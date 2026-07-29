package app.aaps.pump.ypso.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey

enum class YpsoBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
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
) : BooleanPreferenceKey {

    /** Nano ("tiny") bolus mode, as configured on the pump. Affects bolus step once delivery lands. */
    YpsoNanoBolus("ypso_nano_bolus", true),

    /**
     * Use the auth salt from Andy Rozman's 2022 driver instead of the one in vicktor's SDK.
     * The two differ; if the pump refuses to answer reads right after a seemingly successful
     * auth write, this is the first thing to flip. See PORTING_NOTES.md.
     */
    YpsoLegacyAuthSalt("ypso_legacy_auth_salt", false),

    /**
     * Write pump history into AAPS treatment records.
     *
     * Off by default, deliberately: the pumpId used for de-duplication is the pump's history
     * slot index, and it is not yet confirmed on hardware that those indices are stable rather
     * than reused ring-buffer slots. Turning this on before that is settled risks duplicated or
     * silently-merged boluses, which is an IOB error. Read the history first, compare the dump
     * against the pump screen, then enable.
     */
    YpsoSyncHistoryToAaps("ypso_sync_history_to_aaps", false),

    YpsoLogInsulinChange("ypso_log_insulin_change", true),
    YpsoLogSiteChange("ypso_log_site_change", true),
    YpsoLogTubeChange("ypso_log_tube_change", true),
    YpsoLogBatteryChange("ypso_log_battery_change", true),
}
