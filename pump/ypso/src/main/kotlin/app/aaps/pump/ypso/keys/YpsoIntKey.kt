package app.aaps.pump.ypso.keys

import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.ypso.R

enum class YpsoIntKey(
    override val key: String,
    override val defaultValue: Int,
    override val min: Int,
    override val max: Int,
    private val titleResId: Int,
    private val summaryResId: Int? = null
) : IntPreferenceKey {

    /** Devices weaker than this are hidden by the pairing scan. -99 comes from the Lotus export. */
    YpsoRssiThreshold(
        key = "ypso_rssi_threshold",
        defaultValue = -99,
        min = -127,
        max = 0,
        titleResId = R.string.ypso_rssi_threshold_title,
        summaryResId = R.string.ypso_rssi_threshold_summary
    ),

    /** How many of the most recent entries to pull from each pump history log. */
    YpsoHistoryReadCount(
        key = "ypso_history_read_count",
        defaultValue = 50,
        min = 1,
        max = 500,
        titleResId = R.string.ypso_history_read_count_title,
        summaryResId = R.string.ypso_history_read_count_summary
    ),
    ;

    override val title: TextRef = TextRef.AndroidRes(titleResId)
    override val summary: TextRef? = summaryResId?.let { TextRef.AndroidRes(it) }
}
