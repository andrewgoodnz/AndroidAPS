package app.aaps.pump.ypsopump.connector

import dagger.android.HasAndroidInjector
import app.aaps.pump.ypsopump.util.YpsoPumpUtil
import app.aaps.core.interfaces.logging.AAPSLogger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@Deprecated("Probably wont be used")
class YpsoPump15Connector @Inject constructor(pumpUtil: YpsoPumpUtil,
                                              injector: HasAndroidInjector,
                                              aapsLogger: AAPSLogger) : YpsoPumpConnectorAbstract(pumpUtil, injector, aapsLogger) {






}