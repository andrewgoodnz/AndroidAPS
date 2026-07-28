package app.aaps.pump.ypsopump.data

class HistoryProcessingDto constructor(fullEvents: MutableList<EventDto>) {

    var basalEntries: MutableList<EventDto>? = null
    var endListOfEvents: MutableList<EventDto>? = null
}