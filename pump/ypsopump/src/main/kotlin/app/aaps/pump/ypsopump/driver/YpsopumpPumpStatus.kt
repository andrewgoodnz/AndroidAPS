package app.aaps.pump.ypsopump.driver

import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.pump.common.data.PumpStatus
import app.aaps.pump.ypsopump.defs.BasalProfileStatus
import app.aaps.core.interfaces.pump.defs.PumpDeviceState
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.pump.ypsopump.comm.data.YpsoPumpStatusEntry
import app.aaps.pump.ypsopump.comm.data.YpsoPumpStatusList
import app.aaps.pump.ypsopump.data.BasalProfileDto
import app.aaps.pump.ypsopump.defs.YpsoPumpFirmware
import app.aaps.pump.ypsopump.keys.YpsopumpLongNonKey
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.interfaces.Preferences
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Created by andy on 1/4/21.
 */
@Singleton
class YpsopumpPumpStatus @Inject constructor(private val resourceHelper: ResourceHelper,
                                             private val preferences: Preferences,
                                             private val rxBus: RxBus
) : PumpStatus(PumpType.YPSOPUMP) {

    lateinit var pumpDescription: PumpDescription
    var errorDescription: String? = null
    var ypsopumpFirmware: YpsoPumpFirmware = YpsoPumpFirmware.VERSION_1_5  // TODO we default to 1.0 here
    var isFirmwareSet = false
    @JvmField var baseBasalRate = 0.0
    var serialNumber: Long? = null
    var ypsoPumpStatusList: YpsoPumpStatusList? = null

    //        rileyLinkUtil.getRileyLinkHistory().add(new RLHistoryItem(pumpDeviceState, RileyLinkTargetDevice.MedtronicPump));
//
//        rxBus.send(new EventRileyLinkDeviceStatusChange(pumpDeviceState));
    //public Double maxBolus;
    //public Double maxBasal;
    // statuses
    var pumpDeviceState = PumpDeviceState.NeverContacted

    // NOTE: tempBasalStart / tempBasalPercent / tempBasalDuration / tempBasalEnd are now declared
    // on the upstream PumpStatus base class (as nullable), so the local copies were removed.

    // TODO determine if this is needed
    var basalProfileStatus = BasalProfileStatus.NotInitialized
    var basalProfile: Profile? = null

    //var connectionStatus = YpsoConnectionStatus.NOT_CONNECTED
    var settingsServiceVersion: String? = null

    var lastConfigurationUpdate: Long = 0
    var configChanged: Boolean = false
    var bolusStep: Double = 0.1
    var maxBolus: Double? = null
    var maxBasal: Double? = null

    var forceRefreshBasalProfile: Boolean = true
    var basalProfilePump: BasalProfileDto? = null

    // upstream PumpStatus no longer declares initSettings(), so this is a plain private helper now
    private fun initSettings() {
        activeProfileName = "A"
        reservoirRemainingUnits = 75.0
        reservoirFullUnits = 160
        batteryRemaining = 75
        lastConnection = preferences.get(YpsopumpLongNonKey.LastGoodPumpCommunicationTime)
        lastDataTime = lastConnection
    }

    //var ypsoPumpStatusList: YpsoPumpStatusList? = null

    fun getPumpStatusValuesForSelectedPump(): YpsoPumpStatusEntry? {
        return ypsoPumpStatusList!!.map.get(serialNumber)
    }

    fun setPumpStatusValues(entry: YpsoPumpStatusEntry) {
        ypsoPumpStatusList!!.map.put(entry.serialNumber, entry)
        
    }

    val basalProfileForHour: Double
        get() {
            if (basalsByHour != null) {
                val c = GregorianCalendar()
                val hour = c[Calendar.HOUR_OF_DAY]
                return basalsByHour!![hour]
            }
            return 0.0
        }

    override val errorInfo: String
        get() = if (errorDescription == null) "-" else errorDescription!!

    init {
        initSettings()
    }
}