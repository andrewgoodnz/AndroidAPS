package app.aaps.pump.ypsopump.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey

/**
 * Replaces the `@StringRes`-keyed [app.aaps.pump.ypsopump.util.YpsoPumpConst].Prefs entries.
 * Key strings are kept identical to Andy's `res/values/strings.xml` so an existing install keeps
 * its stored values.
 */
enum class YpsopumpStringPreferenceKey(
    override val key: String,
    override val defaultValue: String,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val isPassword: Boolean = false,
    override val isPin: Boolean = false,
    override val exportable: Boolean = true
) : StringPreferenceKey {

    Serial("pref_ypsopump_serial", "00000000"),
    Address("pref_ypsopump_address", ""),
    Name("pref_ypsopump_name", ""),
    BolusSize("pref_ypsopump_bolus_size", "0.1"),
}
