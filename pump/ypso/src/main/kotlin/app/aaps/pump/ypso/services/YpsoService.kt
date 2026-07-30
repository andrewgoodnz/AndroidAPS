package app.aaps.pump.ypso.services

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAppExit
import app.aaps.core.interfaces.rx.events.EventOverviewBolusProgress
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.ypso.R
import app.aaps.pump.ypso.YpsoPump
import app.aaps.pump.ypso.events.EventYpsoPumpDataChanged
import app.aaps.pump.ypso.events.EventYpsoPumpDeviceChanged
import app.aaps.pump.ypso.keys.YpsoBooleanKey
import app.aaps.pump.ypso.keys.YpsoIntKey
import app.aaps.pump.ypso.keys.YpsoIntNonKey
import app.aaps.pump.ypso.keys.YpsoStringKey
import com.pump.common.PumpConnectionState
import com.pump.common.PumpHistoryEvent
import com.ypsopump.sdk.YpsoEventTypes
import com.ypsopump.sdk.YpsoPumpManagerImpl
import dagger.android.DaggerService
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Owns the connection to the pump.
 *
 * Structurally this is Medtrum's bound `DaggerService`: the plugin binds it, talks to it through
 * [LocalBinder], and the service holds the pump session for the whole app lifetime. Internally it
 * is nothing like Medtrum's, though — there is no packet state machine and no `SystemClock.sleep`
 * polling. The service owns a [CoroutineScope] and calls the vendored SDK's suspend functions
 * directly.
 *
 * `runBlocking` appears exactly once, in [awaitResult], and only because the AAPS command queue
 * calls into pump drivers from its own thread and expects a synchronous answer. Everything below
 * that line stays suspending.
 *
 * **This build can set the clock and deliver a standard bolus, and nothing else.** There is still
 * no temporary-basal or basal-profile entry point on this service at all — an absence of method,
 * not a flag to check. Each is added on its own, behind its own saline test.
 *
 * [deliverBolus] is under test on saline and writes nothing to `PumpSync`; see its comment.
 */
class YpsoService : DaggerService() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var context: Context
    @Inject lateinit var ypsoPump: YpsoPump
    @Inject lateinit var pumpSync: PumpSync
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var uiInteraction: UiInteraction

    private val binder: IBinder = LocalBinder()
    private val disposable = CompositeDisposable()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var pumpManager: YpsoPumpManagerImpl

    /** Serialises the blocking bridge so two command-queue calls cannot overlap. */
    private val commandLock = Any()

    override fun onCreate() {
        super.onCreate()
        aapsLogger.debug(LTag.PUMP, "YpsoService onCreate")

        pumpManager = YpsoPumpManagerImpl.create(context)
        applyPairedDevice()
        applyAuthSaltPreference()

        disposable += rxBus
            .toObservable(EventAppExit::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ stopSelf() }, fabricPrivacy::logException)

        // A pump chosen in the pairing screen must take effect now, not at the next app start.
        // Dropping the current session first means we never keep talking to the old pump.
        disposable += rxBus
            .toObservable(EventYpsoPumpDeviceChanged::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           scope.launch {
                               pumpManager.disconnect()
                               ypsoPump.reset()
                               applyPairedDevice()
                               rxBus.send(EventYpsoPumpDataChanged())
                           }
                       }, fabricPrivacy::logException)

        disposable += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ event ->
                           if (event.isChanged(YpsoBooleanKey.YpsoLegacyAuthSalt.key)) applyAuthSaltPreference()
                       }, fabricPrivacy::logException)

        // Forward the SDK's own log lines into the AAPS log, so BLE traffic shows up in the
        // same place as everything else during bring-up.
        scope.launch {
            pumpManager.logs.collect { line -> aapsLogger.debug(LTag.PUMPBTCOMM, "[ypso-sdk] $line") }
        }

        scope.launch {
            pumpManager.connectionState.collect { state -> onConnectionStateChanged(state) }
        }

        scope.launch {
            pumpManager.pumpStatus.collect { status ->
                status ?: return@collect
                status.serialNumber.takeIf { it.isNotBlank() }?.let {
                    ypsoPump.serialNumber = it.removePrefix(YPSOPUMP_NAME_PREFIX)
                }
                status.firmwareVersion.takeIf { it.isNotBlank() }?.let { ypsoPump.firmwareVersion = it }
                status.batteryPercent?.let { ypsoPump.batteryPercent = it }
                status.reservoirUnits?.let { ypsoPump.reservoir = it }
                status.activeProfileId?.let { ypsoPump.activeProfileId = it }
                ypsoPump.deliveryModeName = status.deliveryModeName
                ypsoPump.isDelivering = status.isDelivering
                ypsoPump.activeTbr = status.activeTbr
                rxBus.send(EventYpsoPumpDataChanged())
            }
        }

        scope.launch {
            pumpManager.isTbrActive.collect { active ->
                ypsoPump.isTbrRunning = active
                rxBus.send(EventYpsoPumpDataChanged())
            }
        }

        scope.launch {
            pumpManager.pumpClockSkewMs.collect { skew ->
                ypsoPump.pumpClockSkewMs = skew
                rxBus.send(EventYpsoPumpDataChanged())
            }
        }

        // Bolus progress, straight from the pump's own injected figure. Collecting a Flow rather
        // than Medtrum's sleep-poll, and the value is never an estimate from elapsed time — an
        // estimate would keep climbing through an occlusion and show insulin that never went in.
        scope.launch {
            pumpManager.bolusProgress.collect { progress ->
                progress ?: return@collect
                BolusProgressData.delivered = progress.deliveredUnits
                rxBus.send(EventOverviewBolusProgress(rh, progress.deliveredUnits))
            }
        }

        // Alerts the pump has raised since the last status read. An occlusion means insulin is not
        // reaching the patient, and during phase 2 one stopped the pump with AAPS none the wiser —
        // the alert sat in a log nobody reads unless asked.
        pumpManager.seedAlertBaseline(preferences.get(YpsoIntNonKey.LastAlertCount))
        scope.launch {
            pumpManager.newAlerts.collect { alert -> raisePumpAlert(alert) }
        }

        // Persist every change, not just the ones that raised an alarm. The stored count is the
        // high-water mark of what the user has been told about, so an alert that arrives while
        // AAPS is closed still gets announced at the next connection instead of being absorbed
        // into a fresh baseline.
        scope.launch {
            pumpManager.alertCount.collect { count ->
                if (count >= 0) preferences.put(YpsoIntNonKey.LastAlertCount, count)
            }
        }
    }

    /**
     * Puts a pump alert in front of the user.
     *
     * Occlusions are [Notification.URGENT] with a sound: the pump has stopped and insulin is not
     * being delivered, which is not something to discover at the next glance at the phone.
     * Everything else the pump logs as an alert is still worth saying, at a lower level.
     */
    private fun raisePumpAlert(alert: PumpHistoryEvent.Alert) {
        val urgent = alert.code in URGENT_ALERT_CODES
        val text = rh.gs(R.string.ypso_pump_alert, alert.message)
        aapsLogger.warn(LTag.PUMP, "Pump alert ${alert.code} (${alert.message}), urgent=$urgent")

        if (urgent) {
            uiInteraction.addNotificationWithSound(
                Notification.PUMP_ERROR, text, Notification.URGENT, app.aaps.core.ui.R.raw.alarm
            )
        } else {
            uiInteraction.addNotification(Notification.PUMP_WARNING, text, Notification.NORMAL)
        }
    }

    override fun onDestroy() {
        aapsLogger.debug(LTag.PUMP, "YpsoService onDestroy")
        disposable.clear()
        pumpManager.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder = binder

    inner class LocalBinder : Binder() {

        val serviceInstance: YpsoService get() = this@YpsoService
    }

    // ==================== STATE ====================

    val isConnected: Boolean get() = pumpManager.isConnected

    val isConnecting: Boolean
        get() = pumpManager.connectionState.value.let {
            it is PumpConnectionState.Connecting || it is PumpConnectionState.Initializing
        }

    /** Picks up a MAC newly chosen in the pairing screen. */
    fun applyPairedDevice() {
        val mac = ypsoPump.pumpMac
        pumpManager.setPairedDevice(mac.takeIf { it.isNotBlank() }, ypsoPump.pumpName)
    }

    fun applyAuthSaltPreference() {
        pumpManager.useLegacyAuthSalt = preferences.get(YpsoBooleanKey.YpsoLegacyAuthSalt)
    }

    // ==================== CONNECTION ====================

    /**
     * Starts connecting and returns immediately.
     *
     * The AAPS command queue calls this and then waits on [isConnected], so blocking here would
     * only stall its thread for the length of a BLE handshake.
     */
    fun connect(from: String): Boolean {
        if (!ypsoPump.isPaired) {
            aapsLogger.error(LTag.PUMP, "connect($from) refused: no pump paired")
            return false
        }
        aapsLogger.debug(LTag.PUMP, "connect from: $from")
        scope.launch { pumpManager.connect() }
        return true
    }

    fun disconnect(from: String) {
        aapsLogger.debug(LTag.PUMP, "disconnect from: $from")
        scope.launch { pumpManager.disconnect() }
    }

    fun stopConnecting() = disconnect("stopConnecting")

    private fun onConnectionStateChanged(state: PumpConnectionState) {
        aapsLogger.debug(LTag.PUMP, "Connection state: $state")
        when (state) {
            is PumpConnectionState.Connecting  ->
                rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.CONNECTING))

            is PumpConnectionState.Initializing ->
                rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.HANDSHAKING))

            is PumpConnectionState.Ready       -> {
                ypsoPump.onConnectionSucceeded()
                rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.CONNECTED))
            }

            is PumpConnectionState.Disconnected,
            is PumpConnectionState.NotPaired   ->
                rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED))

            is PumpConnectionState.Error       ->
                rxBus.send(EventPumpStatusChanged(state.message))

            else                               -> Unit
        }
        rxBus.send(EventYpsoPumpDataChanged())
    }

    // ==================== READS ====================

    /**
     * Reads reservoir, battery, delivery mode, active program and the active basal profile.
     *
     * @return true if the pump answered.
     */
    fun readPumpStatus(): Boolean = awaitResult("readPumpStatus", false) {
        rxBus.send(EventPumpStatusChanged(rh.gs(R.string.ypso_getting_pump_status)))
        pumpManager.getFullStatus()
        ypsoPump.onConnectionSucceeded()

        // Read the program that the pump says is active, so the rate we report is the rate the
        // pump is actually running rather than whichever program we looked at last.
        val profileId = ypsoPump.activeProfileId.takeIf { it >= 0 } ?: 0
        pumpManager.readBasalProfile(profileId)?.let { profile ->
            ypsoPump.basalProfileRates = profile.blocks.map { it.basalRate }
            aapsLogger.debug(LTag.PUMP, "Basal profile ${profile.name}: ${ypsoPump.basalProfileRates}")
        } ?: aapsLogger.warn(LTag.PUMP, "Basal profile read returned nothing")

        rxBus.send(EventYpsoPumpDataChanged())
        true
    }

    /**
     * Reads the pump's event and alert logs.
     *
     * Always produces a human-readable dump for the firmware parity check. Writing the events
     * into AAPS treatment records is gated behind [YpsoBooleanKey.YpsoSyncHistoryToAaps] — see
     * that key's comment for why it defaults to off.
     */
    fun loadHistory(): Boolean = awaitResult("loadHistory", false) {
        val count = preferences.get(YpsoIntKey.YpsoHistoryReadCount)

        val result = pumpManager.readHistoryWithDump(count)
        ypsoPump.lastHistoryDump = result.rawDump
        result.rawDump.forEach { aapsLogger.debug(LTag.PUMP, "[history] $it") }
        aapsLogger.debug(LTag.PUMP, "Mapped ${result.events.size} of ${result.rawDump.size} entries")

        if (preferences.get(YpsoBooleanKey.YpsoSyncHistoryToAaps)) {
            result.events.forEach { syncToAaps(it) }
        } else {
            aapsLogger.info(LTag.PUMP, "History sync to AAPS is disabled; ${result.events.size} event(s) logged only")
        }

        ypsoPump.onConnectionSucceeded()
        rxBus.send(EventYpsoPumpDataChanged())
        true
    }

    /** Reads one of the pump's two basal programs without making it active. */
    fun readBasalProfile(profileId: Int): List<Double>? = awaitResult("readBasalProfile", null) {
        pumpManager.readBasalProfile(profileId)?.blocks?.map { it.basalRate }
    }

    // ==================== WRITES ====================

    /**
     * Sets the pump's clock from the phone's — the only write this build performs.
     *
     * It is deliberately the first one: no insulin can move, so a wrong wire format costs a
     * refused GATT write rather than a dose. The result is whatever reading the clock back said,
     * never what the write callback said — see `YpsoPumpManagerImpl.syncDateTime`.
     *
     * Every other write (bolus, temporary basal, basal profile) is still refused below the
     * service, and each one is its own saline test before it is unblocked.
     */
    fun syncPumpTime(): Boolean = awaitResult("syncPumpTime", false) {
        rxBus.send(EventPumpStatusChanged(rh.gs(R.string.ypso_setting_pump_clock)))
        // Pessimistic up front, so an exception escaping into awaitResult's catch leaves
        // "not confirmed" behind rather than whatever the previous attempt reported.
        ypsoPump.lastClockWriteConfirmed = false
        val confirmed = pumpManager.syncDateTime()
        ypsoPump.lastClockWriteConfirmed = confirmed
        if (confirmed) ypsoPump.onConnectionSucceeded()
        aapsLogger.info(LTag.PUMP, "syncPumpTime: pump ${if (confirmed) "adopted" else "did NOT adopt"} the phone's clock")
        rxBus.send(EventYpsoPumpDataChanged())
        confirmed
    }

    /**
     * Delivers a standard bolus, reports what the **pump** says went in, and records it.
     *
     * The treatment is recorded under the **pump's own sequence number** for the bolus, which is
     * the same identity `readHistory` puts in `PumpHistoryEvent.position`. That is what stops this
     * record and a later history sync of the same bolus becoming two boluses — the two paths name
     * the event the way the pump names it, so `syncBolusWithPumpId` recognises them as one.
     *
     * A bolus the pump's log did not confirm has no sequence number and is **not recorded**. An
     * invented identity would be worse than a missing record, because nothing could reconcile it
     * afterwards; the failure is reported loudly instead.
     *
     * A partial delivery — stopped by the user, or cut short by the pump — **is** recorded, for the
     * amount that actually went in. That is the case where not recording would corrupt IOB.
     */
    fun deliverBolus(detailedBolusInfo: DetailedBolusInfo): YpsoPumpManagerImpl.ConfirmedBolus {
        val insulin = detailedBolusInfo.insulin
        val failed = YpsoPumpManagerImpl.ConfirmedBolus(
            requestedUnits = insulin,
            deliveredUnits = 0.0,
            pumpSequence = null,
            timestampMillis = null,
            complete = false,
            errorMessage = rh.gs(R.string.ypso_bolus_failed)
        )

        return awaitResult("deliverBolus", failed) {
            pumpManager.bolusSpeedUnitsPerMinute = configuredBolusSpeedUnitsPerMinute()
            val result = pumpManager.deliverBolusConfirmed(insulin)
            aapsLogger.info(
                LTag.PUMP,
                "deliverBolus: asked ${insulin}U, pump delivered ${result.deliveredUnits}U, " +
                    "success=${result.complete}" + (result.errorMessage?.let { ", $it" } ?: "")
            )

            if (result.deliveredUnits > 0.0) {
                ypsoPump.lastBolusAmount = result.deliveredUnits
                ypsoPump.lastBolusTime = dateUtil.now()
            }
            recordBolus(detailedBolusInfo, result)

            rxBus.send(EventYpsoPumpDataChanged())
            result
        }
    }

    private fun recordBolus(detailedBolusInfo: DetailedBolusInfo, result: YpsoPumpManagerImpl.ConfirmedBolus) {
        val sequence = result.pumpSequence
        if (sequence == null || result.deliveredUnits <= 0.0) {
            aapsLogger.warn(
                LTag.PUMP,
                "Not recording a treatment: " +
                    if (sequence == null) "the pump's log did not confirm this bolus, so it has no stable id"
                    else "the pump delivered nothing"
            )
            return
        }

        // The **phone's** clock, not the pump's entry timestamp.
        //
        // Using the pump's put a bolus 12 hours into the future and so out of every window AAPS
        // cares about: the treatment existed but showed nowhere and contributed no IOB, while the
        // dialog reported success because the *amount* had been confirmed. The pump counts its
        // clock in local terms and the decoder read it as UTC (see PORTING_NOTES §5), and beyond
        // that bug, a treatment time that depends on the pump's clock being right is a bad trade
        // for something the phone knows exactly.
        //
        // Dedupe is unaffected: `syncBolusWithPumpId` matches on `pumpId`, so a later history sync
        // of this same bolus still resolves to one record.
        val synced = pumpSync.syncBolusWithPumpId(
            timestamp = dateUtil.now(),
            amount = result.deliveredUnits,
            type = detailedBolusInfo.bolusType,
            pumpId = sequence,
            pumpType = PumpType.YPSOPUMP,
            pumpSerial = pumpSerial()
        )
        aapsLogger.info(
            LTag.PUMP,
            "Recorded ${result.deliveredUnits}U under pumpId=$sequence (the pump's own sequence), new=$synced"
        )
    }

    /**
     * Identity used for every `PumpSync` call. Trimmed, because the pump's device-name
     * characteristic used to hand back a NUL terminator and a serial differing by an invisible
     * byte would split records between two apparent pumps.
     */
    private fun pumpSerial(): String =
        ypsoPump.serialNumber.trim { it.code <= 0x20 }.ifBlank { ypsoPump.pumpMac }

    /**
     * Asks the pump to stop the running bolus.
     *
     * Launched rather than awaited: AAPS calls this from the UI thread while [deliverBolus] is
     * still blocking the command-queue thread, so waiting here would deadlock the stop against
     * the delivery it is trying to stop.
     */
    fun stopBolus() {
        aapsLogger.info(LTag.PUMP, "stopBolus requested")
        scope.launch { pumpManager.cancelBolus(null) }
    }

    /** The pump's own speed index: `0` = 3 U/min, `1` = 12 U/min, `2` = 33.3 U/min. */
    private fun configuredBolusSpeedUnitsPerMinute(): Double =
        when (preferences.get(YpsoStringKey.YpsoBolusSpeed)) {
            "0"  -> 3.0
            "2"  -> 33.3
            else -> 12.0
        }

    // ==================== PUMPSYNC ====================

    private fun syncToAaps(event: PumpHistoryEvent) {
        val serial = ypsoPump.serialNumber.ifBlank { ypsoPump.pumpMac }
        when (event) {
            is PumpHistoryEvent.BolusDelivered -> pumpSync.syncBolusWithPumpId(
                timestamp = event.timestamp,
                amount = event.amount,
                type = null,
                pumpId = event.position,
                pumpType = PumpType.YPSOPUMP,
                pumpSerial = serial
            )

            is PumpHistoryEvent.TbrStarted     -> pumpSync.syncTemporaryBasalWithPumpId(
                timestamp = event.timestamp,
                rate = event.percentage.toDouble(),
                duration = event.durationMinutes * 60L * 1000L,
                isAbsolute = false,
                type = null,
                pumpId = event.position,
                pumpType = PumpType.YPSOPUMP,
                pumpSerial = serial
            )

            is PumpHistoryEvent.TbrEnded       -> pumpSync.syncStopTemporaryBasalWithPumpId(
                timestamp = event.timestamp,
                endPumpId = event.position,
                pumpType = PumpType.YPSOPUMP,
                pumpSerial = serial
            )

            is PumpHistoryEvent.CartridgeChanged -> insertTherapyEvent(TE.Type.INSULIN_CHANGE, event, serial)

            is PumpHistoryEvent.Alert          -> pumpSync.insertAnnouncement(
                error = event.message,
                pumpId = event.position,
                pumpType = PumpType.YPSOPUMP,
                pumpSerial = serial
            )

            is PumpHistoryEvent.Unknown        -> syncConsumableChange(event, serial)

            else                               ->
                aapsLogger.debug(LTag.PUMP, "No PumpSync mapping for ${event::class.simpleName}, skipped")
        }
    }

    /**
     * Turns the pump's mechanical events into AAPS therapy events, per the mapping confirmed
     * against mylife Lotus: cannula priming is a site change, battery removal is a battery
     * change, and a prime counts as a tubing change only when it was large enough to have
     * actually filled a line — a small prime is a top-up, not a set change.
     */
    private fun syncConsumableChange(event: PumpHistoryEvent.Unknown, serial: String) {
        when (event.eventType) {
            YpsoEventTypes.CANNULA_PRIMING_FINISHED -> insertTherapyEvent(TE.Type.CANNULA_CHANGE, event, serial)

            YpsoEventTypes.BATTERY_REMOVED          -> insertTherapyEvent(TE.Type.PUMP_BATTERY_CHANGE, event, serial)

            YpsoEventTypes.PRIMING_FINISHED         -> {
                val primed = YpsoEventTypes.primedUnits(event.rawData)
                if (primed > YpsoEventTypes.TUBE_CHANGE_PRIME_THRESHOLD_UNITS) {
                    insertTherapyEvent(TE.Type.TUBE_CHANGE, event, serial)
                } else {
                    aapsLogger.debug(LTag.PUMP, "Prime of ${primed}U below tube-change threshold, not recorded")
                }
            }

            else                                    ->
                aapsLogger.debug(LTag.PUMP, "Unmapped pump event type ${event.eventType}, skipped")
        }
    }

    private fun insertTherapyEvent(type: TE.Type, event: PumpHistoryEvent, serial: String) {
        val enabled = when (type) {
            TE.Type.INSULIN_CHANGE  -> preferences.get(YpsoBooleanKey.YpsoLogInsulinChange)
            TE.Type.CANNULA_CHANGE  -> preferences.get(YpsoBooleanKey.YpsoLogSiteChange)
            TE.Type.TUBE_CHANGE     -> preferences.get(YpsoBooleanKey.YpsoLogTubeChange)
            TE.Type.PUMP_BATTERY_CHANGE -> preferences.get(YpsoBooleanKey.YpsoLogBatteryChange)
            else                    -> true
        }
        if (!enabled) return
        pumpSync.insertTherapyEventIfNewWithTimestamp(
            timestamp = event.timestamp,
            type = type,
            pumpId = event.position,
            pumpType = PumpType.YPSOPUMP,
            pumpSerial = serial
        )
    }

    // ==================== BLOCKING BRIDGE ====================

    /**
     * The one place suspending code is made synchronous, for the AAPS command queue.
     *
     * A failure is reported as [fallback] and logged; it never throws into the queue thread,
     * because an exception escaping here aborts the whole command chain.
     */
    private fun <T> awaitResult(what: String, fallback: T, block: suspend () -> T): T =
        synchronized(commandLock) {
            try {
                runBlocking { block() }
            } catch (e: Exception) {
                aapsLogger.error(LTag.PUMP, "$what failed: ${e.message}", e)
                fallback
            }
        }

    companion object {

        private const val YPSOPUMP_NAME_PREFIX = "YpsoPump_"

        /**
         * Alert codes that mean insulin is not being delivered. These get an urgent, audible
         * notification; the rest of the pump's alerts are informational by comparison.
         */
        private val URGENT_ALERT_CODES = setOf(
            YpsoEventTypes.A_OCCLUSION,
            YpsoEventTypes.A_CARTRIDGE_EMPTY,
            YpsoEventTypes.A_NO_CARTRIDGE,
            YpsoEventTypes.A_AUTO_STOP,
            YpsoEventTypes.A_BATTERY_EMPTY,
            YpsoEventTypes.A_REUSABLE_ERROR
        )
    }
}
