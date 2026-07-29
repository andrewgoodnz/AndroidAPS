package app.aaps.pump.ypso.keys

import app.aaps.core.keys.interfaces.LongNonPreferenceKey

enum class YpsoLongNonKey(
    override val key: String,
    override val defaultValue: Long,
    override val exportable: Boolean = true
) : LongNonPreferenceKey {

    LastConnection("ypso_last_connection", 0L),
    LastBolusTime("ypso_last_bolus_time", 0L),
}
