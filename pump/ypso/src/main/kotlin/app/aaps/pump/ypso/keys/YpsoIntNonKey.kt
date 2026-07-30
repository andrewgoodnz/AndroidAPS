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

    /**
     * Number of entries in the pump's alert log at the last status read; -1 = never read.
     *
     * Persisted so a restart does not re-announce alarms the user has already been told about,
     * and so the first read after an install establishes a baseline instead of firing for every
     * occlusion in the pump's two-year history.
     */
    LastAlertCount("ypso_last_alert_count", -1),
}
