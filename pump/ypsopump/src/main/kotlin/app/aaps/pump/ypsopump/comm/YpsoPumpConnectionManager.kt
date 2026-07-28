package app.aaps.pump.ypsopump.comm

import android.os.SystemClock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.common.defs.PumpDriverState
import app.aaps.pump.ypsopump.comm.command.parameters.CommandParameters
import app.aaps.pump.ypsopump.comm.command.response.CommandResponse
import app.aaps.pump.ypsopump.comm.command.response.SimpleDataCommandResponse
import app.aaps.pump.ypsopump.comm.command.response.TemporaryBasalResponse
import app.aaps.pump.ypsopump.connector.YpsoPumpConnectorInterface
import app.aaps.pump.ypsopump.connector.YpsoPumpDummyConnector
import app.aaps.pump.ypsopump.data.DateTimeDto
import app.aaps.pump.ypsopump.defs.PumpUpdateFragmentType
import app.aaps.pump.ypsopump.defs.YpsoPumpCommandType
import app.aaps.pump.ypsopump.defs.YpsoPumpFirmware
import app.aaps.pump.ypsopump.driver.YpsopumpPumpStatus
import app.aaps.pump.ypsopump.event.EventPumpConfigurationChanged
import app.aaps.pump.ypsopump.event.EventPumpFragmentValuesChanged
import app.aaps.pump.ypsopump.util.YpsoPumpUtil
import dagger.android.HasAndroidInjector
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FORWARD-PORT NOTE
 *
 * Andy's original reached past the connector seam and drove his own BLE stack directly for
 * `determineFirmwareVersion`, `getConfiguration`, `getBasalProfile` and `getTime` (via
 * `GetFirmwareVersion`, `GetPumpSettings`, `GetBasalProfile`, `GetDateTime` BLE commands against
 * `YpsoPumpBLE`).
 *
 * That BLE stack is NOT part of this port - it is real pump communication, and per the integration
 * plan it is being replaced wholesale by the vicktor SDK behind a new connector. So every operation
 * here now goes through [YpsoPumpConnectorInterface], which is the intended seam. With the dummy
 * connector wired in, all of them report "not implemented".
 *
 * See PORTING_NOTES.md.
 */
@Singleton
class YpsoPumpConnectionManager @Inject constructor(
    val pumpStatus: YpsopumpPumpStatus,
    val pumpUtil: YpsoPumpUtil,
    val preferences: Preferences,
    val injector: HasAndroidInjector,
    val aapsLogger: AAPSLogger,
    val rxBus: RxBus,
    val fabricPrivacy: FabricPrivacy
) {

    private val selectedConnector: YpsoPumpConnectorInterface
    private val disposable = CompositeDisposable()
    private var currentFirmware: YpsoPumpFirmware? = null

    var inConnectMode = false
    var inDisconnectMode = false

    fun connectToPump(deviceMac: String, deviceBonded: Boolean): Boolean {

        if (pumpUtil.driverStatus === PumpDriverState.Ready) {
            return true
        }

        if (inConnectMode) return false

        if (deviceMac.isEmpty() && !deviceBonded) {
            return false
        }

        inConnectMode = true

        try {
            if (!selectedConnector.connectToPump()) {
                return false
            }

            val timeoutTime = System.currentTimeMillis() + (120 * 1000)

            while (System.currentTimeMillis() < timeoutTime) {
                SystemClock.sleep(5000)

                val driverStatus = pumpUtil.driverStatus
                aapsLogger.debug(LTag.PUMPCOMM, "connectToPump: " + driverStatus.name)

                if (driverStatus == PumpDriverState.Ready || driverStatus == PumpDriverState.ErrorCommunicatingWithPump) {
                    break
                }
            }
            return true
        } finally {
            inConnectMode = false
        }
    }

    private fun resetFirmwareVersion() {}

    fun determineFirmwareVersion() {
        val firmware = selectedConnector.retrieveFirmwareVersion()

        if (firmware != null) {
            pumpStatus.ypsopumpFirmware = firmware
            pumpStatus.isFirmwareSet = true
            this.currentFirmware = firmware
            rxBus.send(EventPumpFragmentValuesChanged(PumpUpdateFragmentType.Configuration))
        } else {
            aapsLogger.error(LTag.PUMPCOMM, "Problem getting firmware version (connector returned null).")
        }
    }

    fun executeCommand(parameters: CommandParameters): CommandResponse {
        pumpUtil.currentCommand = parameters.commandType
        pumpUtil.driverStatus = PumpDriverState.Connected
        return CommandResponse.builder().success(false).errorDescription("Command not implemented.").build()
    }

    fun disconnectFromPump() {

        var driverStatus: PumpDriverState = pumpUtil.driverStatus

        when (driverStatus) {
            PumpDriverState.NotInitialized,
            PumpDriverState.Initialized                -> {
                aapsLogger.warn(LTag.PUMPCOMM, "disconnectFromPump. Pump is in weird state (${driverStatus.name}), exiting.")
                return
            }

            PumpDriverState.ErrorCommunicatingWithPump -> {
                val errorType = pumpUtil.errorType
                aapsLogger.warn(LTag.PUMPCOMM, "disconnectFromPump. Pump is in error ($errorType), exiting.")
                return
            }

            PumpDriverState.Connecting,
            PumpDriverState.EncryptCommunication,
            PumpDriverState.ExecutingCommand,
            PumpDriverState.Busy,
            PumpDriverState.Suspended,
            PumpDriverState.Connected                  -> {
                aapsLogger.warn(LTag.PUMPCOMM, "disconnectFromPump. Pump seems to be in unallowed state (${driverStatus.name}), exiting and setting to Sleep.")
                pumpUtil.driverStatus = PumpDriverState.Sleeping
                return
            }

            PumpDriverState.Disconnecting              -> {
                aapsLogger.warn(LTag.PUMPCOMM, "disconnectFromPump. Pump is already disconnecting, exiting.")
                return
            }

            else                                       -> return
        }
    }

    fun deliverBolus(detailedBolusInfo: DetailedBolusInfo?): CommandResponse? {
        pumpUtil.currentCommand = YpsoPumpCommandType.SetBolus
        val commandResponse = selectedConnector.sendBolus(detailedBolusInfo!!)
        pumpUtil.resetDriverStatusToConnected()
        return commandResponse
    }

    fun getTemporaryBasal(): TemporaryBasalResponse? {
        pumpUtil.currentCommand = YpsoPumpCommandType.GetTemporaryBasal
        val commandResponse = selectedConnector.retrieveTemporaryBasal()
        pumpUtil.resetDriverStatusToConnected()
        return commandResponse
    }

    fun setTemporaryBasal(value: Int, duration: Int): CommandResponse? {
        pumpUtil.currentCommand = YpsoPumpCommandType.SetTemporaryBasal
        val commandResponse = selectedConnector.sendTemporaryBasal(value, duration)
        pumpUtil.resetDriverStatusToConnected()
        return commandResponse
    }

    fun setBasalProfile(profile: Profile?): CommandResponse? {
        pumpUtil.currentCommand = YpsoPumpCommandType.SetBasalProfile
        val commandResponse = selectedConnector.sendBasalProfile(profile!!)
        pumpUtil.resetDriverStatusToConnected()
        return commandResponse
    }

    fun sendFakeCommand(commandType: YpsoPumpCommandType): CommandResponse? {
        pumpUtil.currentCommand = commandType
        pumpUtil.resetDriverStatusToConnected()
        return CommandResponse.builder().success(false).errorDescription("Command not implemented.").build()
    }

    fun cancelTemporaryBasal(): CommandResponse? {
        pumpUtil.currentCommand = YpsoPumpCommandType.CancelTemporaryBasal
        val commandResponse = selectedConnector.cancelTemporaryBasal()
        pumpUtil.resetDriverStatusToConnected()
        return commandResponse
    }

    fun getRemainingInsulin(): SimpleDataCommandResponse? = selectedConnector.retrieveRemainingInsulin()

    val batteryStatus: SimpleDataCommandResponse?
        get() = selectedConnector.retrieveBatteryStatus()

    /**
     * DEFERRED: unpacking the settings payload.
     *
     * Andy populated bolusStep / activeProfileName / maxBolus / maxBasal from a `YpsoPumpSettingsDto`
     * produced by his `GetPumpSettings` BLE command. The connector seam instead returns
     * [app.aaps.pump.ypsopump.comm.command.response.MapDataCommandResponse], whose payload field has
     * no accessor and whose key names were never defined. Rather than invent a contract, the mapping
     * is left for the session that writes the real connector. See PORTING_NOTES.md.
     */
    fun getConfiguration() {
        val response = selectedConnector.retrieveConfiguration()

        if (response == null || !response.isSuccess) {
            aapsLogger.error(LTag.PUMPCOMM, "Problem reading configuration: ${response?.errorDescription}")
            return
        }

        pumpStatus.lastConfigurationUpdate = System.currentTimeMillis()
        aapsLogger.warn(LTag.PUMPCOMM, "getConfiguration: response payload mapping not implemented yet.")
    }

    /**
     * DEFERRED: same as [getConfiguration] - `BasalProfileResponse` carries a `Double[]` with no
     * accessor, while the pump status wants a `BasalProfileDto`. Mapping deferred to the connector
     * work. See PORTING_NOTES.md.
     */
    fun getBasalProfile(): Boolean {
        val response = selectedConnector.retrieveBasalProfile()

        if (response == null || !response.isSuccess) {
            aapsLogger.error(LTag.PUMPCOMM, "Problem reading basal profile: ${response?.errorDescription}")
            return false
        }

        aapsLogger.warn(LTag.PUMPCOMM, "getBasalProfile: response payload mapping not implemented yet.")
        return false
    }

    /**
     * DEFERRED: reading the pump clock needs a connector method. Andy read it straight off his BLE
     * stack (GetDateTime); the connector interface has no equivalent yet. Add
     * `retrieveDateTime()`/`sendDateTime()` to [YpsoPumpConnectorInterface] when wiring the vicktor
     * connector - see PORTING_NOTES.md.
     */
    fun getTime(): DateTimeDto? {
        aapsLogger.warn(LTag.PUMPCOMM, "getTime: not implemented (no connector method yet).")
        return null
    }

    fun setTime(): DateTimeDto? {
        aapsLogger.warn(LTag.PUMPCOMM, "setTime: not implemented (no connector method yet).")
        return null
    }

    fun getPumpHistory() {
        selectedConnector.getPumpHistory()
    }

    init {
        selectedConnector = YpsoPumpDummyConnector(pumpStatus, pumpUtil, injector, aapsLogger)
        disposable.add(
            rxBus
                .toObservable(EventPumpConfigurationChanged::class.java)
                .observeOn(Schedulers.io())
                .subscribe({ resetFirmwareVersion() }, fabricPrivacy::logException)
        )
    }
}
