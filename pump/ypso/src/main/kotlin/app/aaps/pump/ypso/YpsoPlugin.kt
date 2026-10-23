package app.aaps.pump.ypso

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.pump.defs.TimeChangeType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.pump.BlePreCheck
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpPluginBase
import app.aaps.core.interfaces.pump.PumpProfile
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.di.PumpDriver
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.defs.determineCorrectBasalSize
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.interfaces.rx.events.EventAppExit
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.pump.ypso.keys.YpsoBooleanKey
import app.aaps.pump.ypso.keys.YpsoDoubleNonKey
import app.aaps.pump.ypso.keys.YpsoIntKey
import app.aaps.pump.ypso.keys.YpsoIntNonKey
import app.aaps.pump.ypso.keys.YpsoLongNonKey
import app.aaps.pump.ypso.keys.YpsoStringKey
import app.aaps.pump.ypso.keys.YpsoStringNonKey
import app.aaps.pump.ypso.compose.YpsoComposeContent
import app.aaps.pump.ypso.compose.icons.IcPluginYpso
import app.aaps.pump.ypso.services.YpsoService
import app.aaps.pump.ypso.ui.YpsoLoadHistoryCommand
import app.aaps.pump.ypso.ui.YpsoSyncTimeCommand
import com.ypsopump.sdk.BolusSpeed
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.reactivex.rxjava3.kotlin.plusAssign
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.IntKey as MetroIntKey
import kotlin.math.abs
import app.aaps.core.ui.R as CoreUiR

/**
 * AAPS pump driver for the YpsoPump, running against an unlocked plaintext research pump.
 *
 * Shaped on `MedtrumPlugin`: the plugin binds [YpsoService] and forwards to it; the service owns
 * the connection.
 *
 * **Four things reach the pump**, each verified on saline behind its own hardware test: the clock via
 * [YpsoSyncTimeCommand], a **standard bolus** via [deliverTreatment], a **temporary basal** via
 * [setTempBasalPercent] / [setTempBasalAbsolute] / [cancelTempBasal], and the **24-slot basal
 * profile** via [setNewBasalProfile]. Only the **extended and combined bolus** still return a failed
 * [PumpEnactResult] without contacting the pump; they are overridden individually rather than left to
 * a default, so "does nothing" is visible at the call site and enabling one has to be deliberate.
 *
 * Nothing here reports success on the strength of a write. The bolus reports the amount the **pump**
 * says it delivered, and the temporary basal reports what byte 10 of the pump's status reads back —
 * in both cases `success` means the pump confirmed the effect, not that the command was accepted.
 */
// Ordering slot 1140, between Equil (1130) and Carelevo (1190). Registration is the annotation
// itself on 4.0.0 - there is no central plugin list to add a line to any more.
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@PumpDriver
@MetroIntKey(1140)
@SingleIn(AppScope::class)
@Inject
class YpsoPlugin(
    aapsLogger: AAPSLogger,
    override val rh: ResourceHelper,
    preferences: Preferences,
    commandQueue: CommandQueue,
    private val aapsSchedulers: AapsSchedulers,
    private val rxBus: RxBus,
    private val context: Context,
    private val fabricPrivacy: FabricPrivacy,
    private val ypsoPump: YpsoPump,
    private val protectionCheck: ProtectionCheck,
    private val blePreCheck: BlePreCheck,
    notificationManager: NotificationManager,
    private val pumpEnactResultProvider: () -> PumpEnactResult
) : PumpPluginBase(
    pluginDescription = PluginDescription()
        .mainType(PluginType.PUMP)
        .icon(IcPluginYpso)
        .pluginName(TextRef.AndroidRes(R.string.ypso_pump))
        .description(TextRef.AndroidRes(R.string.ypso_pump_description))
        .composeContent { _ ->
            YpsoComposeContent(
                pluginName = rh.gs(R.string.ypso_pump),
                protectionCheck = protectionCheck,
                blePreCheck = blePreCheck
            )
        },
    ownPreferences = YpsoStringKey.entries + YpsoBooleanKey.entries + YpsoIntKey.entries +
        YpsoStringNonKey.entries + YpsoIntNonKey.entries +
        YpsoLongNonKey.entries + YpsoDoubleNonKey.entries,
    aapsLogger, rh, preferences, commandQueue, notificationManager
), Pump {

    private val disposable = CompositeDisposable()
    private var ypsoService: YpsoService? = null

    /**
     * One lock for every command that writes to the pump.
     *
     * These five were `@Synchronized` before, which 4.0.0 cannot keep: they are all suspend
     * functions now, and a monitor cannot be held across a suspension point. A [Mutex] is the
     * equivalent, and it is deliberately **one** mutex rather than one each - what the instance
     * lock bought was that no two of them run at once, and two concurrent writes to an insulin
     * pump is the thing worth preventing.
     *
     * Each public entry point takes the lock and calls an `apply...` function that does not.
     * `@Synchronized` was reentrant and a `Mutex` is not, so the old shape - where
     * [setTempBasalAbsolute] finished by calling [setTempBasalPercent] - would now deadlock on
     * every absolute temporary basal, which is the path the loop actually uses.
     */
    private val pumpWriteMutex = Mutex()

    override suspend fun onStart() {
        super.onStart()
        aapsLogger.debug(LTag.PUMP, "YpsoPlugin onStart()")
        context.bindService(Intent(context, YpsoService::class.java), connection, Context.BIND_AUTO_CREATE)
        // RxBus has no toObservable on 4.0.0. UNDISPATCHED because the bus does not replay, so a
        // collector that is merely scheduled could miss an exit sent before it starts.
        rxBus.toFlow(EventAppExit::class)
            .collectResilient(pluginScope, aapsLogger, LTag.PUMP, start = CoroutineStart.UNDISPATCHED) {
                context.unbindService(connection)
            }
    }

    override suspend fun onStop() {
        aapsLogger.debug(LTag.PUMP, "YpsoPlugin onStop()")
        context.unbindService(connection)
        disposable.clear()
        super.onStop()
    }

    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName) {
            aapsLogger.debug(LTag.PUMP, "YpsoService disconnected")
            ypsoService = null
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            aapsLogger.debug(LTag.PUMP, "YpsoService connected")
            ypsoService = (service as YpsoService.LocalBinder).serviceInstance
        }
    }

    fun getService(): YpsoService? = ypsoService

    // ==================== STATE ====================

    /** Initialised once a pump has been paired and has answered at least once. */
    override fun isInitialized(): Boolean = ypsoPump.isPaired && ypsoPump.lastConnection > 0

    override fun isSuspended(): Boolean = ypsoPump.lastConnection > 0 && !ypsoPump.isDelivering
    override fun isBusy(): Boolean = false
    override fun isConnected(): Boolean = ypsoService?.isConnected == true
    override fun isConnecting(): Boolean = ypsoService?.isConnecting == true
    override fun isHandshakeInProgress(): Boolean = false

    override fun connect(reason: String) {
        aapsLogger.debug(LTag.PUMP, "connect - reason: $reason")
        ypsoService?.connect(reason)
    }

    override fun disconnect(reason: String) {
        aapsLogger.debug(LTag.PUMP, "disconnect - reason: $reason")
        ypsoService?.disconnect(reason)
    }

    override fun stopConnecting() {
        ypsoService?.stopConnecting()
    }

    override suspend fun getPumpStatus(reason: String) {
        aapsLogger.debug(LTag.PUMP, "getPumpStatus - reason: $reason")
        if (ypsoService?.readPumpStatus() != true) {
            aapsLogger.error(LTag.PUMP, "getPumpStatus failed")
        }
    }

    /**
     * 4.0.0 asks for these as flows rather than plain getters.
     *
     * They are derived from the preferences the values are already stored in, not from a cached
     * copy refreshed on an event. Every one of these is written through [YpsoPump], which is
     * preference-backed, so observing the preference means the flow cannot go stale no matter which
     * code path does the writing - a cache refreshed on [EventYpsoPumpDataChanged] would silently
     * lag any path that forgot to send it, and the loop reads these.
     *
     * The units are the pump's own ([PumpInsulin.cU] / [PumpRate.cU]). This pump is U100, so they
     * are the same numbers the driver has always used.
     */
    override val lastDataTime: StateFlow<Long> = preferences.observe(YpsoLongNonKey.LastConnection)

    override val lastBolusTime: StateFlow<Long?> =
        preferences.observe(YpsoLongNonKey.LastBolusTime)
            .map { it.takeIf { time -> time > 0 } }
            .stateIn(pluginScope, SharingStarted.Eagerly, ypsoPump.lastBolusTime.takeIf { it > 0 })

    override val lastBolusAmount: StateFlow<PumpInsulin?> =
        preferences.observe(YpsoDoubleNonKey.LastBolusAmount)
            .map { amount -> amount.takeIf { it > 0.0 }?.let { PumpInsulin(it) } }
            .stateIn(
                pluginScope, SharingStarted.Eagerly,
                ypsoPump.lastBolusAmount.takeIf { it > 0.0 }?.let { PumpInsulin(it) }
            )

    override val reservoirLevel: StateFlow<PumpInsulin> =
        preferences.observe(YpsoDoubleNonKey.Reservoir)
            .map { PumpInsulin(it) }
            .stateIn(pluginScope, SharingStarted.Eagerly, PumpInsulin(ypsoPump.reservoir))

    override val batteryLevel: StateFlow<Int?> =
        preferences.observe(YpsoIntNonKey.BatteryLevel)
            .map { it.takeIf { level -> level > 0 } }
            .stateIn(pluginScope, SharingStarted.Eagerly, ypsoPump.batteryPercent.takeIf { it > 0 })

    override val baseBasalRate: PumpRate get() = PumpRate(ypsoPump.baseBasalRate)

    override fun manufacturer(): ManufacturerType = ManufacturerType.Ypsomed
    override fun model(): PumpType = PumpType.YPSOPUMP
    override fun serialNumber(): String = ypsoPump.serialNumber.ifBlank { ypsoPump.pumpMac }
    /**
     * What AAPS is told this driver can do — **narrower than `PumpType.YPSOPUMP` declares**.
     *
     * A capability is a promise AAPS acts on, not a note about the hardware. `YpsomedCapabilities`
     * in `core/data` claims `ExtendedBolus`, `TDD` and `ManualTDDLoad`; all three reach a method
     * here that returns a failed [PumpEnactResult] without contacting the pump. Left as declared,
     * AAPS offers an extended-bolus button, a Treatments tab for extended boluses and a TDD reload
     * that can only ever fail — the UI promises what the driver refuses.
     *
     * Cleared here rather than in `PumpCapability` for two reasons. `core/data` is shared upstream
     * code and every line changed in it is future rebase conflict surface (§9); and the flags are
     * only ever read back through `pump.pumpDescription`, which is this plugin's to answer. Every
     * consumer — `LoopPlugin`, `ActionsFragment`, `TreatmentsActivity`, `TDDStatsActivity` — goes
     * through this property. `VirtualPumpPlugin` adjusts its own description the same way.
     *
     * **Extended and combined bolus are refusals, not omissions** (§8.4, §0 item 2). The combined
     * ("multiwave") bolus has no capability flag of its own — AAPS reaches it through the extended
     * one — so clearing `isExtendedBolusCapable` withdraws both.
     *
     * **TDD is different: the data exists and nobody has wired it up.** The pump logs
     * `23 Daily Total Insulin` with the total in `v1` as centi-units, and
     * `PumpSync.createOrUpdateTotalDailyDose` takes that shape. Until [loadTDDs] does something,
     * claiming the capability is a promise; when it does, delete these two lines rather than
     * editing shared code.
     *
     * Built once. `fillFor` allocates and re-derives on every call, and this is read from the UI.
     */
    override val pumpDescription: PumpDescription = PumpDescription().fillFor(PumpType.YPSOPUMP).apply {
        isExtendedBolusCapable = false
        supportsTDDs = false
        needsManualTDDLoad = false
    }
    override val isFakingTempsByExtendedBoluses: Boolean = false

    /**
     * False, and it has to be: this pump cannot handle a DST change by itself.
     *
     * It has **no concept of a time zone**. `syncTime` writes local hour, minute and second, and the
     * pump counts local wall-clock seconds from there (`PORTING_NOTES` §5). So when the phone's
     * offset changes, the pump's clock is simply an hour wrong until something rewrites it, and every
     * history timestamp read in the meantime is an hour out — which is the class of error that
     * already produced treatments 12 hours in the future once.
     *
     * Returning false keeps AAPS's own DST safeguard in place; [timezoneOrDSTChanged] then does the
     * repair.
     */
    override fun canHandleDST(): Boolean = false

    /**
     * Rewrites the pump's clock after the phone's time, zone or DST changed.
     *
     * The gap this closes: nothing else ever corrects it. The clock is written on demand from the
     * YPSO tab and at no other time, so before this a DST change left the pump an hour out until
     * somebody noticed and pressed the button — with every bolus and temporary basal logged at the
     * wrong instant in between.
     *
     * Queued rather than called directly, so it shares the normal connect/disconnect cycle instead of
     * opening a second BLE session from a broadcast receiver. The write is safe to fire unattended:
     * it refuses unless it can read the clock first, and reports success only from the read-back
     * (§4e). If the pump is unreachable the command fails and the clock stays wrong, which is what a
     * failure should look like.
     */
    override suspend fun timezoneOrDSTChanged(timeChangeType: TimeChangeType) {
        aapsLogger.info(LTag.PUMP, "Phone time changed ($timeChangeType); queueing a pump clock sync")
        if (!isInitialized()) {
            aapsLogger.warn(LTag.PUMP, "Not initialised, so the pump clock cannot be corrected yet")
            return
        }
        // The command queue returns its result directly on 4.0.0, so there is no Callback to
        // wait on - this function is itself suspend.
        val result = commandQueue.customCommand(YpsoSyncTimeCommand())
        if (result.success) {
            aapsLogger.info(LTag.PUMP, "Pump clock corrected after $timeChangeType")
        } else {
            aapsLogger.error(LTag.PUMP, "Pump clock NOT corrected after $timeChangeType - it is now wrong by the offset")
            notificationManager.post(
                NotificationId.TIME_OR_TIMEZONE_CHANGE,
                rh.gs(R.string.ypso_clock_dst_failed),
                NotificationLevel.URGENT
            )
        }
    }

    /**
     * ⚠ The last line is a **safety statement, not a status field**, and it is deliberately not
     * derived from anything.
     *
     * It has now been wrong twice in the same way, which is the thing worth recording. It read
     * "profile write UNTESTED" until Phase 4 passed on 2026-07-31, then "Saline test build — not for
     * use with insulin" until this build was made for a phone that will carry insulin. Both were
     * true when written; both were **verdicts about a moment** in a string nobody re-reads, and a
     * safety line that is visibly false is worse than none because it teaches the reader to skip it.
     *
     * What is here now is a **fact about the driver's history**, not a verdict about its use.
     * "Verified on saline only" stays true no matter what anybody decides to put in the cartridge —
     * the verification is what it is — and it says the thing a reader actually needs, which is that
     * nothing here has ever been checked against a person. "Research pump" says the other one: this
     * build cannot talk to a standard YpsoPump at all (§0), so seeing this line against the wrong
     * pump means something is badly wrong.
     */
    override fun pumpSpecificShortStatus(veryShort: Boolean): String = buildString {
        append("Firmware: ${ypsoPump.firmwareVersion.ifBlank { "?" }}")
        if (!veryShort) {
            append("\nMode: ${ypsoPump.deliveryModeName.ifBlank { "?" }}")
            append("\nResearch pump — verified on saline only")
        }
    }

    /**
     * Runs the driver's own commands inside a queued command, so they share the normal
     * connect/disconnect cycle instead of opening a second BLE session from the UI thread.
     */
    override fun executeCustomCommand(customCommand: CustomCommand): PumpEnactResult? =
        // The Pump interface keeps this one non-suspend, but the service's commands are suspend on
        // 4.0.0. The command queue calls this on its own worker thread and waits for the result
        // either way, so blocking that thread here is the behaviour the driver already had rather
        // than new behaviour. Same bridge as `PumpSyncStorage` in :pump:common.
        runBlocking {
            when (customCommand) {
                is YpsoLoadHistoryCommand -> pumpEnactResultProvider().success(ypsoService?.loadHistory() == true)

                // The clock write. `confirmed` is what the pump's own clock read back as, not what
                // the GATT write returned, so reporting it as enacted is a claim about the pump
                // rather than about the phone.
                is YpsoSyncTimeCommand    -> {
                    val confirmed = ypsoService?.syncPumpTime() == true
                    pumpEnactResultProvider()
                        .success(confirmed)
                        .enacted(confirmed)
                        .comment(rh.gs(if (confirmed) R.string.ypso_clock_set_ok else R.string.ypso_clock_set_failed))
                }

                else                      -> null
            }
        }

    // ==================== NO DELIVERY: NOTHING BELOW TOUCHES THE PUMP ====================

    /**
     * Whether the pump's basal program already matches [profile].
     *
     * Compared against the **rounded** rates, not the profile's own: the pump's step varies with the
     * rate, so a profile asking for 1.23 U/h can only ever be stored as 1.22 or 1.24. Comparing
     * against the unrounded value would report a mismatch on a correctly written profile for ever,
     * and AAPS would offer to rewrite it on every loop iteration.
     *
     * Returns false whenever the pump's rates are not known — the honest answer when there is
     * nothing to compare, and it makes AAPS offer the write rather than assume agreement.
     */
    override fun isThisProfileSet(profile: PumpProfile): Boolean {
        val pumpRates = ypsoPump.basalProfileRates
        if (pumpRates.size != HOURS_IN_DAY) return false

        val wanted = hourlyRatesFor(profile)
        val mismatch = (0 until HOURS_IN_DAY).firstOrNull { abs(pumpRates[it] - wanted[it]) > BASAL_RATE_EPSILON }
        if (mismatch != null) {
            aapsLogger.debug(
                LTag.PUMP,
                "isThisProfileSet: hour $mismatch differs — pump ${pumpRates[mismatch]} vs wanted ${wanted[mismatch]}"
            )
        }
        return mismatch == null
    }

    /**
     * Writes the AAPS profile into the pump's active basal program.
     *
     * **Destructive**, and only reported as done once all 24 slots have been read back and matched —
     * see `YpsoPumpManagerImpl.writeBasalProfile`, which also logs the program it replaced, because
     * the pump has no undo and that log line is the only copy.
     */
    override suspend fun setNewBasalProfile(profile: PumpProfile): PumpEnactResult =
        pumpWriteMutex.withLock { applyNewBasalProfile(profile) }

    private suspend fun applyNewBasalProfile(profile: PumpProfile): PumpEnactResult {
        val result = pumpEnactResultProvider()
        val service = ypsoService
        if (!isInitialized() || service == null) {
            return result.success(false).enacted(false).comment(rh.gs(R.string.ypso_bolus_not_connected))
        }

        val wanted = hourlyRatesFor(profile)
        aapsLogger.info(LTag.PUMP, "setNewBasalProfile: writing ${wanted.map { "%.2f".format(it) }}")

        // Name the slots rounding actually moved. Both this write and isThisProfileSet log the
        // *rounded* figure, so without this a log cannot tell "the two rounded the same way" from
        // "neither had anything to round" — and the two agreeing is the whole point of sharing
        // hourlyRatesFor. A rounding test needs to see the value that was asked for.
        val rounded = (0 until HOURS_IN_DAY)
            .map { it to profile.getBasalTimeFromMidnight(it * 60 * 60) }
            .filter { (hour, asked) -> abs(asked - wanted[hour]) > BASAL_RATE_EPSILON }
        if (rounded.isNotEmpty()) {
            aapsLogger.info(
                LTag.PUMP,
                "setNewBasalProfile: rounded to the pump's step " +
                    rounded.joinToString { (hour, asked) -> "hour $hour ${"%.3f".format(asked)}→${"%.2f".format(wanted[hour])}" }
            )
        }

        val written = service.writeBasalProfile(wanted)
        if (written) {
            notificationManager.post(
                NotificationId.PROFILE_SET_OK, rh.gs(CoreUiR.string.profile_set_ok), NotificationLevel.INFO,
                validMinutes = 60
            )
            return result.success(true).enacted(true).comment(rh.gs(CoreUiR.string.ok))
        }

        notificationManager.post(
            NotificationId.FAILED_UPDATE_PROFILE, rh.gs(R.string.ypso_profile_write_failed), NotificationLevel.URGENT
        )
        return result.success(false).enacted(false).comment(rh.gs(R.string.ypso_profile_write_failed))
    }

    /**
     * The 24 hourly rates the pump would hold for [profile], each rounded to the step the pump can
     * actually store at that rate — 0.01 U/h below 1, 0.02 to 2, 0.1 to 15, 0.5 above.
     *
     * One function so the write and [isThisProfileSet] cannot disagree: if they rounded differently,
     * a freshly written profile would read as not set.
     */
    private fun hourlyRatesFor(profile: Profile): List<Double> =
        (0 until HOURS_IN_DAY).map { hour ->
            PumpType.YPSOPUMP.determineCorrectBasalSize(profile.getBasalTimeFromMidnight(hour * 60 * 60))
        }

    private companion object {

        /** The pump stores one basal rate per hour. */
        const val HOURS_IN_DAY = 24

        /** Half the pump's finest basal step, for comparing rates read back against rates asked for. */
        const val BASAL_RATE_EPSILON = 0.005
    }

    /**
     * Delivers a standard bolus. Confirmed on hardware in Phase 2 and again, chunked, in Phase 9;
     * ⚠ what an **interrupted** bolus leaves in the database is Phase 10 and is not yet run.
     *
     * `bolusDelivered` is what the pump reported injecting, not what was asked for, and `success`
     * is only true when the two agree. A cancelled or short bolus therefore reports failure while
     * still carrying the partial amount, which is the combination the caller needs to get IOB
     * right.
     */
    override suspend fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult =
        pumpWriteMutex.withLock { applyDeliverTreatment(detailedBolusInfo) }

    private suspend fun applyDeliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        require(detailedBolusInfo.carbs == 0.0) { detailedBolusInfo.toString() }
        require(detailedBolusInfo.insulin > 0) { detailedBolusInfo.toString() }

        val result = pumpEnactResultProvider()
        val service = ypsoService
        if (!isInitialized() || service == null) {
            aapsLogger.error(LTag.PUMP, "deliverTreatment refused: pump not initialised")
            return result.success(false).enacted(false).comment(rh.gs(R.string.ypso_bolus_not_connected))
        }

        // Clamp to the pump's own maximum. AAPS's SafetyMaxBolus is the user's setting and is
        // applied by the command queue; this is the separate, pump-side cap.
        val pumpMax = preferences.get(YpsoDoubleNonKey.MaxBolus)
        val requested = detailedBolusInfo.insulin
        if (requested > pumpMax) {
            aapsLogger.error(LTag.PUMP, "deliverTreatment refused: ${requested}U exceeds the pump maximum of ${pumpMax}U")
            return result.success(false).enacted(false).comment(rh.gs(R.string.ypso_bolus_over_pump_max, requested, pumpMax))
        }

        aapsLogger.info(LTag.PUMP, "deliverTreatment: ${requested}U")
        // The amount stays a Double the whole way: it is exact centi-units in the pump's log, and
        // squeezing it through the SDK contract's Float turns 1.82 into 1.8200000524520874 — §4d's
        // trap, on a figure that becomes a treatment record.
        val delivery = service.deliverBolus(detailedBolusInfo)
        val delivered = delivery.deliveredUnits

        result.bolusDelivered = delivered
        result.success(delivery.complete).enacted(delivered > 0.0)
        result.comment(delivery.errorMessage ?: rh.gs(CoreUiR.string.ok))
        aapsLogger.info(LTag.PUMP, "deliverTreatment done: asked ${requested}U, pump delivered ${delivered}U, success=${delivery.complete}")
        return result
    }

    override fun stopBolusDelivering() {
        aapsLogger.info(LTag.PUMP, "stopBolusDelivering")
        ypsoService?.stopBolus()
    }

    /**
     * Converts an absolute rate to the percentage the pump works in, then sets it.
     *
     * **Divided by the rate the pump is programmed to run, not the AAPS profile's.** The pump
     * applies the percentage to its own basal program, so if the two disagree — and they do until a
     * profile write exists, which is why [isThisProfileSet] returns false — converting against the
     * AAPS profile would ask for one rate and get another. `baseBasalRate` is read from the pump.
     *
     * ⚠ A percentage is only correct for the basal block it was computed in. When the pump crosses
     * into an hour with a different programmed rate, the same percentage becomes a different
     * absolute rate. That is inherent to a percent-only pump and cannot be fixed here; AAPS
     * re-evaluates each loop iteration, which is what limits the exposure.
     */
    override suspend fun setTempBasalAbsolute(
        absoluteRate: Double,
        durationInMinutes: Int,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult =
        pumpWriteMutex.withLock { applyTempBasalAbsolute(absoluteRate, durationInMinutes, enforceNew, tbrType) }

    private suspend fun applyTempBasalAbsolute(
        absoluteRate: Double,
        durationInMinutes: Int,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult {
        val result = pumpEnactResultProvider()
        if (!isInitialized() || ypsoService == null) {
            return result.success(false).enacted(false).comment(rh.gs(R.string.ypso_bolus_not_connected))
        }

        val pumpBase = baseBasalRate.cU
        if (pumpBase <= 0.0) {
            // Without the pump's own rate there is no divisor, and guessing one would ask for a
            // rate nobody chose. 0.0 means the profile has not been read yet.
            aapsLogger.error(LTag.PUMP, "setTempBasalAbsolute refused: the pump's base rate is not known yet")
            return result.success(false).enacted(false).comment(rh.gs(R.string.ypso_tbr_no_base_rate))
        }

        val percent = Math.round(absoluteRate / pumpBase * 100.0).toInt()
        aapsLogger.info(
            LTag.PUMP,
            "setTempBasalAbsolute: ${absoluteRate}U/h against the pump's ${pumpBase}U/h = $percent% for ${durationInMinutes}min"
        )
        return applyTempBasalPercent(percent, durationInMinutes, enforceNew, tbrType)
    }

    override suspend fun setTempBasalPercent(
        percent: Int,
        durationInMinutes: Int,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult =
        pumpWriteMutex.withLock { applyTempBasalPercent(percent, durationInMinutes, enforceNew, tbrType) }

    private suspend fun applyTempBasalPercent(
        percent: Int,
        durationInMinutes: Int,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult {
        val result = pumpEnactResultProvider()
        val service = ypsoService
        if (!isInitialized() || service == null) {
            return result.success(false).enacted(false).comment(rh.gs(R.string.ypso_bolus_not_connected))
        }

        val outcome = service.setTempBasalPercent(percent, durationInMinutes, tbrType)
        result.success(outcome.confirmed).enacted(outcome.confirmed)
        result.percent = outcome.percentage
        result.duration = outcome.durationMinutes
        result.isPercent = true
        result.comment(outcome.errorMessage ?: rh.gs(CoreUiR.string.ok))
        return result
    }

    override suspend fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult =
        pumpWriteMutex.withLock { applyCancelTempBasal(enforceNew) }

    private suspend fun applyCancelTempBasal(enforceNew: Boolean): PumpEnactResult {
        val result = pumpEnactResultProvider()
        val service = ypsoService
        if (!isInitialized() || service == null) {
            return result.success(false).enacted(false).comment(rh.gs(R.string.ypso_bolus_not_connected))
        }

        val outcome = service.cancelTempBasal()
        result.success(outcome.confirmed).enacted(outcome.confirmed)
        result.isTempCancel = true
        result.comment(outcome.errorMessage ?: rh.gs(CoreUiR.string.ok))
        return result
    }

    override suspend fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult =
        notImplemented("setExtendedBolus(${insulin}U, ${durationInMinutes}min)")

    override suspend fun cancelExtendedBolus(): PumpEnactResult = notImplemented("cancelExtendedBolus")

    override suspend fun loadTDDs(): PumpEnactResult = notImplemented("loadTDDs")

    /**
     * The comment used to be "Saline test build", which said the wrong thing twice: it named a
     * temporary state as the reason for a permanent refusal, and it would have gone on being shown
     * after the build stopped being a saline one. These methods are not refused because the driver
     * is under test; they are refused because nothing behind them is implemented.
     *
     * Since [pumpDescription] no longer advertises these, reaching this method should now be rare —
     * so it logs, because a call that arrives anyway means something is reading the capability from
     * somewhere other than the description.
     */
    private fun notImplemented(what: String): PumpEnactResult {
        aapsLogger.warn(LTag.PUMP, "REFUSED $what: not implemented for this pump, and not advertised in pumpDescription")
        return pumpEnactResultProvider()
            .success(false)
            .enacted(false)
            .comment(rh.gs(R.string.ypso_not_supported))
    }

    // ==================== PREFERENCES ====================

    /**
     * The settings screen.
     *
     * 4.0.0 declares preferences instead of building them view by view: every key already carries
     * its own title and summary, so all that is left here is which keys appear and in what order.
     *
     * The bolus-speed list is on the key too, taken from `BolusSpeed`, so the stored values and the
     * labels cannot drift apart.
     *
     * NOTE: the pairing entry that used to head this screen is gone. It was an intent preference
     * that opened `YpsoBLEScanActivity`, and 4.0.0 has no activity-based plugin UI - pairing needs
     * rebuilding on the shared Compose BLE scan. See PORTING_NOTES.
     */
    override fun getPreferenceScreenContent(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "ypso_settings",
            titleResId = R.string.ypso_pump_setting,
            items = listOf(
                YpsoStringKey.YpsoBolusSpeed,
                YpsoBooleanKey.YpsoNanoBolus,
                YpsoIntKey.YpsoRssiThreshold,
                PreferenceSubScreenDef(
                    key = "ypso_advanced",
                    titleResId = CoreUiR.string.advanced_settings_title,
                    items = listOf(
                        YpsoIntKey.YpsoHistoryReadCount,
                        YpsoBooleanKey.YpsoSyncHistoryToAaps,
                        YpsoBooleanKey.YpsoLegacyAuthSalt,
                        YpsoBooleanKey.YpsoLogInsulinChange,
                        YpsoBooleanKey.YpsoLogSiteChange,
                        YpsoBooleanKey.YpsoLogTubeChange,
                        YpsoBooleanKey.YpsoLogBatteryChange
                    )
                )
            )
        )
}
