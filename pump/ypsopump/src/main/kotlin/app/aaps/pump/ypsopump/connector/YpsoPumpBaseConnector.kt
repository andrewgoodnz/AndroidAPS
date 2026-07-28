package app.aaps.pump.ypsopump.connector

import dagger.android.HasAndroidInjector
import app.aaps.pump.ypsopump.util.YpsoPumpUtil
import app.aaps.core.interfaces.logging.AAPSLogger

class YpsoPumpBaseConnector(ypsopumpUtil: YpsoPumpUtil?, injector: HasAndroidInjector?, aapsLogger: AAPSLogger?) : YpsoPumpConnectorAbstract(ypsopumpUtil!!, injector!!, aapsLogger!!)