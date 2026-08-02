package com.ypsopump.sdk

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The probe decides whether the driver spends twenty seconds of radio time or four hundred
 * milliseconds, and — in one branch — whether a silent loss of treatments is announced.
 *
 * Every branch is pinned, including the two hardware will not produce to order: a log wrap, and
 * the boundary where the newest entry is exactly the watermark.
 */
class HistorySyncDecisionTest {

    @Test
    fun `no watermark takes a baseline, whatever the pump says`() {
        assertThat(HistorySyncDecision.decide(watermark = -1, newestSeen = 160658))
            .isEqualTo(HistorySyncDecision.TakeBaseline)
        assertThat(HistorySyncDecision.decide(watermark = -1, newestSeen = 0))
            .isEqualTo(HistorySyncDecision.TakeBaseline)
    }

    @Test
    fun `newest equal to the watermark is up to date`() {
        // The real quiet case: 13 of 17 connections on 2026-08-02. Skipping here has to be exactly
        // equivalent to reading, not merely usually right — freshEvents would return empty and
        // missedSince would return 0 from its own newest <= watermark guard in each window.
        assertThat(HistorySyncDecision.decide(watermark = 160710, newestSeen = 160710))
            .isEqualTo(HistorySyncDecision.UpToDate)
    }

    @Test
    fun `one sequence above the watermark is enough to read the window`() {
        assertThat(HistorySyncDecision.decide(watermark = 160710, newestSeen = 160711))
            .isEqualTo(HistorySyncDecision.ReadFully)
    }

    @Test
    fun `the 24-entry burst from a profile write reads the window`() {
        assertThat(HistorySyncDecision.decide(watermark = 160662, newestSeen = 160686))
            .isEqualTo(HistorySyncDecision.ReadFully)
    }

    @Test
    fun `a sequence below the watermark is a wrap, not a quiet pump`() {
        // The failure this exists to make loud. Before the probe, this case filtered to nothing,
        // imported nothing and silently reset the watermark to the lower value — losing up to a
        // window of treatments with no gap warning, because the gap arithmetic assumes a
        // monotonic counter. Missing boluses under-state IOB, which is the stacking direction.
        assertThat(HistorySyncDecision.decide(watermark = 160710, newestSeen = 1))
            .isEqualTo(HistorySyncDecision.SequenceWentBackwards)
        assertThat(HistorySyncDecision.decide(watermark = 160710, newestSeen = 160709))
            .isEqualTo(HistorySyncDecision.SequenceWentBackwards)
    }

    @Test
    fun `a watermark of zero is a real watermark, not an unset one`() {
        // Only a negative watermark means "no baseline yet". Zero is a legitimate sequence, and
        // treating it as unset would silently re-baseline a pump whose log genuinely starts there.
        assertThat(HistorySyncDecision.decide(watermark = 0, newestSeen = 0))
            .isEqualTo(HistorySyncDecision.UpToDate)
        assertThat(HistorySyncDecision.decide(watermark = 0, newestSeen = 1))
            .isEqualTo(HistorySyncDecision.ReadFully)
    }
}
