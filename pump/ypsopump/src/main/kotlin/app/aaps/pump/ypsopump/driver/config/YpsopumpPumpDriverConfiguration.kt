package app.aaps.pump.ypsopump.driver.config

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.pump.common.driver.PumpDriverConfiguration
import app.aaps.pump.common.driver.ble.PumpBLESelector
import app.aaps.pump.common.driver.db.PumpDriverDatabaseOperation
import app.aaps.pump.common.driver.history.PumpHistoryDataProvider
import javax.inject.Inject

class YpsopumpPumpDriverConfiguration @Inject constructor(
    var pumpBLESelector: YpsoPumpBLESelector,
    var pumpHistoryDataProvider: YpsopumpPumpHistoryDataProvider
) : PumpDriverConfiguration {

    override fun getPumpBLESelector(): PumpBLESelector = pumpBLESelector

    override fun getPumpHistoryDataProvider(): PumpHistoryDataProvider = pumpHistoryDataProvider

    /**
     * DEFERRED: upstream added this to [PumpDriverConfiguration] but ships no real implementation
     * (Medtronic's also throws). ypsopump keeps Andy's own Room history DB for now, so nothing
     * calls this. See PORTING_NOTES.md.
     */
    override fun getPumpDriverDatabaseOperation(): PumpDriverDatabaseOperation =
        throw NotImplementedError("ypsopump uses its own Room history DB - see PORTING_NOTES.md")

    override fun getPumpType(): PumpType = PumpType.YPSOPUMP

    override var logPrefix: String = "YpsopumpPumpPlugin::"
    override var canHandleDST: Boolean = false
    override var hasService: Boolean = false
}
