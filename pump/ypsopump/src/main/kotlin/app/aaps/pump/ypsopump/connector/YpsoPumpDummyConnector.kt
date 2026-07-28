package app.aaps.pump.ypsopump.connector

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.pump.ypsopump.comm.command.response.BasalProfileResponse
import app.aaps.pump.ypsopump.comm.command.response.CommandResponse
import app.aaps.pump.ypsopump.comm.command.response.TemporaryBasalResponse
import app.aaps.pump.ypsopump.defs.YpsoPumpFirmware
import app.aaps.pump.ypsopump.driver.YpsopumpPumpStatus
import app.aaps.pump.ypsopump.util.YpsoPumpUtil
import dagger.android.HasAndroidInjector
import javax.inject.Singleton

/**
 * Non-communicating connector. This is the ONLY connector wired up in the current port - there is no
 * pump communication in the tree at all.
 *
 * SAFETY: every delivery command (bolus, TBR, basal profile) returns failure / "not implemented".
 * Andy's original dummy returned `success(true)` for all of them so he could exercise the UI; that
 * is deliberately NOT carried over, because a dummy that reports successful insulin delivery is a
 * hazard the moment anything above it starts trusting the result. Do not "restore" it - write a real
 * connector instead. See PORTING_NOTES.md.
 */
@Singleton
class YpsoPumpDummyConnector(
    var pumpStatus: YpsopumpPumpStatus,
    ypsopumpUtil: YpsoPumpUtil,
    injector: HasAndroidInjector,
    aapsLogger: AAPSLogger
) : YpsoPumpConnectorAbstract(ypsopumpUtil, injector, aapsLogger) {

    private fun notImplemented(command: String): CommandResponse {
        aapsLogger.warn(LTag.PUMPCOMM, "YpsoPumpDummyConnector: $command - not implemented (no connector).")
        return CommandResponse.builder().success(false).errorDescription("Command not implemented.").build()
    }

    override fun connectToPump(): Boolean {
        aapsLogger.warn(LTag.PUMPCOMM, "YpsoPumpDummyConnector: connectToPump - not implemented (no connector).")
        return false
    }

    override fun disconnectFromPump() {
        aapsLogger.warn(LTag.PUMPCOMM, "YpsoPumpDummyConnector: disconnectFromPump - not implemented (no connector).")
    }

    override fun retrieveFirmwareVersion(): YpsoPumpFirmware? {
        aapsLogger.warn(LTag.PUMPCOMM, "YpsoPumpDummyConnector: retrieveFirmwareVersion - not implemented (no connector).")
        return null
    }

    // ---- delivery commands: must always fail ----

    override fun sendBolus(detailedBolusInfo: DetailedBolusInfo): CommandResponse = notImplemented("sendBolus")

    override fun sendTemporaryBasal(value: Int, duration: Int): CommandResponse = notImplemented("sendTemporaryBasal")

    override fun cancelTemporaryBasal(): CommandResponse = notImplemented("cancelTemporaryBasal")

    override fun sendBasalProfile(profile: Profile): CommandResponse = notImplemented("sendBasalProfile")

    // ---- read commands: nothing to read ----

    override fun retrieveTemporaryBasal(): TemporaryBasalResponse? {
        aapsLogger.warn(LTag.PUMPCOMM, "YpsoPumpDummyConnector: retrieveTemporaryBasal - not implemented (no connector).")
        return null
    }

    override fun retrieveBasalProfile(): BasalProfileResponse? {
        aapsLogger.warn(LTag.PUMPCOMM, "YpsoPumpDummyConnector: retrieveBasalProfile - not implemented (no connector).")
        return null
    }

    override fun getPumpHistory() {
        aapsLogger.warn(LTag.PUMPCOMM, "YpsoPumpDummyConnector: getPumpHistory - not implemented (no connector).")
    }

    init {
        pumpStatus = ypsopumpUtil.ypsopumpPumpStatus
    }
}
