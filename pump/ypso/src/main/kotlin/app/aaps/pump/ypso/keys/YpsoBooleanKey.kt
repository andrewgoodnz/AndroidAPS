package app.aaps.pump.ypso.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.ypso.R

enum class YpsoBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    private val titleResId: Int,
    private val summaryResId: Int? = null
) : BooleanPreferenceKey {

    /** Nano ("tiny") bolus mode, as configured on the pump. Affects bolus step once delivery lands. */
    YpsoNanoBolus(
        key = "ypso_nano_bolus",
        defaultValue = true,
        titleResId = R.string.ypso_nano_bolus_title,
        summaryResId = R.string.ypso_nano_bolus_summary
    ),

    /**
     * Use the auth salt from Andy Rozman's 2022 driver instead of the one in vicktor's SDK.
     * The two differ; if the pump refuses to answer reads right after a seemingly successful
     * auth write, this is the first thing to flip. See PORTING_NOTES.md.
     */
    YpsoLegacyAuthSalt(
        key = "ypso_legacy_auth_salt",
        defaultValue = false,
        titleResId = R.string.ypso_legacy_auth_salt_title,
        summaryResId = R.string.ypso_legacy_auth_salt_summary
    ),

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
    YpsoSyncHistoryToAaps(
        key = "ypso_sync_history_to_aaps",
        defaultValue = true,
        titleResId = R.string.ypso_sync_history_title,
        summaryResId = R.string.ypso_sync_history_summary
    ),

    YpsoLogInsulinChange(
        key = "ypso_log_insulin_change",
        defaultValue = true,
        titleResId = R.string.ypso_log_insulin_change_title,
        summaryResId = R.string.ypso_log_insulin_change_summary
    ),

    YpsoLogSiteChange(
        key = "ypso_log_site_change",
        defaultValue = true,
        titleResId = R.string.ypso_log_site_change_title,
        summaryResId = R.string.ypso_log_site_change_summary
    ),

    YpsoLogTubeChange(
        key = "ypso_log_tube_change",
        defaultValue = true,
        titleResId = R.string.ypso_log_tube_change_title,
        summaryResId = R.string.ypso_log_tube_change_summary
    ),

    YpsoLogBatteryChange(
        key = "ypso_log_battery_change",
        defaultValue = true,
        titleResId = R.string.ypso_log_battery_change_title,
        summaryResId = R.string.ypso_log_battery_change_summary
    ),
    ;

    override val title: TextRef = TextRef.AndroidRes(titleResId)
    override val summary: TextRef? = summaryResId?.let { TextRef.AndroidRes(it) }
}
