package app.aaps.pump.ypso.keys

import app.aaps.core.keys.interfaces.IntNonPreferenceKey

enum class YpsoIntNonKey(
    override val key: String,
    override val defaultValue: Int,
    override val exportable: Boolean = true
) : IntNonPreferenceKey {

    BatteryLevel("ypso_battery_level", 0),

    /** Active basal program: 0 = A, 1 = B, -1 = not read yet. */
    ActiveProfileId("ypso_active_profile_id", -1),
}
