package app.aaps.pump.ypso.keys

import app.aaps.core.keys.interfaces.LongNonPreferenceKey

enum class YpsoLongNonKey(
    override val key: String,
    override val defaultValue: Long,
    // Pump runtime state and pump identity, not a user setting. Kept OUT of a settings export:
    // these are facts about one physical pump, and carrying them into another phone's import
    // would hand it a MAC address, a serial and a stale reservoir reading for a pump it is not
    // paired to. The driver re-reads every one of them from the pump on connection.
    override val exportable: Boolean = false
) : LongNonPreferenceKey {

    LastConnection("ypso_last_connection", 0L),
    LastBolusTime("ypso_last_bolus_time", 0L),

    /**
     * Highest pump sequence number already handed to `PumpSync`, or -1 when no baseline has been
     * taken yet.
     *
     * -1 is not "sync everything from the beginning" — it is "decide where to start". The first run
     * with history sync on records the pump's *current* newest sequence here and imports nothing, so
     * switching the feature on cannot conjure hours of historic boluses into IOB. Same shape as
     * `LastAlertCount`, and for the same reason.
     */
    LastSyncedSequence("ypso_last_synced_sequence", -1L),

}
