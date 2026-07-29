package app.aaps.pump.ypso.di

import app.aaps.pump.ypso.activities.YpsoBLEScanActivity
import app.aaps.pump.ypso.services.YpsoService
import app.aaps.pump.ypso.ui.YpsoOverviewFragment
import dagger.Module
import dagger.android.ContributesAndroidInjector

@Module
@Suppress("unused")
abstract class YpsoModule {

    @ContributesAndroidInjector abstract fun contributesYpsoOverviewFragment(): YpsoOverviewFragment

    @ContributesAndroidInjector abstract fun contributesYpsoBLEScanActivity(): YpsoBLEScanActivity

    @ContributesAndroidInjector abstract fun contributesYpsoService(): YpsoService
}
