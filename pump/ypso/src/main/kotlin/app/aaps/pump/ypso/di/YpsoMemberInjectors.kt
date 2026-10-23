package app.aaps.pump.ypso.di

import app.aaps.core.interfaces.di.FeatureMemberInjectors
import app.aaps.pump.ypso.services.YpsoService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.MembersInjector
import dev.zacsweers.metro.Provides

/**
 * Member injector for [YpsoService] - the `@ContributesAndroidInjector` replacement.
 *
 * Android builds the service itself, so it fills its own fields from this map afterwards.
 * [FeatureMemberInjectors] keeps the entry out of the map each graph extension declares; without it
 * they are the same multibinding and turn up everywhere.
 *
 * The old Dagger module also contributed the overview fragment and the BLE scan activity. Neither
 * exists now: the fragment became [app.aaps.pump.ypso.compose.YpsoOverviewViewModel] plus the
 * shared pump overview screen, and the scan activity is replaced by the shared Compose BLE scan.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object YpsoMemberInjectors {

    @Provides
    @FeatureMemberInjectors
    @IntoMap
    @ClassKey(YpsoService::class)
    fun bindYpsoService(injector: MembersInjector<YpsoService>): MembersInjector<*> = injector
}
