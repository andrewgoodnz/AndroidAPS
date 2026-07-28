package app.aaps.pump.ypsopump.keys

import app.aaps.core.keys.interfaces.LongNonPreferenceKey

/**
 * Replaces [app.aaps.pump.ypsopump.util.YpsoPumpConst].Statistics, which used raw SP string keys.
 */
enum class YpsopumpLongNonKey(
    override val key: String,
    override val defaultValue: Long,
    override val exportable: Boolean = true
) : LongNonPreferenceKey {

    FirstPumpStart("AAPS.YpsoPump.first_pump_use", 0L),
    LastGoodPumpCommunicationTime("AAPS.YpsoPump.lastGoodPumpCommunicationTime", 0L),
    TbrsSet("ypsopump_tbrs_set", 0L),
    StandardBoluses("ypsopump_std_boluses_delivered", 0L),
    SmbBoluses("ypsopump_smb_boluses_delivered", 0L),
}
