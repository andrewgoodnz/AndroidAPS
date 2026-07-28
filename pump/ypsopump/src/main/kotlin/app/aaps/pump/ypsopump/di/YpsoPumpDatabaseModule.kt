package app.aaps.pump.ypsopump.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.Reusable
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.ypsopump.database.HistoryMapper
import app.aaps.pump.ypsopump.database.HistoryRecordDao
import app.aaps.pump.ypsopump.database.YpsoPumpDatabase
import app.aaps.pump.ypsopump.database.YpsoPumpHistory
import app.aaps.pump.ypsopump.driver.YpsopumpPumpStatus
import app.aaps.pump.ypsopump.util.YpsoPumpUtil
import app.aaps.core.interfaces.logging.AAPSLogger
import javax.inject.Singleton

@Module
@Suppress("unused")
class YpsoPumpDatabaseModule {

    @Provides
    @Singleton
    internal fun provideDatabase(context: Context): YpsoPumpDatabase = YpsoPumpDatabase.build(context)

    @Provides
    @Singleton
    internal fun provideHistoryRecordDao(historyDatabase: YpsoPumpDatabase): HistoryRecordDao = historyDatabase.historyRecordDao()

    @Provides
    @Reusable // no state, let system decide when to reuse or create new.
    internal fun provideHistoryMapper(ypsoPumpUtil: YpsoPumpUtil, aapsLogger: AAPSLogger) = HistoryMapper(ypsoPumpUtil, aapsLogger)

    @Provides
    @Singleton
    internal fun provideYpsoPumpHistory(
        dao: HistoryRecordDao,
        pumpHistoryDatabase: YpsoPumpDatabase,
        historyMapper: HistoryMapper,
        pumpSync: PumpSync,
        ypsoPumpUtil: YpsoPumpUtil,
        pumpStatus: YpsopumpPumpStatus,
        aapsLogger: AAPSLogger
    ) =
        YpsoPumpHistory(dao, pumpHistoryDatabase, historyMapper, pumpSync, ypsoPumpUtil, pumpStatus, aapsLogger)

}