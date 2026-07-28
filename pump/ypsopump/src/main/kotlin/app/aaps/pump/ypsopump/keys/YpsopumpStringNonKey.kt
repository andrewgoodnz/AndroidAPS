package app.aaps.pump.ypsopump.keys

import app.aaps.core.keys.interfaces.StringNonPreferenceKey

/**
 * Internal driver state persisted as JSON, not a user-facing preference.
 */
enum class YpsopumpStringNonKey(
    override val key: String,
    override val defaultValue: String,
    override val exportable: Boolean = true
) : StringNonPreferenceKey {

    PumpStatusList("pref_ypsopump_status_list", ""),
}
