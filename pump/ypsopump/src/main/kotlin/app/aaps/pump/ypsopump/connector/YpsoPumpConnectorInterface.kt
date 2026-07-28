package app.aaps.pump.ypsopump.connector

import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.profile.Profile
import app.aaps.pump.ypsopump.comm.command.response.BasalProfileResponse
import app.aaps.pump.ypsopump.comm.command.response.CommandResponse
import app.aaps.pump.ypsopump.comm.command.response.MapDataCommandResponse
import app.aaps.pump.ypsopump.comm.command.response.SimpleDataCommandResponse
import app.aaps.pump.ypsopump.comm.command.response.TemporaryBasalResponse
import app.aaps.pump.ypsopump.defs.YpsoPumpFirmware

interface YpsoPumpConnectorInterface {

    fun connectToPump(): Boolean
    fun disconnectFromPump()
    fun retrieveFirmwareVersion(): YpsoPumpFirmware?
    fun sendBolus(detailedBolusInfo: DetailedBolusInfo): CommandResponse?
    fun retrieveTemporaryBasal(): TemporaryBasalResponse?
    fun sendTemporaryBasal(value: Int, duration: Int): CommandResponse?
    fun cancelTemporaryBasal(): CommandResponse?
    fun retrieveBasalProfile(): BasalProfileResponse?
    fun sendBasalProfile(profile: Profile): CommandResponse?
    fun retrieveRemainingInsulin(): SimpleDataCommandResponse?
    fun retrieveConfiguration(): MapDataCommandResponse?
    fun retrieveBatteryStatus(): SimpleDataCommandResponse?
    fun getPumpHistory(): Unit
}