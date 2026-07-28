package app.aaps.pump.ypsopump.connector

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.pump.ypsopump.comm.command.response.BasalProfileResponse
import app.aaps.pump.ypsopump.comm.command.response.CommandResponse
import app.aaps.pump.ypsopump.comm.command.response.MapDataCommandResponse
import app.aaps.pump.ypsopump.comm.command.response.SimpleDataCommandResponse
import app.aaps.pump.ypsopump.comm.command.response.TemporaryBasalResponse
import app.aaps.pump.ypsopump.defs.YpsoPumpFirmware
import app.aaps.pump.ypsopump.util.YpsoPumpUtil
import dagger.android.HasAndroidInjector

/**
 * Base for every connector. All defaults are "not implemented" failures.
 *
 * SAFETY: Andy's original defaulted `connectToPump()` to `true` and `retrieveFirmwareVersion()` to
 * `VERSION_1_0` after a sleep. Both now fail/return null, so a connector that forgets to override a
 * method cannot silently look like a working pump.
 */
abstract class YpsoPumpConnectorAbstract(
    protected var pumpUtil: YpsoPumpUtil,
    protected var injector: HasAndroidInjector,
    protected var aapsLogger: AAPSLogger
) : YpsoPumpConnectorInterface {

    protected val unSuccessfulResponse: CommandResponse
        get() = CommandResponse.builder().success(false).errorDescription("Command not implemented.").build()

    override fun connectToPump(): Boolean = false

    override fun disconnectFromPump() {}

    override fun retrieveFirmwareVersion(): YpsoPumpFirmware? = null

    override fun sendBolus(detailedBolusInfo: DetailedBolusInfo): CommandResponse? = unSuccessfulResponse

    override fun retrieveTemporaryBasal(): TemporaryBasalResponse? = null

    override fun sendTemporaryBasal(value: Int, duration: Int): CommandResponse? = unSuccessfulResponse

    override fun cancelTemporaryBasal(): CommandResponse? = unSuccessfulResponse

    override fun retrieveBasalProfile(): BasalProfileResponse? = null

    override fun sendBasalProfile(profile: Profile): CommandResponse? = unSuccessfulResponse

    override fun retrieveConfiguration(): MapDataCommandResponse =
        MapDataCommandResponse.builder().success(false).errorDescription("Command not implemented.").build()

    override fun retrieveRemainingInsulin(): SimpleDataCommandResponse? =
        SimpleDataCommandResponse.builder().success(false).errorDescription("Command not implemented.").build()

    override fun retrieveBatteryStatus(): SimpleDataCommandResponse? =
        SimpleDataCommandResponse.builder().success(false).errorDescription("Command not implemented.").build()

    override fun getPumpHistory() {}
}
