package app.aaps.pump.ypso

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.ypso.keys.YpsoDoubleNonKey
import app.aaps.pump.ypso.keys.YpsoIntNonKey
import app.aaps.pump.ypso.keys.YpsoLongNonKey
import app.aaps.pump.ypso.keys.YpsoStringNonKey
import com.pump.common.ActiveTbr
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the driver knows about the pump between connections.
 *
 * Shaped on `MedtrumPump`: the plugin and the fragment read from here rather than from the
 * service, so a value survives the service being unbound and rebound. Values that must outlive
 * a process restart are mirrored into preferences.
 */
@Singleton
class YpsoPump @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val dateUtil: DateUtil
) {

    var pumpMac: String
        get() = preferences.get(YpsoStringNonKey.MacAddress)
        set(value) = preferences.put(YpsoStringNonKey.MacAddress, value)

    var pumpName: String
        get() = preferences.get(YpsoStringNonKey.DeviceName)
        set(value) = preferences.put(YpsoStringNonKey.DeviceName, value)

    /** Serial as printed on the pump, taken from the advertised name `YpsoPump_<serial>`. */
    var serialNumber: String
        get() = preferences.get(YpsoStringNonKey.SerialNumber)
        set(value) = preferences.put(YpsoStringNonKey.SerialNumber, value)

    var firmwareVersion: String
        get() = preferences.get(YpsoStringNonKey.FirmwareVersion)
        set(value) = preferences.put(YpsoStringNonKey.FirmwareVersion, value)

    var lastConnection: Long
        get() = preferences.get(YpsoLongNonKey.LastConnection)
        set(value) = preferences.put(YpsoLongNonKey.LastConnection, value)

    var reservoir: Double
        get() = preferences.get(YpsoDoubleNonKey.Reservoir)
        set(value) = preferences.put(YpsoDoubleNonKey.Reservoir, value)

    var batteryPercent: Int
        get() = preferences.get(YpsoIntNonKey.BatteryLevel)
        set(value) = preferences.put(YpsoIntNonKey.BatteryLevel, value)

    /** 0 = program A, 1 = program B, -1 = not read yet. */
    var activeProfileId: Int
        get() = preferences.get(YpsoIntNonKey.ActiveProfileId)
        set(value) = preferences.put(YpsoIntNonKey.ActiveProfileId, value)

    var lastBolusTime: Long
        get() = preferences.get(YpsoLongNonKey.LastBolusTime)
        set(value) = preferences.put(YpsoLongNonKey.LastBolusTime, value)

    var lastBolusAmount: Double
        get() = preferences.get(YpsoDoubleNonKey.LastBolusAmount)
        set(value) = preferences.put(YpsoDoubleNonKey.LastBolusAmount, value)

    /** The pump's delivery mode as a display string, e.g. "Basal" or "TBR Active". */
    var deliveryModeName: String = ""

    var isDelivering: Boolean = false

    /**
     * Whether the pump says a temporary basal is running — the boolean form of
     * "status byte 10 is not 100".
     */
    var isTbrRunning: Boolean = false

    /** Full temporary basal detail, or null when none is running *or* it has not been read. */
    var activeTbr: ActiveTbr? = null

    /**
     * How far the pump's clock sat from the phone's at the last read, in milliseconds; positive
     * means the pump is ahead. Null until read, and null again after a failed read — a skew left
     * over from an earlier session is indistinguishable on screen from a fresh one.
     */
    var pumpClockSkewMs: Long? = null

    /** 24 hourly rates in U/h for the active program, empty until read. */
    var basalProfileRates: List<Double> = emptyList()

    /** Last raw history dump, kept in memory only, for the firmware parity check. */
    var lastHistoryDump: List<String> = emptyList()

    /**
     * Report from the last Phase 8a extended-bolus probe, in memory only.
     *
     * A bench instrument's output, held so the YPSO tab can show it without the fragment having to
     * reach into the service. Empty until the probe has been run, which on a shipped build is
     * never — nothing runs it but a button press.
     */
    var lastExtendedBolusProbe: String = ""

    /**
     * Outcome of the last clock write: true only when the pump's clock was read back and agreed
     * with the phone's, false when it did not, null when the clock has never been written.
     *
     * Held here rather than taken from the command queue's callback because
     * [app.aaps.core.interfaces.queue.Callback.result] is a `lateinit` that is never set when a
     * command is dropped, and reading it then throws.
     */
    var lastClockWriteConfirmed: Boolean? = null

    val isPaired: Boolean get() = pumpMac.isNotBlank()

    /**
     * Base basal rate right now: the active program's rate for the current hour.
     *
     * Returns 0.0 until the profile has been read, which is honest — the driver genuinely does
     * not know the rate yet, and reporting a made-up value would corrupt every calculation
     * downstream.
     */
    val baseBasalRate: Double
        get() {
            val rates = basalProfileRates
            if (rates.size != 24) return 0.0
            return rates[Calendar.getInstance().get(Calendar.HOUR_OF_DAY)]
        }

    fun onConnectionSucceeded() {
        lastConnection = dateUtil.now()
    }

    /**
     * Clears everything read *from* a pump, keeping the identity of the pump we are paired to.
     *
     * MAC, name and serial are set by the pairing screen and must survive this — the service
     * calls it right after the user picks a pump, and wiping the serial here would discard the
     * choice that was just made.
     */
    fun reset() {
        aapsLogger.debug(LTag.PUMP, "YpsoPump: resetting cached pump state")
        reservoir = 0.0
        batteryPercent = 0
        activeProfileId = -1
        lastConnection = 0L
        firmwareVersion = ""
        deliveryModeName = ""
        isDelivering = false
        isTbrRunning = false
        activeTbr = null
        pumpClockSkewMs = null
        basalProfileRates = emptyList()
        lastHistoryDump = emptyList()
        lastClockWriteConfirmed = null
    }
}
