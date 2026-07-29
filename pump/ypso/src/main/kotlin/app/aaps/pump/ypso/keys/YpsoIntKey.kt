package app.aaps.pump.ypso.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey

enum class YpsoIntKey(
    override val key: String,
    override val defaultValue: Int,
    override val min: Int,
    override val max: Int,
    override val calculatedDefaultValue: Boolean = false,
    override val engineeringModeOnly: Boolean = false,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true
) : IntPreferenceKey {

    /** Devices weaker than this are hidden by the pairing scan. -99 comes from the Lotus export. */
    YpsoRssiThreshold("ypso_rssi_threshold", -99, -127, 0),

    /** How many of the most recent entries to pull from each pump history log. */
    YpsoHistoryReadCount("ypso_history_read_count", 50, 1, 500),
}
