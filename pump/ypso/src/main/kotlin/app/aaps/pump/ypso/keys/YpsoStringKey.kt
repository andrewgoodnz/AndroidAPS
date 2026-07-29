package app.aaps.pump.ypso.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey

enum class YpsoStringKey(
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

    /**
     * Bolus speed, as the pump's own index. `1` = 12 U/min (1 U per 5 s), which is what the
     * decrypted mylife Lotus export had configured. Read-only for now; it becomes load-bearing
     * when bolus progress and completion timeouts are implemented.
     */
    YpsoBolusSpeed("ypso_bolus_speed", "1"),
}
