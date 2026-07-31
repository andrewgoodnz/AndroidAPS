package com.ypsopump.sdk

import com.google.common.truth.Truth.assertThat
import com.pump.common.BolusType
import com.pump.common.PumpHistoryEvent
import org.junit.jupiter.api.Test

/**
 * The rule that decides **what becomes a treatment in AAPS**.
 *
 * This is the last gate before pump history turns into insulin on board, and getting it wrong
 * corrupts IOB silently and persistently rather than visibly — which is why the sync it feeds was
 * gated off for three sessions. It is pure so that it can be pinned here rather than only on
 * hardware.
 */
class HistoryReadResultTest {

    private fun bolus(sequence: Long, amount: Double = 1.0, ts: Long = 1_785_400_000_000L) =
        PumpHistoryEvent.BolusDelivered(amount, BolusType.STANDARD, ts, sequence)

    private fun result(
        events: List<PumpHistoryEvent>,
        sequencesRead: List<Long> = events.map { it.position }
    ) = YpsoPumpManagerImpl.HistoryReadResult(events, emptyList(), sequencesRead)

    @Test
    fun `only entries above the watermark are fresh`() {
        val fresh = result(listOf(bolus(100), bolus(101), bolus(102))).freshEvents(watermark = 100)

        assertThat(fresh.map { it.position }).containsExactly(101L, 102L).inOrder()
    }

    /**
     * ⚠ Ordered by **sequence**, never by timestamp.
     *
     * A clock change makes the pump's timestamps go backwards — a type 12 entry carries the time
     * from *before* the change, and one real entry reads 2022 (§5). Sorting by time would then
     * replay history in an order the pump never lived, and `readHistoryWithDump` hands its events
     * over sorted by timestamp, so the re-sort here is doing real work.
     */
    @Test
    fun `fresh entries come back in sequence order even when timestamps disagree`() {
        val out = result(
            listOf(
                bolus(102, ts = 1_000_000_000_000L), // a clock change put this one in the past
                bolus(101, ts = 1_785_400_000_000L),
                bolus(103, ts = 1_785_400_500_000L)
            )
        ).freshEvents(watermark = 100)

        assertThat(out.map { it.position }).containsExactly(101L, 102L, 103L).inOrder()
    }

    /**
     * ⚠ A bolus this driver delivered is already recorded at the **phone's** timestamp, which is
     * deliberate (§5) and better evidence than the pump's entry — the pump's time is only as good as
     * its clock. `syncBolusWithPumpId` upserts on `pumpId` and *updates the timestamp*, so re-syncing
     * one would silently move the treatment onto pump time, undoing the fix for a bug that put
     * boluses 12 hours into the future.
     */
    @Test
    fun `entries the driver recorded itself are excluded, not merely deduplicated`() {
        val fresh = result(listOf(bolus(101), bolus(102), bolus(103)))
            .freshEvents(watermark = 100, alreadyRecorded = setOf(102L))

        assertThat(fresh.map { it.position }).containsExactly(101L, 103L).inOrder()
    }

    @Test
    fun `nothing is fresh when the watermark is already at the newest entry`() {
        assertThat(result(listOf(bolus(101), bolus(102))).freshEvents(watermark = 102)).isEmpty()
    }

    /**
     * The watermark has to come from every sequence read, not from the mapped events. Clock changes,
     * daily totals and unrecognised types map to nothing, so the highest *mapped* sequence can sit
     * below the highest sequence that exists — and a watermark taken from it would re-read the same
     * entries for ever.
     */
    @Test
    fun `newest sequence counts entries that mapped to nothing`() {
        val read = result(events = listOf(bolus(101)), sequencesRead = listOf(101L, 102L, 103L))

        assertThat(read.newestSequence).isEqualTo(103L)
        assertThat(read.oldestSequence).isEqualTo(101L)
    }

    @Test
    fun `a contiguous read reports no gap`() {
        val read = result(events = emptyList(), sequencesRead = listOf(101L, 102L, 103L))

        assertThat(read.missedSince(watermark = 100)).isEqualTo(0)
    }

    /**
     * ⚠ The case that produces missing treatments. The pump kept working while AAPS was away, the
     * read window did not reach back far enough, and the entries in between are gone for good. A
     * single basal profile write appends 24 entries, so a small window opens one of these quickly.
     */
    @Test
    fun `entries below the oldest one read are reported as missed`() {
        val read = result(events = emptyList(), sequencesRead = listOf(150L, 151L, 152L))

        assertThat(read.missedSince(watermark = 100)).isEqualTo(49)
    }

    @Test
    fun `a read reaching below the watermark reports no gap`() {
        val read = result(events = emptyList(), sequencesRead = listOf(90L, 95L, 101L))

        assertThat(read.missedSince(watermark = 100)).isEqualTo(0)
    }

    @Test
    fun `an empty read has no sequences and claims no gap`() {
        val read = result(events = emptyList(), sequencesRead = emptyList())

        assertThat(read.newestSequence).isNull()
        assertThat(read.missedSince(watermark = 100)).isEqualTo(0)
    }
}
