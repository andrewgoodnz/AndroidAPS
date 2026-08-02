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
     * Write pump history into AAPS treatment records — above all a bolus given on the pump's own
     * menu, which nothing else here would ever see.
     *
     * **This comment used to say the pumpId was the history slot index and that its stability was
     * unconfirmed. Both are out of date.** `pumpId` carries the pump's own **sequence number**
     * (§5) precisely because the slot index *is* a reused ring-buffer position — index 0 is the
     * newest entry, so every new event renumbers every older one. Using it would have merged or
     * rejected boluses silently.
     *
     * Validated on hardware 2026-08-02, `SALINE_TEST_PROCEDURE.md` Phase 7: the first enable takes
     * a baseline and imports nothing, a manual bolus arrives exactly once, an AAPS bolus stays one
     * record across re-reads, and a 24-entry profile burst reaches no treatment.
     *
     * **On by default since 2026-08-02**, once Phase 7 passed in full — 7e ran four idle hours and
     * produced fourteen empty syncs and not one row in any treatment table — and Phase 7f validated
     * the probe that keeps the cost survivable. Without that probe this would add ~20 s to every
     * status read; with it, ~0.6 s on the polls where nothing happened, which is most of them
     * (§8.5).
     *
     * It stays a preference so it can be switched off in one action, which is what the procedure
     * asks for if anything downstream of it ever looks wrong.
     */
    YpsoSyncHistoryToAaps("ypso_sync_history_to_aaps", true),

    YpsoLogInsulinChange("ypso_log_insulin_change", true),
    YpsoLogSiteChange("ypso_log_site_change", true),
    YpsoLogTubeChange("ypso_log_tube_change", true),
    YpsoLogBatteryChange("ypso_log_battery_change", true),
}
