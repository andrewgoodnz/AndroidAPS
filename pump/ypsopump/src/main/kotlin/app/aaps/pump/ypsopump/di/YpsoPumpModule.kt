package app.aaps.pump.ypsopump.di

import app.aaps.pump.ypsopump.YpsoPumpFragment
import app.aaps.pump.ypsopump.comm.YpsoPumpConnectionManager
import app.aaps.pump.ypsopump.comm.YpsoPumpDataConverter
import app.aaps.pump.ypsopump.driver.YpsopumpPumpStatus
import app.aaps.pump.ypsopump.driver.config.YpsoPumpBLESelector
import app.aaps.pump.ypsopump.driver.config.YpsopumpPumpDriverConfiguration
import app.aaps.pump.ypsopump.driver.config.YpsopumpPumpHistoryDataProvider
import app.aaps.pump.ypsopump.handlers.YpsoPumpHistoryHandler
import app.aaps.pump.ypsopump.handlers.YpsoPumpStatusHandler
import app.aaps.pump.ypsopump.util.YpsoPumpUtil
import dagger.Module
import dagger.android.ContributesAndroidInjector

/**
 * FORWARD-PORT NOTE: Andy's version also bound his BLE command classes (GetFirmwareVersion,
 * GetBasalProfile, GetDateTime, GetPumpSettings, GetEvents, GetAlarms, GetSystemEntries,
 * GetLastEvent). That BLE stack is not part of this port - see PORTING_NOTES.md.
 */
@Module(includes = [YpsoPumpDatabaseModule::class])
@Suppress("unused")
abstract class YpsoPumpModule {

    // Driver basics
    @ContributesAndroidInjector abstract fun ypsopumpPumpStatus(): YpsopumpPumpStatus
    @ContributesAndroidInjector abstract fun contributeYpsoPumpUtil(): YpsoPumpUtil
    @ContributesAndroidInjector abstract fun contributeYpsoPumpStatusHandler(): YpsoPumpStatusHandler

    // Data
    @ContributesAndroidInjector abstract fun contributesYpsoPumpDataConverter(): YpsoPumpDataConverter
    @ContributesAndroidInjector abstract fun contributeYpsoPumpHistoryHandler(): YpsoPumpHistoryHandler

    // Communication Layer
    @ContributesAndroidInjector abstract fun ypsopumpConnectionManager(): YpsoPumpConnectionManager

    // Fragments
    @ContributesAndroidInjector abstract fun contributesYpsoPumpFragment(): YpsoPumpFragment

    // Configuration
    @ContributesAndroidInjector abstract fun contributesYpsoPumpBLESelector(): YpsoPumpBLESelector
    @ContributesAndroidInjector abstract fun contributesYpsoPumpHistoryDataProvider(): YpsopumpPumpHistoryDataProvider
    @ContributesAndroidInjector abstract fun contributesYpsopumpPumpDriverConfiguration(): YpsopumpPumpDriverConfiguration
}
