package app.aaps.pump.ypsopump.handlers

import app.aaps.pump.ypsopump.comm.data.YpsoPumpStatusEntry
import app.aaps.pump.ypsopump.comm.data.YpsoPumpStatusList
import app.aaps.pump.ypsopump.data.HistoryEntryType
import app.aaps.pump.ypsopump.database.HistoryRecordEntity
import app.aaps.pump.ypsopump.database.YpsoPumpHistory
import app.aaps.pump.ypsopump.driver.YpsopumpPumpStatus
import app.aaps.pump.ypsopump.keys.YpsopumpStringNonKey
import app.aaps.pump.ypsopump.keys.YpsopumpStringPreferenceKey
import app.aaps.pump.ypsopump.util.YpsoPumpUtil
import app.aaps.core.keys.interfaces.Preferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YpsoPumpStatusHandler
@Inject constructor(var preferences: Preferences,
                    var ypsoPumpUtil: YpsoPumpUtil,
                    var ypsoPumpHistory: YpsoPumpHistory,
                    var pumpStatus: YpsopumpPumpStatus) {

    fun loadYpsoPumpStatusList() {

        val jsonListValue = preferences.getIfExists(YpsopumpStringNonKey.PumpStatusList)

        if (!jsonListValue.isNullOrBlank()) {
            val fromJson = ypsoPumpUtil.gson.fromJson(jsonListValue, YpsoPumpStatusList::class.java)
            pumpStatus.ypsoPumpStatusList = fromJson
        }

        if (pumpStatus.ypsoPumpStatusList == null) {
            pumpStatus.ypsoPumpStatusList = YpsoPumpStatusList(map = mutableMapOf())
        }

        val serialNumber = preferences.get(YpsopumpStringPreferenceKey.Serial).toLongOrNull() ?: 0L

        if (serialNumber != 0L) {
            if (!pumpStatus.ypsoPumpStatusList!!.map.containsKey(serialNumber)) {
                Thread {
                    generateNewPumpStatusEntry(serialNumber)
                }.start()
            }
            pumpStatus.serialNumber = serialNumber

            // TODO remove this, just for database testing
            val ypsoPumpStatusEntry = pumpStatus.ypsoPumpStatusList!!.map[serialNumber]
            ypsoPumpStatusEntry!!.lastSystemEntrySequenceNumber = 0
        }
    }

    private fun generateNewPumpStatusEntry(serialNumber: Long) {
        var ypsoPumpStatusEntry: YpsoPumpStatusEntry = YpsoPumpStatusEntry(serialNumber)

        var entry = getLatestEntryFromDatabase(serialNumber, HistoryEntryType.Event);

        if (entry != null) {
            ypsoPumpStatusEntry.lastEventSequenceNumber = entry.eventSequenceNumber
            //ypsoPumpStatusEntry.lastEventDate = entry.date
        } else {
            ypsoPumpStatusEntry.lastEventSequenceNumber = 0
            //ypsoPumpStatusEntry.lastEventDate = 0
        }

        entry = getLatestEntryFromDatabase(serialNumber, HistoryEntryType.Alarm);

        if (entry != null) {
            ypsoPumpStatusEntry.lastAlarmSequenceNumber = entry.eventSequenceNumber
            //ypsoPumpStatusEntry.lastAlarmDate = entry.date
        } else {
            ypsoPumpStatusEntry.lastAlarmSequenceNumber = 0
            //ypsoPumpStatusEntry.lastAlarmDate = 0
        }

        entry = getLatestEntryFromDatabase(serialNumber, HistoryEntryType.SystemEntry);

        if (entry != null) {
            ypsoPumpStatusEntry.lastSystemEntrySequenceNumber = entry.eventSequenceNumber
            //ypsoPumpStatusEntry.lastSystemEntryDate = entry.date
        } else {
            ypsoPumpStatusEntry.lastSystemEntrySequenceNumber = 0
            //ypsoPumpStatusEntry.lastSystemEntryDate = 0
        }

        pumpStatus.ypsoPumpStatusList!!.map.put(serialNumber, ypsoPumpStatusEntry)

        saveYpsoPumpStatusList()
    }

    private fun getLatestEntryFromDatabase(serialNumber: Long, entryType: HistoryEntryType): HistoryRecordEntity? {
        return ypsoPumpHistory.getLatestHistoryEntry(serialNumber, entryType)
    }

    fun switchPumpData() {
        val serialNumber = preferences.get(YpsopumpStringPreferenceKey.Serial).toLongOrNull() ?: 0L

        if (serialNumber == 0L) {// this would only happen if user removed his pump, and haven't set the new one
            pumpStatus.serialNumber = null
            return
        }

        pumpStatus.serialNumber = serialNumber

        if (pumpStatus.ypsoPumpStatusList == null) {
            pumpStatus.ypsoPumpStatusList = YpsoPumpStatusList(map = mutableMapOf())
            generateNewPumpStatusEntry(serialNumber)
        } else if (!pumpStatus.ypsoPumpStatusList!!.map.containsKey(serialNumber)) {
            generateNewPumpStatusEntry(serialNumber)
        }
    }

    fun saveYpsoPumpStatusList() {
        preferences.put(YpsopumpStringNonKey.PumpStatusList, ypsoPumpUtil.gson.toJson(pumpStatus.ypsoPumpStatusList))
    }

}