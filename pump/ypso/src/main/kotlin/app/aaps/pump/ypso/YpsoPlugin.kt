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
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpPluginBase
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAppExit
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
import app.aaps.pump.ypso.ui.YpsoLoadHistoryCommand
import app.aaps.pump.ypso.ui.YpsoOverviewFragment
import app.aaps.pump.ypso.ui.YpsoSyncTimeCommand
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * AAPS pump driver for the YpsoPump, running against an unlocked plaintext research pump.
 *
 * Shaped on `MedtrumPlugin`: the plugin binds [YpsoService] and forwards to it; the service owns
 * the connection. See [YpsoService] for why there is no delivery path in this build.
 *
 * **No delivery.** Every method on the [Pump] interface that would move insulin or change pump
 * configuration returns a failed [PumpEnactResult] without contacting the pump. They are
 * individually overridden rather than left to a default so that "does nothing" is visible at
 * each call site and a future edit has to be deliberate.
 *
 * The single exception is the clock: [YpsoSyncTimeCommand] reaches
 * [YpsoService.syncPumpTime][app.aaps.pump.ypso.services.YpsoService.syncPumpTime] and does write
 * to the pump. It is not a [Pump] method, it cannot move insulin, and it only ever runs when the
 * user asks for it — it exists to prove the write path before anything that matters uses it.
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
    override val pumpDescription: PumpDescription get() = PumpDescription().fillFor(PumpType.YPSOPUMP)
    override val isFakingTempsByExtendedBoluses: Boolean = false
    override fun canHandleDST(): Boolean = false

    override fun pumpSpecificShortStatus(veryShort: Boolean): String = buildString {
        append("Firmware: ${ypsoPump.firmwareVersion.ifBlank { "?" }}")
        if (!veryShort) {
            append("\nMode: ${ypsoPump.deliveryModeName.ifBlank { "?" }}")
            append("\nNo delivery (clock write only)")
        }
    }

    /**
     * Runs the driver's own commands inside a queued command, so they share the normal
     * connect/disconnect cycle instead of opening a second BLE session from the UI thread.
     */
    override fun executeCustomCommand(customCommand: CustomCommand): PumpEnactResult? =
        when (customCommand) {
            is YpsoLoadHistoryCommand -> pumpEnactResultProvider.get().success(ypsoService?.loadHistory() == true)

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

    override fun isThisProfileSet(profile: Profile): Boolean {
        // Claiming the profile is set would let AAPS believe the pump matches when it has never
        // been written. Claiming it is not set makes AAPS offer to write it, which this build
        // then refuses. The second is the honest answer, and the refusal is visible to the user.
        return false
    }

    override fun setNewBasalProfile(profile: Profile): PumpEnactResult = notImplemented("setNewBasalProfile")

    override fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult =
        notImplemented("deliverTreatment(${detailedBolusInfo.insulin}U)")

    override fun stopBolusDelivering() {
        aapsLogger.warn(LTag.PUMP, "stopBolusDelivering ignored: this build never starts a bolus")
    }

    override fun setTempBasalAbsolute(
        absoluteRate: Double,
        durationInMinutes: Int,
        profile: Profile,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult = notImplemented("setTempBasalAbsolute($absoluteRate U/h, ${durationInMinutes}min)")

    override fun setTempBasalPercent(
        percent: Int,
        durationInMinutes: Int,
        profile: Profile,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult = notImplemented("setTempBasalPercent($percent%, ${durationInMinutes}min)")

    override fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult = notImplemented("cancelTempBasal")

    override fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult =
        notImplemented("setExtendedBolus(${insulin}U, ${durationInMinutes}min)")

    override fun cancelExtendedBolus(): PumpEnactResult = notImplemented("cancelExtendedBolus")

    override fun loadTDDs(): PumpEnactResult = notImplemented("loadTDDs")

    private fun notImplemented(what: String): PumpEnactResult {
        aapsLogger.warn(LTag.PUMP, "REFUSED $what: this is a read-only build")
        return pumpEnactResultProvider.get()
            .success(false)
            .enacted(false)
            .comment(rh.gs(R.string.ypso_read_only_build))
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
