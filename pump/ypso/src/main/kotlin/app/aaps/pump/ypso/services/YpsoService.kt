package app.aaps.pump.ypso.services

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.time.T
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
import app.aaps.core.interfaces.rx.events.EventDismissNotification
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
import app.aaps.pump.ypso.keys.YpsoLongNonKey
import app.aaps.pump.ypso.keys.YpsoStringKey
import app.aaps.pump.ypso.keys.YpsoStringNonKey
import com.pump.common.BasalBlock
import com.pump.common.BasalProfile
import com.pump.common.OperatingMode
import com.pump.common.PumpConnectionState
import com.pump.common.PumpHistoryEvent
import com.pump.common.PumpStatus
import com.ypsopump.sdk.BolusChunkPlan
import com.ypsopump.sdk.BolusSpeed
import com.ypsopump.sdk.HistorySyncDecision
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
import kotlin.math.abs

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
 * **This build can set the clock, deliver a standard bolus, set a temporary basal and overwrite the
 * 24-slot basal profile** — every one of them verified on saline behind its own hardware test. The
 * extended and combined bolus are the only writes still refused, below this service.
 *
 * Every write here is confirmed against the pump before it is reported as done, and each records
 * into `PumpSync` under the pump's own sequence number so that a later history sync of the same
 * event resolves to one record rather than two.
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

    /**
     * When the 0% suspend record observed at the previous poll was due to expire, or null if there
     * was none.
     *
     * The only reason this exists is that **the obvious check cannot fail** (§8.6). `logDeliveryState`
     * runs *after* `syncSuspendedState`, so by the time a line is written a fresh record always
     * exists and every line reads `suspendRecord=open` — including the pairs bracketing a
     * twenty-minute hole. The 13h40 run that contained two real gaps reported zero.
     *
     * A lapse is only visible by noticing that the record which *should* still have been open is
     * gone. That needs one number carried between polls, and this is it. Held in memory
     * deliberately: it is a within-run instrument, and a restart is not a lapse.
     */
    private var suspendRecordExpiryMs: Long? = null

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

        // Every status read, including the ones that changed nothing — see
        // YpsoPumpManagerImpl.statusReads for why the StateFlow above cannot carry this.
        scope.launch {
            pumpManager.statusReads.collect { status ->
                syncSuspendedState(status.operatingMode, status.deliveryModeName)
                logDeliveryState(status)
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
     * Keeps the pump's clock on the phone's, correcting it rather than complaining about it.
     *
     * ⚠ **The pump's clock is the sole authority on when anything in its log happened**, and with
     * history sync on (§8.5) those timestamps become treatment times in AAPS. A slow clock makes a
     * bolus look older than it is, so its IOB decays from too early, so AAPS reads less insulin on
     * board than there is — the stacking direction. Drift is therefore a dosing problem, not a
     * cosmetic one, and the interval that matters is **seconds**.
     *
     * An earlier version of this raised a notification above half an hour. That was the wrong
     * mechanism as well as the wrong number: there is a proven write here that refuses unless it can
     * read the clock back first (§4e), so the answer to a wrong clock is to fix it. Medtronic does
     * exactly this — `if (timeDiff > 20) … set time on pump` — and it is the convention worth
     * following.
     *
     * Two guards:
     *
     * - **A difference beyond [CLOCK_IMPLAUSIBLE_HOURS] is not drift**, it is something wrong: a
     *   phone with no network since a factory reset, or a pump whose clock was lost with its
     *   battery. Silently writing a year-wrong time into a pump is not a correction. Medtronic
     *   refuses over 24 h for the same reason; this says so and leaves it to a human.
     * **Not rate-limited.** An earlier version was, on the reasoning that each correction appends two
     * entries (type 12 and 13) to the pump's 3000-entry log. That defended a failing crystal nobody
     * has observed: the one measurement of this pump is ~0 s of drift over 9 hours. A correction that
     * fires often enough to churn the log is itself the finding, and it will be visible in the log
     * line above without machinery built to anticipate it.
     */
    private suspend fun correctClockIfDrifted() {
        val skewMs = ypsoPump.pumpClockSkewMs ?: return
        val skewSeconds = abs(skewMs) / 1000L
        if (skewSeconds < CLOCK_RESYNC_THRESHOLD_SECONDS) {
            // Logged even when there is nothing to do, because this is the number that says whether a
            // history-synced timestamp landing seconds away from the phone's is clock drift or just
            // how long the bolus took to deliver (Phase 7c). Without it the two are indistinguishable.
            aapsLogger.debug(LTag.PUMP, "Pump clock is ${skewMs}ms from the phone's, under the ${CLOCK_RESYNC_THRESHOLD_SECONDS}s threshold; left alone")
            return
        }

        if (skewSeconds > CLOCK_IMPLAUSIBLE_HOURS * 3600L) {
            aapsLogger.error(
                LTag.PUMP,
                "Pump clock is ${skewSeconds / 3600} h from the phone's. That is not drift, and this driver will not " +
                    "write a correction that large unattended. Check both clocks."
            )
            uiInteraction.addNotification(
                Notification.INSIGHT_DATE_TIME_UPDATED,
                rh.gs(R.string.ypso_clock_implausible, skewSeconds / 3600),
                Notification.URGENT
            )
            return
        }

        aapsLogger.info(LTag.PUMP, "Pump clock is ${skewSeconds}s from the phone's; correcting it")
        if (pumpManager.syncDateTime()) {
            rxBus.send(EventDismissNotification(Notification.INSIGHT_DATE_TIME_UPDATED))
            uiInteraction.addNotificationValidFor(
                Notification.INSIGHT_DATE_TIME_UPDATED,
                rh.gs(R.string.ypso_clock_corrected, skewSeconds),
                Notification.INFO,
                60
            )
        } else {
            aapsLogger.error(LTag.PUMP, "Pump clock correction FAILED; its log timestamps remain ${skewSeconds}s out")
            uiInteraction.addNotification(
                Notification.INSIGHT_DATE_TIME_UPDATED,
                rh.gs(R.string.ypso_clock_drifted, skewSeconds),
                Notification.NORMAL
            )
        }
    }

    /**
     * Tells AAPS that a stopped pump is delivering **no basal**, by holding a 0% temporary basal
     * open for as long as it is stopped.
     *
     * ⚠ `isSuspended()` is not enough on its own, and that was the gap. It stops the *loop* from
     * acting, but AAPS's IOB accounting does not consult it: basal insulin on board is computed from
     * the profile and whatever temporary basals are on record. A pump that has stopped — by the
     * user's hand, or because an occlusion halted it — therefore goes on contributing basal IOB that
     * is not being delivered, indefinitely and invisibly. That is the same shape as the temporary
     * basal cancel that left AAPS believing a TBR was still running (`PORTING_NOTES` §4h): the pump
     * was right, the driver was right, and only AAPS's own record was wrong.
     *
     * The pattern is Medtrum's — every driver that can detect a stopped pump does this. The record
     * is a `PUMP_SUSPEND` temporary basal, which is the type reserved for a pump stopping of its own
     * accord rather than AAPS asking for zero.
     *
     * [SUSPEND_TBR_MINUTES] is distinctive on purpose: it is how the record is recognised as this
     * driver's own on resume, so a real temporary basal is never mistaken for one and closed by
     * accident. It also expires, so a record orphaned by a crash does not outlive the outage by
     * days — which is why it has to be re-asserted, and why it is driven from
     * `YpsoPumpManagerImpl.statusReads` rather than from the status `StateFlow`. A stopped pump
     * reports the same bytes poll after poll, a `StateFlow` conflates equal values, and this ran
     * once on the transition and then never again: the record would have expired after
     * [SUSPEND_TBR_MINUTES] with the pump still stopped, and AAPS would have gone back to counting
     * basal IOB that was not being delivered. That is the same bug this method exists to fix,
     * arriving by a different door.
     *
     * ⚠ **An unrecognised delivery mode does not open this record**, and that distinction is the
     * whole reason [PumpStatus.operatingMode] is a three-valued thing rather than `isDelivering`.
     * Only two of 256 byte-0 values have ever been identified (§8.3) — but that is now the answer
     * rather than a gap: every state reachable on hardware came back as one of the two, a bolus
     * included (it reads `10`), and there is no pause state on this pump at all. The rule stands
     * anyway, because it costs nothing and cartridge-out and reservoir-empty have still not been
     * read; if either turns out to be a third value, treating unknown as stopped would open a
     * spurious suspend record.
     *
     * `isSuspended()` still reports true for an unknown mode, so the **loop** stays conservative and
     * refuses to act. That costs nothing and reverses instantly. Writing a therapy record is a claim
     * that persists, and this driver does not make claims from bytes it has not identified.
     */
    private fun syncSuspendedState(mode: OperatingMode, modeName: String) {
        val ourSuspendRecord = openSuspendRecord()
        val now = dateUtil.now()
        val remainingMs = ourSuspendRecord?.let { (it.timestamp + it.duration) - now }

        // The arithmetic lives in SuspendRecordDecision, where a test can reach it. Everything
        // below is the part that needs AAPS: records, notifications and log lines.
        val decision = SuspendRecordDecision.decide(mode, remainingMs, T.mins(SUSPEND_TBR_RENEW_BELOW_MINUTES).msecs())

        // Did the record open at the previous poll survive to this one? This is the only moment a
        // lapse is observable — a replacement is about to be opened, and every line written after
        // that reads `suspendRecord=open` whether or not there was a hole. Asked before anything
        // acts, and only while stopped: the record is *meant* to be gone once delivery resumes.
        if (decision != SuspendRecordDecision.Close && decision != SuspendRecordDecision.Clear) {
            SuspendRecordDecision.lapsedByMs(ourSuspendRecord != null, suspendRecordExpiryMs, now)?.let { holeMs ->
                aapsLogger.error(
                    LTag.PUMP,
                    "[ypso-suspend] LAPSED — the 0% record expired at ${dateUtil.dateAndTimeString(suspendRecordExpiryMs!!)} and " +
                        "nothing replaced it for ${holeMs / 1000}s. AAPS counted basal IOB for a stopped pump for that long. " +
                        "This is the SALINE_TEST_PROCEDURE Phase 6b failure, and it is the only line that reports it"
                )
            }
        }

        when (decision) {
            SuspendRecordDecision.Ignore -> {
                aapsLogger.warn(
                    LTag.PUMP,
                    "Delivery mode '$modeName' has never been identified; the loop is held off but no suspend record is written. " +
                        "Record what the pump's own display says against this mode — PORTING_NOTES §8.3"
                )
                // No record is maintained in this state on purpose, so one expiring here is the
                // design rather than the Phase 6b failure. Stop watching, or the next stopped poll
                // would report a lapse this branch caused deliberately.
                suspendRecordExpiryMs = null
            }

            SuspendRecordDecision.Hold   ->
                suspendRecordExpiryMs = ourSuspendRecord!!.timestamp + ourSuspendRecord.duration

            SuspendRecordDecision.Open,
            SuspendRecordDecision.Renew  -> {
                val synced = pumpSync.syncTemporaryBasalWithPumpId(
                    timestamp = now,
                    rate = 0.0,
                    duration = T.mins(SUSPEND_TBR_MINUTES).msecs(),
                    isAbsolute = true,
                    type = PumpSync.TemporaryBasalType.PUMP_SUSPEND,
                    pumpId = now,
                    pumpType = PumpType.YPSOPUMP,
                    pumpSerial = pumpSerial()
                )
                suspendRecordExpiryMs = now + T.mins(SUSPEND_TBR_MINUTES).msecs()

                if (decision == SuspendRecordDecision.Renew) {
                    // Still the same suspension, just topped up. Not news, and not a second alarm:
                    // the user was told when it started and nothing has changed since.
                    aapsLogger.info(
                        LTag.PUMP,
                        "Pump still not delivering (mode '$modeName'); renewed the 0% basal with " +
                            "${remainingMs!! / 60000} min left, new=$synced"
                    )
                } else {
                    aapsLogger.warn(
                        LTag.PUMP,
                        "Pump is not delivering (mode '$modeName'); recorded a 0% basal so AAPS stops counting basal IOB, new=$synced"
                    )
                    uiInteraction.addNotification(
                        Notification.PUMP_SUSPENDED, rh.gs(R.string.ypso_pump_suspended, modeName), Notification.URGENT
                    )
                }
            }

            SuspendRecordDecision.Clear  -> {
                rxBus.send(EventDismissNotification(Notification.PUMP_SUSPENDED))
                suspendRecordExpiryMs = null
            }

            // Delivering again. Closes only a record this driver opened — openSuspendRecord()
            // identifies it by type *and* duration, because closing a real temporary basal would
            // tell AAPS basal resumed when it had not.
            SuspendRecordDecision.Close  -> {
                rxBus.send(EventDismissNotification(Notification.PUMP_SUSPENDED))
                suspendRecordExpiryMs = null
                val stopped = pumpSync.syncStopTemporaryBasalWithPumpId(
                    timestamp = now,
                    endPumpId = now,
                    pumpType = PumpType.YPSOPUMP,
                    pumpSerial = pumpSerial()
                )
                aapsLogger.info(LTag.PUMP, "Pump is delivering again (mode '$modeName'); closed the 0% suspend record, new=$stopped")
            }
        }
    }

    /**
     * The 0% temporary basal this driver opens for a stopped pump, if one is currently open.
     *
     * Recognised by type **and** duration together. A real temporary basal AAPS asked for has a
     * different type; [SUSPEND_TBR_MINUTES] is the second half of the signature, so that a
     * `PUMP_SUSPEND` record left by anything else is not closed as though it were ours.
     */
    private fun openSuspendRecord(): PumpSync.PumpState.TemporaryBasal? =
        pumpSync.expectedPumpState().temporaryBasal?.takeIf {
            it.type == PumpSync.TemporaryBasalType.PUMP_SUSPEND && it.duration == T.mins(SUSPEND_TBR_MINUTES).msecs()
        }

    /**
     * One greppable line per status read, carrying the bytes rather than the driver's reading of
     * them.
     *
     * **Written to be read from `logcat` by someone standing at the pump**, in a state where they
     * cannot look at the phone's screen — an alarm sounding, a cartridge half-changed, a bolus
     * running. That is why the raw byte and the whole payload are here and not only on the YPSO
     * tab: 254 of byte 0's 256 values have never been identified (§8.3), and the states that would
     * identify them are exactly the states in which the phone is not the thing being watched.
     *
     * Everything on the line is either a raw pump byte or a fact about AAPS's own records, so the
     * two can be compared without trusting the decode that sits between them:
     *
     * ```
     * [ypso-state] byte0=10 name='Running' mode=STARTED delivering=true suspendRecord=none raw=[0A 2B ...]
     * ```
     *
     * `suspendRecord` is sampled **after** [syncSuspendedState] has run, so the line says what this
     * poll settled on rather than what it inherited — which is the question when the thing being
     * checked is whether a bolus opens one.
     */
    private fun logDeliveryState(status: PumpStatus) {
        val suspendRecord = openSuspendRecord()
        aapsLogger.info(
            LTag.PUMP,
            "[ypso-state] byte0=${status.deliveryModeRaw} name='${status.deliveryModeName}' mode=${status.operatingMode} " +
                "delivering=${status.isDelivering} " +
                "suspendRecord=${
                    suspendRecord?.let {
                        // The minutes left are what make a line from the *middle* of a run readable.
                        // `open` alone is true on both sides of a hole; a figure that counts down
                        // 60 → 40 → 60 and never approaches zero is what a held record looks like,
                        // and one line says so without arithmetic across the run.
                        "open since ${dateUtil.dateAndTimeString(it.timestamp)} " +
                            "(${((it.timestamp + it.duration) - dateUtil.now()) / 60000}min left of ${it.duration / 60000})"
                    } ?: "none"
                } " +
                "rate=${status.activeBasalRate}U/h tbr=${status.activeTbr?.let { "${it.percentage}% ${it.remainingMinutes}min" } ?: "none"} " +
                "reservoir=${status.reservoirUnits ?: "unknown"} " +
                "raw=[${status.rawStatusHex}]"
        )
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

        correctClockIfDrifted()
        syncHistoryToAaps()

        rxBus.send(EventYpsoPumpDataChanged())
        true
    }

    /**
     * Brings anything the pump did on its own into AAPS — above all, a bolus given from the pump's
     * own menu, which nothing else here would ever see.
     *
     * Until this ran, `loadHistory` was only called when the user pressed a button, so a manual bolus
     * was invisible to the loop: IOB under-stated, and AAPS dosing on top of insulin already given.
     * That is the stacking direction, and it was the largest gap left before insulin.
     *
     * **Shaped on Medtrum's `syncRecords`**, which is the closest analogue in the codebase — a
     * persisted sequence watermark, read forward from it, advance it, and treat a hole as a safety
     * event rather than a log line. Two things make it workable here, both established on hardware:
     * events and alerts share one monotonic sequence counter, so a single watermark covers both; and
     * `syncBolusWithPumpId` is an **upsert keyed on `pumpId`**, so re-reading an entry is harmless
     * and the watermark is an optimisation rather than a correctness requirement.
     *
     * Three rules, each for a reason that cost something to learn:
     *
     * 1. **A missing watermark means take a baseline, not import everything.** Switching this on
     *    would otherwise turn the pump's recent history into new treatments carrying IOB — boluses
     *    from hours ago, possibly from before AAPS was installed. The alert count already works this
     *    way (§4g) for exactly the same reason.
     * 2. ~~**Entries this driver recorded itself are skipped.**~~ **Dropped, and this comment used to
     *    claim it.** The skip list existed so a bolus AAPS delivered would keep the *phone's*
     *    timestamp rather than the pump's. A sync is a sync: it upserts on `pumpId`, the amount comes
     *    from the same log entry either way, and the only field that moved was the timestamp. It
     *    defended half a problem — a manual bolus has no source but the pump — and left two classes
     *    of record with different provenance. The clock is the thing worth defending, and it is now
     *    corrected to within 10 s instead (§8.6).
     * 3. **A gap is announced, not stepped over.** If the oldest entry read is still above the
     *    watermark, entries in between existed and were never seen — the pump was busy while AAPS was
     *    away, or a profile write pushed 24 entries in at once. The watermark still advances, because
     *    not advancing would re-read the same window for ever, but the user is told that treatments
     *    are missing rather than left to infer it from IOB that does not add up. ⚠ That check was
     *    measured across both logs at once and could not fire; it is per window now (§8.5).
     */
    private suspend fun syncHistoryToAaps() {
        if (!preferences.get(YpsoBooleanKey.YpsoSyncHistoryToAaps)) return

        // Timing is not diagnostics here, it is the measurement Phase 7d exists to take: a 50-entry
        // window at ~195 ms an entry is ~10 s on every connection, which is survivable for a manual
        // driver and not survivable for a loop issuing an SMB every five minutes (§8.5). Read and
        // import are timed apart because only the read scales with the window.
        val startedAt = System.currentTimeMillis()
        val watermark = preferences.get(YpsoLongNonKey.LastSyncedSequence)
        aapsLogger.info(
            LTag.PUMP,
            "History sync: starting, watermark=" + if (watermark < 0) "unset ($watermark)" else "$watermark"
        )

        // Ask the cheap question first. Index 0 is the newest entry, so two entry reads say whether
        // anything happened at all; the full window costs ~20 s and on most connections answers
        // "nothing". See HistorySyncDecision for why skipping is equivalent rather than approximate.
        val newestSeen = pumpManager.readNewestSequence()
        val probeMillis = System.currentTimeMillis() - startedAt
        if (newestSeen == null) {
            aapsLogger.warn(LTag.PUMP, "History sync: the pump returned no entries at all; nothing to do")
            return
        }

        when (HistorySyncDecision.decide(watermark, newestSeen)) {
            HistorySyncDecision.TakeBaseline -> {
                preferences.put(YpsoLongNonKey.LastSyncedSequence, newestSeen)
                aapsLogger.info(
                    LTag.PUMP,
                    "History sync: no watermark yet, so taking sequence $newestSeen as the baseline and importing " +
                        "nothing. Anything the pump does from here is synced. " +
                        "(watermark unset -> $newestSeen, from a $probeMillis ms probe; no window was read)"
                )
                return
            }

            HistorySyncDecision.UpToDate  -> {
                aapsLogger.info(
                    LTag.PUMP,
                    "History sync: newest sequence is $newestSeen, still at the watermark — nothing new, so the " +
                        "window was not read. Probe took $probeMillis ms."
                )
                return
            }

            HistorySyncDecision.SequenceWentBackwards -> {
                // Loud on purpose. The old code reached this silently and resumed from the lower
                // watermark, losing up to a window of treatments with no warning of any kind.
                aapsLogger.error(
                    LTag.PUMP,
                    "History sync: the pump's newest sequence is $newestSeen but the watermark is $watermark. " +
                        "Sequences do not go backwards on this pump, so either the event log wrapped, this is a " +
                        "different pump, or a backup was restored. Re-baselining at $newestSeen and importing " +
                        "nothing — any treatment between the two will not appear in AAPS."
                )
                preferences.put(YpsoLongNonKey.LastSyncedSequence, newestSeen)
                return
            }

            HistorySyncDecision.ReadFully -> Unit
        }

        val readStartedAt = System.currentTimeMillis()
        val result = pumpManager.readHistoryWithDump(preferences.get(YpsoIntKey.YpsoHistoryReadCount))
        val readMillis = System.currentTimeMillis() - readStartedAt
        val entriesRead = result.eventSequences.size + result.alertSequences.size
        aapsLogger.info(
            LTag.PUMP,
            "History sync: read ${result.eventSequences.size} event(s) seq " +
                "${result.eventSequences.minOrNull()}..${result.eventSequences.maxOrNull()}, " +
                "${result.alertSequences.size} alert(s) seq " +
                "${result.alertSequences.minOrNull()}..${result.alertSequences.maxOrNull()}, " +
                "${result.events.size} of them mapped — in $readMillis ms" +
                if (entriesRead > 0) " (${readMillis / entriesRead} ms/entry)" else ""
        )

        // The probe already said there is something above the watermark, so an empty window here is
        // the pump contradicting itself between two reads seconds apart, not the ordinary quiet case.
        val newest = result.newestSequence
        if (newest == null) {
            aapsLogger.warn(
                LTag.PUMP,
                "History sync: the probe reported $newestSeen but the window read returned no entries at all. " +
                    "Nothing imported."
            )
            return
        }

        // The gap check is logged with its inputs whatever the answer, because this arithmetic was
        // wrong for three sessions and read as "no gap" the whole time (§8.5). An absent warning is
        // only evidence if the numbers it was computed from are on the record next to it.
        val missed = result.missedSince(watermark)
        aapsLogger.info(
            LTag.PUMP,
            "History sync: gap check against watermark $watermark — " +
                "events newest=${result.eventSequences.maxOrNull()} oldest=${result.eventSequences.minOrNull()}, " +
                "alerts newest=${result.alertSequences.maxOrNull()} oldest=${result.alertSequences.minOrNull()} " +
                "-> $missed missed"
        )
        if (missed > 0) {
            // A log line rather than a notification: this has never happened, and the read window is
            // 50 entries against a 24-entry worst case (a full basal profile write). If it starts
            // happening the log says so and the window can be raised.
            aapsLogger.error(
                LTag.PUMP,
                "History sync: $missed entr(ies) above $watermark were never read and are lost. " +
                    "Windows: ${result.eventSequences.size} event(s) back to ${result.eventSequences.minOrNull()}, " +
                    "${result.alertSequences.size} alert(s) back to ${result.alertSequences.minOrNull()} — " +
                    "raise YpsoHistoryReadCount if this repeats."
            )
        }

        val fresh = result.freshEvents(watermark)

        // Every mapped entry the read covered, with the decision taken on it and why. Phase 7 has to
        // adjudicate "nothing was imported" and "one thing was imported" apart from "nothing was
        // looked at", and only the entries that were considered and rejected tell those apart.
        result.events
            .filter { it.position <= watermark }
            .sortedBy { it.position }
            .forEach { event ->
                aapsLogger.debug(
                    LTag.PUMP,
                    "History sync: skipping ${event::class.simpleName} seq=${event.position} " +
                        "at ${dateUtil.dateAndTimeAndSecondsString(event.timestamp)} — at or below watermark $watermark"
                )
            }

        val importedAt = System.currentTimeMillis()
        fresh.forEach { event ->
            aapsLogger.info(
                LTag.PUMP,
                "History sync: importing ${event::class.simpleName} seq=${event.position} " +
                    "at ${dateUtil.dateAndTimeAndSecondsString(event.timestamp)}"
            )
            syncToAaps(event)
        }

        preferences.put(YpsoLongNonKey.LastSyncedSequence, newest)
        aapsLogger.info(
            LTag.PUMP,
            "History sync: imported ${fresh.size} event(s) above $watermark; watermark $watermark -> $newest. " +
                "Probe $probeMillis ms. " +
                "Took ${System.currentTimeMillis() - startedAt} ms for $entriesRead entr(ies) " +
                "(read $readMillis ms, import ${System.currentTimeMillis() - importedAt} ms)"
        )
    }


    /**
     * Reads the pump's event and alert logs.
     *
     * Always produces a human-readable dump for the firmware parity check. Writing the events
     * into AAPS treatment records is gated behind [YpsoBooleanKey.YpsoSyncHistoryToAaps] — see
     * that key's comment for where that switch now stands.
     */
    fun loadHistory(): Boolean = awaitResult("loadHistory", false) {
        val count = preferences.get(YpsoIntKey.YpsoHistoryReadCount)

        // Indices 2-13 have never been read by anything (see probeUnknownSettings). Attached to this
        // button rather than the status poll because it is a diagnostic, and 12 extra round trips is
        // ~2.5s that should not land on every connection.
        pumpManager.probeUnknownSettings().forEach { (index, value) ->
            aapsLogger.info(LTag.PUMP, "[setting probe] index $index = ${value ?: "unreadable"}")
        }

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

    /**
     * `SALINE_TEST_PROCEDURE.md` **Phase 8a** — the extended-bolus probe. A bench instrument.
     *
     * ⚠ **This moves fluid.** Saline, off-body, cannula to air.
     *
     * Takes **no parameters**, and that is the safety property: every amount and duration it will
     * ever try is a constant in `YpsoPumpManagerImpl`, so there is no call path that can ask it for
     * a different dose.
     *
     * It runs a positive control (a 0.1 U standard bolus, to prove the path works in the pump's
     * current state) and then sweeps extended-bolus durations, stopping at the first the pump
     * accepts and cancelling it. **A rejected write delivers nothing**, so the sweep costs saline
     * only for the duration that is accepted — at most 1.1 U in total.
     *
     * **No treatment record is written.** The history sync will import whatever the pump logged on
     * the next status read, which is Phase 8c's question — and watching the real mechanism answer
     * it is worth more than hand-coding a record and then checking the record.
     */
    fun probeExtendedBolus(): String = awaitResult("probeExtendedBolus", "The probe could not run") {
        pumpManager.probeExtendedBolus()
    }

    // ==================== WRITES ====================

    /**
     * Sets the pump's clock from the phone's.
     *
     * It was deliberately the **first** write this driver ever made: no insulin can move, so a wrong
     * wire format cost a refused GATT write rather than a dose. It remains the cheapest regression
     * test for the write path. The result is whatever reading the clock back said, never what the
     * write callback said — see `YpsoPumpManagerImpl.syncDateTime`.
     *
     * Also called unattended now, on a timezone or DST change and whenever the pump's clock has
     * drifted past 10 s (§8.6), which is safe for the same reason: it refuses unless it can read the
     * clock first.
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
            val plan = BolusChunkPlan.plan(insulin, BolusSpeed.from(preferences.get(YpsoStringKey.YpsoBolusSpeed)))

            val result = if (plan.isChunked) deliverChunked(detailedBolusInfo, plan) else deliverWhole(detailedBolusInfo, insulin)
            aapsLogger.info(
                LTag.PUMP,
                "deliverBolus: asked ${insulin}U, pump delivered ${result.deliveredUnits}U, " +
                    "success=${result.complete}" + (result.errorMessage?.let { ", $it" } ?: "")
            )

            if (result.deliveredUnits > 0.0) {
                ypsoPump.lastBolusAmount = result.deliveredUnits
                ypsoPump.lastBolusTime = dateUtil.now()
            }

            rxBus.send(EventYpsoPumpDataChanged())
            result
        }
    }

    /** One command to the pump — the path every hardware phase from 1 to 8 ran. */
    private suspend fun deliverWhole(detailedBolusInfo: DetailedBolusInfo, insulin: Double): YpsoPumpManagerImpl.ConfirmedBolus {
        // ⚠ Recorded BEFORE the pump is told, at the amount requested. See [openPendingBolus].
        val temporaryId = dateUtil.now()
        openPendingBolus(detailedBolusInfo, insulin, temporaryId)
        val result = pumpManager.deliverBolusConfirmed(insulin)
        settlePendingBolus(detailedBolusInfo, result, temporaryId)
        return result
    }

    /**
     * A run of ordinary standard boluses on a timer, for comfort on a large dose (§8.6).
     *
     * **The provisional record is opened per chunk rather than once for the whole amount**, and the
     * distinction is the whole reason this is not simply [deliverWhole] in a loop. [openPendingBolus]
     * exists because *the pump owns the bolus once it has been told*; here the pump has been told
     * about one chunk. Insulin in chunks that were never commanded will never be delivered by
     * anything, so recording 10 U when the phone died after 2 U would not be §0's "err high on an
     * unknown outcome" — that outcome is known. One provisional per chunk covers exactly the window
     * §4g exists for, which is the chunk in flight.
     *
     * Each provisional is then settled to that chunk's own pump sequence, giving N records: the same
     * N the history sync produces from the same N log entries, resolving to one set rather than two
     * (§8.5). A single synthetic record filed under one chunk's sequence would be rewritten down to
     * that chunk's amount by the first sync to reach it.
     */
    private suspend fun deliverChunked(
        detailedBolusInfo: DetailedBolusInfo,
        plan: BolusChunkPlan
    ): YpsoPumpManagerImpl.ConfirmedBolus {
        var pendingTemporaryId: Long? = null
        return pumpManager.deliverChunkedBolusConfirmed(
            plan = plan,
            onChunkStarting = { chunkUnits ->
                val temporaryId = dateUtil.now()
                pendingTemporaryId = temporaryId
                openPendingBolus(detailedBolusInfo, chunkUnits, temporaryId)
            },
            onChunkSettled = { chunkResult ->
                pendingTemporaryId?.let { settlePendingBolus(detailedBolusInfo, chunkResult, it) }
                pendingTemporaryId = null
            }
        )
    }

    /**
     * Records the bolus **before the pump is told about it**, at the amount requested.
     *
     * ⚠ This looks like the one thing this driver refuses to do — claiming something the pump has not
     * confirmed — and the distinction is worth being precise about, because the two are different
     * claims whose safe directions are opposite:
     *
     * - **Reporting success** is a statement about the pump. Over-claiming tells the user insulin
     *   went in when it did not, so it must never exceed what has been confirmed. That rule is
     *   unchanged: `PumpEnactResult.success` still comes only from the pump's own log.
     * - **Recording IOB** is a safety estimate. Over-estimating makes AAPS dose *less*.
     *   Under-estimating causes stacking, which is the hypo direction. So it must err high.
     *
     * The reason it has to happen first is that **this pump owns the bolus once it has been told**.
     * Delivery continues whether or not the phone is still there. Everything between the write and
     * the confirmation — a dropped connection, a killed app, a flat phone — is a window in which
     * insulin goes in; and until now AAPS held no record at all across that window, on every single
     * bolus.
     *
     * `PumpSync`'s contract is explicit that this is the intended failure mode: *"If
     * syncBolusWithTempId is not called afterwards record remains valid and is calculated towards
     * iob"*. An unreconciled record survives as an over-estimate rather than vanishing.
     *
     * The temporary id is the phone's clock at the moment of sending, which is unique per bolus and
     * is what [settlePendingBolus] matches on.
     */
    private fun openPendingBolus(detailedBolusInfo: DetailedBolusInfo, requested: Double, temporaryId: Long) {
        val created = pumpSync.addBolusWithTempId(
            timestamp = dateUtil.now(),
            amount = requested,
            temporaryId = temporaryId,
            type = detailedBolusInfo.bolusType,
            pumpType = PumpType.YPSOPUMP,
            pumpSerial = pumpSerial()
        )
        aapsLogger.info(
            LTag.PUMP,
            "Provisionally recorded ${requested}U under temporaryId=$temporaryId before sending, so the bolus " +
                "counts towards IOB even if this connection does not survive it. new=$created"
        )
    }

    /**
     * Replaces the provisional record with what the pump actually did.
     *
     * Updates the amount to the delivered figure and promotes the identity from the temporary id to
     * the pump's own **sequence number** — the same id `readHistory` reports, so this bolus and a
     * later history sync of it resolve to one record rather than two.
     *
     * **A bolus the pump never logged is deliberately left alone.** The provisional record stands, at
     * the requested amount, and AAPS goes on counting it. That is the honest position: the command
     * went out, the pump owns it, and this driver could not find out how much went in. Deleting the
     * record would assert that nothing was delivered, which is the one thing we do not know.
     *
     * The timestamp stays the **phone's**, never the pump's entry time. Using the pump's put a bolus
     * 12 hours into the future and so out of every window AAPS cares about — the treatment existed,
     * showed nowhere and contributed no IOB, while the dialog reported success because the *amount*
     * had been confirmed (`PORTING_NOTES` §5). Dedupe does not need it: matching is on `pumpId`.
     */
    private fun settlePendingBolus(
        detailedBolusInfo: DetailedBolusInfo,
        result: YpsoPumpManagerImpl.ConfirmedBolus,
        temporaryId: Long
    ) {
        val sequence = result.pumpSequence
        if (sequence == null) {
            aapsLogger.warn(
                LTag.PUMP,
                "The pump's log did not confirm this bolus, so the provisional ${result.requestedUnits}U record under " +
                    "temporaryId=$temporaryId stands and keeps counting towards IOB. It cannot be corrected without " +
                    "knowing what the pump did — check the pump."
            )
            return
        }

        val synced = pumpSync.syncBolusWithTempId(
            timestamp = dateUtil.now(),
            amount = result.deliveredUnits,
            temporaryId = temporaryId,
            type = detailedBolusInfo.bolusType,
            pumpId = sequence,
            pumpType = PumpType.YPSOPUMP,
            pumpSerial = pumpSerial()
        )

        if (!synced) {
            // The provisional record could not be found to update — an app restart between sending
            // and confirming would do it. Fall back to recording under the pump's id directly, or
            // the confirmed amount would be lost entirely.
            val fallback = pumpSync.syncBolusWithPumpId(
                timestamp = dateUtil.now(),
                amount = result.deliveredUnits,
                type = detailedBolusInfo.bolusType,
                pumpId = sequence,
                pumpType = PumpType.YPSOPUMP,
                pumpSerial = pumpSerial()
            )
            aapsLogger.warn(
                LTag.PUMP,
                "No provisional record for temporaryId=$temporaryId to settle; recorded ${result.deliveredUnits}U " +
                    "under pumpId=$sequence instead, new=$fallback"
            )
            return
        }

        aapsLogger.info(
            LTag.PUMP,
            "Settled temporaryId=$temporaryId to ${result.deliveredUnits}U under pumpId=$sequence (the pump's own sequence)"
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

    /**
     * Sets a temporary basal as a percentage and records it.
     *
     * Recorded under the type-9 history entry's sequence number, the same identity history sync
     * uses — see [deliverBolus] for why that matters. A temporary basal the pump confirmed but did
     * not log cannot be recorded, and says so; the TBR is still running either way, which is what
     * `confirmed` reports.
     */
    fun setTempBasalPercent(percent: Int, durationMinutes: Int, tbrType: PumpSync.TemporaryBasalType): YpsoPumpManagerImpl.ConfirmedTbr {
        val failed = YpsoPumpManagerImpl.ConfirmedTbr(
            percentage = percent, durationMinutes = durationMinutes,
            pumpSequence = null, timestampMillis = null, confirmed = false,
            errorMessage = rh.gs(R.string.ypso_tbr_failed)
        )

        return awaitResult("setTempBasalPercent", failed) {
            val result = pumpManager.setTbrConfirmed(percent, durationMinutes)
            aapsLogger.info(
                LTag.PUMP,
                "setTempBasalPercent: asked $percent% for ${durationMinutes}min, confirmed=${result.confirmed}" +
                    (result.errorMessage?.let { ", $it" } ?: "")
            )

            if (result.confirmed) {
                val sequence = result.pumpSequence
                if (sequence == null) {
                    aapsLogger.warn(LTag.PUMP, "TBR is running but the pump logged no type-9 entry, so it cannot be recorded")
                } else {
                    val synced = pumpSync.syncTemporaryBasalWithPumpId(
                        // The phone's clock, for the reason in recordBolus.
                        timestamp = dateUtil.now(),
                        rate = result.percentage.toDouble(),
                        duration = result.durationMinutes * 60L * 1000L,
                        isAbsolute = false,
                        type = tbrType,
                        pumpId = sequence,
                        pumpType = PumpType.YPSOPUMP,
                        pumpSerial = pumpSerial()
                    )
                    aapsLogger.info(LTag.PUMP, "Recorded TBR ${result.percentage}% under pumpId=$sequence, new=$synced")
                }
            }
            rxBus.send(EventYpsoPumpDataChanged())
            result
        }
    }

    /** Cancels the running temporary basal and closes the AAPS record. */
    fun cancelTempBasal(): YpsoPumpManagerImpl.ConfirmedTbr {
        val failed = YpsoPumpManagerImpl.ConfirmedTbr(
            percentage = 100, durationMinutes = 0,
            pumpSequence = null, timestampMillis = null, confirmed = false,
            errorMessage = rh.gs(R.string.ypso_tbr_cancel_failed)
        )

        // ⚠ The "temporary basal" AAPS believes is running may be this driver's own suspend record,
        // and closing that one would tell AAPS basal had resumed on a pump that is stopped.
        // Observed on hardware 2026-08-01: with the pump halted by an occlusion, a cancel closed the
        // suspend record and AAPS went back to counting basal IOB for 52 seconds until the next poll
        // re-opened it. On idle polling that window is a full poll interval — see PORTING_NOTES §8.6.
        val suspendRecord = openSuspendRecord()

        // The id AAPS holds for the temporary basal it believes is running. The pump rewrites one
        // entry in place rather than appending an end record, so this *is* the entry to look for
        // after the cancel — and nothing about the log's ordering can be used to find it instead.
        //
        // Not when it is the suspend record, though: that record's pumpId is a synthetic timestamp
        // this driver invented, so looking for it in the pump's log is looking for something that
        // was never there. The same log showed the driver hunting `seq=1785561083912` — a
        // millisecond clock reading, in a log of pump sequence numbers.
        val expectedSequence = if (suspendRecord == null) pumpSync.expectedPumpState().temporaryBasal?.pumpId else null

        return awaitResult("cancelTempBasal", failed) {
            val result = pumpManager.cancelTbrConfirmed(expectedSequence)
            aapsLogger.info(
                LTag.PUMP,
                "cancelTempBasal: confirmed=${result.confirmed}" + (result.errorMessage?.let { ", $it" } ?: "")
            )

            if (result.confirmed && suspendRecord != null) {
                // The pump-side cancel still ran — asking a stopped pump to drop a temporary basal
                // is harmless, and there may genuinely have been one programmed. What must not
                // happen is closing the AAPS record, because the record open here is not a
                // temporary basal at all: it is this driver saying "the pump is delivering
                // nothing". Only the pump reporting STARTED again may close it, which
                // syncSuspendedState does on the next poll.
                //
                // Note the asymmetry with the branch below, which argues that leaving a record open
                // is the outcome that harms. That is true of a *real* temporary basal, where open
                // means AAPS assumes elevated delivery that has stopped. It is exactly inverted
                // here: this record open means AAPS assumes no basal, which is the truth, and
                // closing it invents basal that is not being delivered. Same action, opposite sign,
                // and telling the two apart is the whole reason the record carries a distinctive
                // type and duration.
                aapsLogger.warn(
                    LTag.PUMP,
                    "TBR cancel: the open record is this driver's own 0% suspend record (pump is not delivering), " +
                        "so it is left alone — closing it would tell AAPS basal had resumed on a stopped pump"
                )
            } else if (result.confirmed) {
                // Byte 10 has already confirmed the pump is not running a temporary basal, so the
                // AAPS record must be closed whatever the log says. Leaving it open is the one
                // outcome that actually harms: AAPS would keep assuming elevated basal delivery
                // that has stopped, and over-count IOB for the rest of the original duration.
                //
                // The pump's sequence number is preferred as the id, so this stop and a later
                // history sync of the same event agree. When the log gives us nothing, the
                // timestamp stands in — a possible dedupe collision is a far smaller problem than a
                // temporary basal AAPS thinks is still running. That trade was priced while the
                // sync switch was off; it is on now, so the collision is reachable rather than
                // hypothetical, and this wants rework anyway since the entry is known to mutate.
                val sequence = result.pumpSequence
                val endPumpId = sequence ?: dateUtil.now()
                // Only a mismatch worth warning about: AAPS held an id for a running temporary basal
                // and the pump's log could not be made to agree. A cancel with nothing running finds
                // no entry because there is none, which is not a fault and should not read like one.
                if (sequence == null && expectedSequence != null) {
                    aapsLogger.warn(
                        LTag.PUMP,
                        "TBR is stopped but the entry AAPS expected (seq=$expectedSequence) was not in the log; " +
                            "closing the AAPS record under a timestamp id instead of leaving it open"
                    )
                }
                val synced = pumpSync.syncStopTemporaryBasalWithPumpId(
                    timestamp = dateUtil.now(),
                    endPumpId = endPumpId,
                    pumpType = PumpType.YPSOPUMP,
                    pumpSerial = pumpSerial()
                )
                aapsLogger.info(LTag.PUMP, "Recorded TBR stop under endPumpId=$endPumpId, new=$synced")
            }
            rxBus.send(EventYpsoPumpDataChanged())
            result
        }
    }

    /**
     * Overwrites the pump's active basal program with 24 hourly rates.
     *
     * Rates must already be rounded to the pump's step — [app.aaps.pump.ypso.YpsoPlugin] owns that,
     * because the step table lives in AAPS's `PumpType`.
     *
     * On success the cached rates are replaced so `baseBasalRate` and `isThisProfileSet` reflect what
     * the pump now holds rather than what it held before.
     */
    fun writeBasalProfile(hourlyRates: List<Double>): Boolean = awaitResult("writeBasalProfile", false) {
        val profile = BasalProfile(
            profileId = ypsoPump.activeProfileId.takeIf { it >= 0 } ?: 0,
            name = "AAPS",
            blocks = hourlyRates.map { BasalBlock(durationMinutes = 60, basalRate = it) }
        )
        val written = pumpManager.writeBasalProfile(profile)
        aapsLogger.info(LTag.PUMP, "writeBasalProfile: confirmed=$written")

        if (written) {
            ypsoPump.basalProfileRates = hourlyRates
        } else {
            // What the pump holds is no longer known to match anything cached. Drop the cache so
            // baseBasalRate reports 0.0 (honestly "not known") instead of a stale profile, and so
            // isThisProfileSet cannot claim agreement.
            ypsoPump.basalProfileRates = emptyList()
        }
        rxBus.send(EventYpsoPumpDataChanged())
        written
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
                aapsLogger.info(
                    LTag.PUMP,
                    "No PumpSync mapping for ${event::class.simpleName} seq=${event.position}, skipped — " +
                        "nothing reaches treatments from it"
                )
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
                    aapsLogger.info(
                        LTag.PUMP,
                        "Prime of ${primed}U at seq=${event.position} below tube-change threshold, not recorded"
                    )
                }
            }

            else                                    ->
                aapsLogger.info(
                    LTag.PUMP,
                    "Unmapped pump event type ${event.eventType} at seq=${event.position}, skipped"
                )
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
        if (!enabled) {
            aapsLogger.info(LTag.PUMP, "Therapy event $type at seq=${event.position} skipped — its preference is off")
            return
        }
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

        /**
         * Length of the 0% temporary basal that stands for a stopped pump — see [syncSuspendedState].
         *
         * Three times the 20-minute idle poll interval (§8.2): long enough that it cannot lapse
         * between polls while the pump is still stopped, short enough that one left behind by a
         * crash expires in an hour rather than three days. It is re-asserted on every poll, so the
         * length is a backstop and not the mechanism.
         *
         * Medtrum uses 4800 minutes for the same record. That suits a driver polling
         * near-continuously and was wrong to copy wholesale for one that polls three times an hour.
         */
        private const val SUSPEND_TBR_MINUTES = 60L

        /**
         * Renew the suspend record once it has less than this left, rather than waiting for it to
         * expire.
         *
         * **Must comfortably exceed AAPS's status-poll interval**, which is ~20 minutes and is not
         * this driver's to choose — `KeepAliveWorker.STATUS_UPDATE_FREQUENCY` is 15 minutes and the
         * worker evaluating it ticks every 5. Thirty leaves a whole poll of margin, and renews on
         * every second poll rather than every one, so the record churn stays modest.
         *
         * It also has to **not divide [SUSPEND_TBR_MINUTES] evenly against the poll interval**,
         * which is the trap the first version fell into: 60 is exactly three 20-minute polls, so
         * expiry was phase-locked to the poll schedule and a poll landed within seconds of it every
         * single cycle.
         */
        private const val SUSPEND_TBR_RENEW_BELOW_MINUTES = 30L

        /**
         * Drift above this is corrected on the next status read. The pump's clock reads to one
         * second and a BLE round trip costs a fraction of one, so the measurement is good to a
         * second or two; ten is comfortably clear of that noise and tight enough that a treatment
         * timestamp is never meaningfully wrong. Medtronic uses twenty.
         */
        private const val CLOCK_RESYNC_THRESHOLD_SECONDS = 10L

        /** Beyond this the difference is not drift, and a human should look before anything is written. */
        private const val CLOCK_IMPLAUSIBLE_HOURS = 24L



    }
}
