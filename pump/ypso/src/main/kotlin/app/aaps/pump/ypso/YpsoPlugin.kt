package app.aaps.pump.ypso

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.pump.defs.TimeChangeType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpPluginBase
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.defs.determineCorrectBasalSize
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAppExit
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveListPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.pump.ypso.keys.YpsoBooleanKey
import app.aaps.pump.ypso.keys.YpsoDoubleNonKey
import app.aaps.pump.ypso.keys.YpsoIntKey
import app.aaps.pump.ypso.keys.YpsoIntNonKey
import app.aaps.pump.ypso.keys.YpsoLongNonKey
import app.aaps.pump.ypso.keys.YpsoStringKey
import app.aaps.pump.ypso.keys.YpsoStringNonKey
import app.aaps.pump.ypso.services.YpsoService
import app.aaps.pump.ypso.ui.YpsoExtendedBolusProbeCommand
import app.aaps.pump.ypso.ui.YpsoLoadHistoryCommand
import app.aaps.pump.ypso.ui.YpsoOverviewFragment
import app.aaps.pump.ypso.ui.YpsoSyncTimeCommand
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.abs

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
@Singleton
class YpsoPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    preferences: Preferences,
    commandQueue: CommandQueue,
    private val aapsSchedulers: AapsSchedulers,
    private val rxBus: RxBus,
    private val context: Context,
    private val fabricPrivacy: FabricPrivacy,
    private val ypsoPump: YpsoPump,
    private val uiInteraction: UiInteraction,
    private val pumpEnactResultProvider: Provider<PumpEnactResult>
) : PumpPluginBase(
    pluginDescription = PluginDescription()
        .mainType(PluginType.PUMP)
        .fragmentClass(YpsoOverviewFragment::class.java.name)
        .pluginIcon(R.drawable.ic_ypso_pump_128)
        .pluginName(R.string.ypso_pump)
        .shortName(R.string.ypso_pump_shortname)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .description(R.string.ypso_pump_description),
    ownPreferences = listOf(
        YpsoStringKey::class.java, YpsoBooleanKey::class.java, YpsoIntKey::class.java,
        YpsoStringNonKey::class.java, YpsoIntNonKey::class.java,
        YpsoLongNonKey::class.java, YpsoDoubleNonKey::class.java
    ),
    aapsLogger, rh, preferences, commandQueue
), Pump {

    private val disposable = CompositeDisposable()
    private var ypsoService: YpsoService? = null

    override fun onStart() {
        super.onStart()
        aapsLogger.debug(LTag.PUMP, "YpsoPlugin onStart()")
        context.bindService(Intent(context, YpsoService::class.java), connection, Context.BIND_AUTO_CREATE)
        disposable += rxBus
            .toObservable(EventAppExit::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ context.unbindService(connection) }, fabricPrivacy::logException)
    }

    override fun onStop() {
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

    override fun getPumpStatus(reason: String) {
        aapsLogger.debug(LTag.PUMP, "getPumpStatus - reason: $reason")
        if (ypsoService?.readPumpStatus() != true) {
            aapsLogger.error(LTag.PUMP, "getPumpStatus failed")
        }
    }

    override val lastDataTime: Long get() = ypsoPump.lastConnection
    override val lastBolusTime: Long? get() = ypsoPump.lastBolusTime.takeIf { it > 0 }
    override val lastBolusAmount: Double? get() = ypsoPump.lastBolusAmount.takeIf { it > 0.0 }
    override val baseBasalRate: Double get() = ypsoPump.baseBasalRate
    override val reservoirLevel: Double get() = ypsoPump.reservoir
    override val batteryLevel: Int? get() = ypsoPump.batteryPercent.takeIf { it > 0 }

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
    override fun timezoneOrDSTChanged(timeChangeType: TimeChangeType) {
        aapsLogger.info(LTag.PUMP, "Phone time changed ($timeChangeType); queueing a pump clock sync")
        if (!isInitialized()) {
            aapsLogger.warn(LTag.PUMP, "Not initialised, so the pump clock cannot be corrected yet")
            return
        }
        commandQueue.customCommand(YpsoSyncTimeCommand(), object : Callback() {
            override fun run() {
                if (result.success) {
                    aapsLogger.info(LTag.PUMP, "Pump clock corrected after $timeChangeType")
                } else {
                    aapsLogger.error(LTag.PUMP, "Pump clock NOT corrected after $timeChangeType — it is now wrong by the offset")
                    uiInteraction.addNotification(
                        Notification.TIME_OR_TIMEZONE_CHANGE,
                        rh.gs(R.string.ypso_clock_dst_failed),
                        Notification.URGENT
                    )
                }
            }
        })
    }

    /**
     * ⚠ The last line is a **safety statement, not a status field**, and it is deliberately not
     * derived from anything.
     *
     * It used to read "profile write UNTESTED", which stopped being true on 2026-07-31 when Phase 4
     * passed — a stale claim in the one string a user reads to decide what the build in their hand
     * can do. What replaced it is the claim that is still true and will stay true until a human
     * decides otherwise: every test to date has been on saline, off-body.
     */
    override fun pumpSpecificShortStatus(veryShort: Boolean): String = buildString {
        append("Firmware: ${ypsoPump.firmwareVersion.ifBlank { "?" }}")
        if (!veryShort) {
            append("\nMode: ${ypsoPump.deliveryModeName.ifBlank { "?" }}")
            append("\nSaline test build — not for use with insulin")
        }
    }

    /**
     * Runs the driver's own commands inside a queued command, so they share the normal
     * connect/disconnect cycle instead of opening a second BLE session from the UI thread.
     */
    override fun executeCustomCommand(customCommand: CustomCommand): PumpEnactResult? =
        when (customCommand) {
            is YpsoLoadHistoryCommand -> pumpEnactResultProvider.get().success(ypsoService?.loadHistory() == true)

            // Phase 8a's bench probe. `success` here means "the probe ran and produced a report",
            // not "an extended bolus was delivered" — the report is the output and it is the thing
            // to read. Deliberately not routed through setExtendedBolus, which stays refused: see
            // YpsoExtendedBolusProbeCommand for why that flag is not one to flip early.
            is YpsoExtendedBolusProbeCommand -> {
                val report = ypsoService?.probeExtendedBolus() ?: "The pump service is not bound"
                ypsoPump.lastExtendedBolusProbe = report
                aapsLogger.info(LTag.PUMP, "Extended bolus probe report:\n$report")
                pumpEnactResultProvider.get().success(true).enacted(false).comment(report)
            }

            // The clock write. `confirmed` is what the pump's own clock read back as, not what
            // the GATT write returned, so reporting it as enacted is a claim about the pump
            // rather than about the phone.
            is YpsoSyncTimeCommand    -> {
                val confirmed = ypsoService?.syncPumpTime() == true
                pumpEnactResultProvider.get()
                    .success(confirmed)
                    .enacted(confirmed)
                    .comment(rh.gs(if (confirmed) R.string.ypso_clock_set_ok else R.string.ypso_clock_set_failed))
            }

            else                      -> null
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
    override fun isThisProfileSet(profile: Profile): Boolean {
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
    @Synchronized
    override fun setNewBasalProfile(profile: Profile): PumpEnactResult {
        val result = pumpEnactResultProvider.get()
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
            uiInteraction.addNotificationValidFor(
                Notification.PROFILE_SET_OK, rh.gs(app.aaps.core.ui.R.string.profile_set_ok), Notification.INFO, 60
            )
            return result.success(true).enacted(true).comment(rh.gs(app.aaps.core.ui.R.string.ok))
        }

        uiInteraction.addNotification(
            Notification.FAILED_UPDATE_PROFILE, rh.gs(R.string.ypso_profile_write_failed), Notification.URGENT
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
     * Delivers a standard bolus. **Under saline test — see SALINE_TEST_PROCEDURE.md phase 2.**
     *
     * `bolusDelivered` is what the pump reported injecting, not what was asked for, and `success`
     * is only true when the two agree. A cancelled or short bolus therefore reports failure while
     * still carrying the partial amount, which is the combination the caller needs to get IOB
     * right.
     */
    @Synchronized
    override fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        require(detailedBolusInfo.carbs == 0.0) { detailedBolusInfo.toString() }
        require(detailedBolusInfo.insulin > 0) { detailedBolusInfo.toString() }

        val result = pumpEnactResultProvider.get()
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
        result.comment(delivery.errorMessage ?: rh.gs(app.aaps.core.ui.R.string.ok))
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
    @Synchronized
    override fun setTempBasalAbsolute(
        absoluteRate: Double,
        durationInMinutes: Int,
        profile: Profile,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult {
        val result = pumpEnactResultProvider.get()
        if (!isInitialized() || ypsoService == null) {
            return result.success(false).enacted(false).comment(rh.gs(R.string.ypso_bolus_not_connected))
        }

        val pumpBase = baseBasalRate
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
        return setTempBasalPercent(percent, durationInMinutes, profile, enforceNew, tbrType)
    }

    @Synchronized
    override fun setTempBasalPercent(
        percent: Int,
        durationInMinutes: Int,
        profile: Profile,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult {
        val result = pumpEnactResultProvider.get()
        val service = ypsoService
        if (!isInitialized() || service == null) {
            return result.success(false).enacted(false).comment(rh.gs(R.string.ypso_bolus_not_connected))
        }

        val outcome = service.setTempBasalPercent(percent, durationInMinutes, tbrType)
        result.success(outcome.confirmed).enacted(outcome.confirmed)
        result.percent = outcome.percentage
        result.duration = outcome.durationMinutes
        result.isPercent = true
        result.comment(outcome.errorMessage ?: rh.gs(app.aaps.core.ui.R.string.ok))
        return result
    }

    @Synchronized
    override fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult {
        val result = pumpEnactResultProvider.get()
        val service = ypsoService
        if (!isInitialized() || service == null) {
            return result.success(false).enacted(false).comment(rh.gs(R.string.ypso_bolus_not_connected))
        }

        val outcome = service.cancelTempBasal()
        result.success(outcome.confirmed).enacted(outcome.confirmed)
        result.isTempCancel = true
        result.comment(outcome.errorMessage ?: rh.gs(app.aaps.core.ui.R.string.ok))
        return result
    }

    override fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult =
        notImplemented("setExtendedBolus(${insulin}U, ${durationInMinutes}min)")

    override fun cancelExtendedBolus(): PumpEnactResult = notImplemented("cancelExtendedBolus")

    override fun loadTDDs(): PumpEnactResult = notImplemented("loadTDDs")

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
        return pumpEnactResultProvider.get()
            .success(false)
            .enacted(false)
            .comment(rh.gs(R.string.ypso_not_supported))
    }

    // ==================== PREFERENCES ====================

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null && requiredKey != "ypso_advanced") return

        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "ypso_settings"
            title = rh.gs(R.string.ypso_pump_setting)
            initialExpandedChildrenCount = 0

            addPreference(
                androidx.preference.Preference(context).apply {
                    key = "ypso_pairing"
                    title = rh.gs(R.string.ypso_pairing_title)
                    summary = rh.gs(R.string.ypso_pairing_summary)
                    intent = Intent(context, app.aaps.pump.ypso.activities.YpsoBLEScanActivity::class.java)
                }
            )
            addPreference(
                AdaptiveListPreference(
                    ctx = context,
                    stringKey = YpsoStringKey.YpsoBolusSpeed,
                    title = R.string.ypso_bolus_speed_title,
                    dialogTitle = R.string.ypso_bolus_speed_title,
                    entries = arrayOf("3 U/min", "12 U/min", "33.3 U/min"),
                    entryValues = arrayOf("0", "1", "2")
                )
            )
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context,
                    booleanKey = YpsoBooleanKey.YpsoNanoBolus,
                    title = R.string.ypso_nano_bolus_title,
                    summary = R.string.ypso_nano_bolus_summary
                )
            )
            addPreference(
                AdaptiveIntPreference(
                    ctx = context,
                    intKey = YpsoIntKey.YpsoRssiThreshold,
                    title = R.string.ypso_rssi_threshold_title,
                    dialogMessage = R.string.ypso_rssi_threshold_summary
                )
            )
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "ypso_advanced"
                title = rh.gs(app.aaps.core.ui.R.string.advanced_settings_title)
                addPreference(
                    AdaptiveIntPreference(
                        ctx = context,
                        intKey = YpsoIntKey.YpsoHistoryReadCount,
                        title = R.string.ypso_history_read_count_title,
                        dialogMessage = R.string.ypso_history_read_count_summary
                    )
                )
                addPreference(
                    AdaptiveSwitchPreference(
                        ctx = context,
                        booleanKey = YpsoBooleanKey.YpsoSyncHistoryToAaps,
                        title = R.string.ypso_sync_history_title,
                        summary = R.string.ypso_sync_history_summary
                    )
                )
                addPreference(
                    AdaptiveSwitchPreference(
                        ctx = context,
                        booleanKey = YpsoBooleanKey.YpsoLegacyAuthSalt,
                        title = R.string.ypso_legacy_auth_salt_title,
                        summary = R.string.ypso_legacy_auth_salt_summary
                    )
                )
                addPreference(
                    AdaptiveSwitchPreference(
                        ctx = context,
                        booleanKey = YpsoBooleanKey.YpsoLogInsulinChange,
                        title = R.string.ypso_log_insulin_change_title,
                        summary = R.string.ypso_log_insulin_change_summary
                    )
                )
                addPreference(
                    AdaptiveSwitchPreference(
                        ctx = context,
                        booleanKey = YpsoBooleanKey.YpsoLogSiteChange,
                        title = R.string.ypso_log_site_change_title,
                        summary = R.string.ypso_log_site_change_summary
                    )
                )
                addPreference(
                    AdaptiveSwitchPreference(
                        ctx = context,
                        booleanKey = YpsoBooleanKey.YpsoLogTubeChange,
                        title = R.string.ypso_log_tube_change_title,
                        summary = R.string.ypso_log_tube_change_summary
                    )
                )
                addPreference(
                    AdaptiveSwitchPreference(
                        ctx = context,
                        booleanKey = YpsoBooleanKey.YpsoLogBatteryChange,
                        title = R.string.ypso_log_battery_change_title,
                        summary = R.string.ypso_log_battery_change_summary
                    )
                )
            })
        }
    }
}
