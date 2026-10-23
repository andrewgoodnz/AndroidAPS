package com.ypsopump.sdk

/**
 * What a history sync should do, decided from the watermark and one cheap read.
 *
 * Reading the pump's history is the slowest thing this driver does — ~200 ms an entry, and the
 * driver reads two windows, so a 50-entry setting costs ~20 s on **every** status read. Measured
 * on 2026-08-02 across five syncs: 19.9–21.4 s. At AAPS's idle cadence of one poll every 20
 * minutes that is merely wasteful; for a loop issuing an SMB every five minutes it is fatal,
 * because the read delays every command behind it.
 *
 * Almost all of that work answers a question the pump can answer far more cheaply. **Index 0 is
 * always the newest entry** (§5), so two entry reads — one per log — say whether anything has
 * happened at all. On the day this was written the pump logged nothing on 13 of 17 connections.
 *
 * Kept pure and separate from the service for the same reason [YpsoPumpManagerImpl.HistoryReadResult]
 * is: this decides whether pump history becomes insulin on board, and three of the four defects
 * recorded in `PORTING_NOTES` were checks that could not fail. A decision made in a function with
 * no BLE in it can be tested against every case, including the ones hardware will not produce on
 * demand.
 */
enum class HistorySyncDecision {

    /**
     * No watermark yet — record where to start and import nothing.
     *
     * The baseline can be taken from the probe alone: it is the newest sequence across both logs,
     * which is exactly what a full read would have yielded, and nothing is imported either way. So
     * a first enable costs ~0.4 s rather than ~21 s.
     */
    TakeBaseline,

    /**
     * The newest entry is one we have already seen. Skip the full read.
     *
     * **This is exactly equivalent to doing the full read**, not an approximation of it, which is
     * what makes it safe to skip. If the newest sequence in both logs is at or below the watermark
     * then `freshEvents` can only return empty, and `missedSince` returns 0 from its own
     * `newest <= watermark` guard in each window. Both answers are already determined; reading 100
     * entries to arrive at them is work with no output.
     */
    UpToDate,

    /**
     * Something above the watermark exists. Read the window properly.
     */
    ReadFully,

    /**
     * The pump's newest sequence is **below** the watermark, which should be impossible.
     *
     * Sequences on this pump are strictly monotonic across both logs (§5), so this means the state
     * underneath changed: the 3000-entry event log wrapped and restarted numbering, the driver is
     * talking to a different pump, or a preferences backup was restored over a newer database.
     *
     * The old code reached this case too and handled it silently — it filtered on
     * `position > watermark`, imported nothing, then reset the watermark to the lower value, losing
     * up to a window of entries with no gap warning, because the gap arithmetic is only meaningful
     * for a monotonic counter. Missing boluses under-state IOB, which is the stacking direction.
     *
     * The behaviour is deliberately unchanged — re-baseline and import nothing, because there is no
     * way to tell which entries are new — but it is now **loud**. Silently resuming from a lower
     * watermark is the failure mode nobody would notice.
     */
    SequenceWentBackwards;

    companion object {

        /**
         * @param watermark  highest sequence already handed to `PumpSync`, or negative if none.
         * @param newestSeen newest sequence across both logs, from the probe.
         */
        fun decide(watermark: Long, newestSeen: Long): HistorySyncDecision = when {
            watermark < 0          -> TakeBaseline
            newestSeen < watermark -> SequenceWentBackwards
            newestSeen == watermark -> UpToDate
            else                   -> ReadFully
        }
    }
}
