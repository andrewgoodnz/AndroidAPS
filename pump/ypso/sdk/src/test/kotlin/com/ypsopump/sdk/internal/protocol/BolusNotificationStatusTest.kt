package com.ypsopump.sdk.internal.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * When the driver is allowed to stop waiting for a bolus.
 *
 * This decides the instant the outcome is read out of the pump's event log, and the pump is still
 * revising that log while it finishes. Stopping early reads a half-written answer — see
 * `PORTING_NOTES` §4f, where doing so recorded 4.0 U against 2.49 U actually delivered.
 */
class BolusNotificationStatusTest {

    @Test
    fun `the two confirmed end states finish the wait`() {
        assertThat(BolusNotificationStatus.hasFinished(BolusNotificationStatus.COMPLETED)).isTrue()
        assertThat(BolusNotificationStatus.hasFinished(BolusNotificationStatus.CANCELLED)).isTrue()
    }

    /**
     * `IDLE` counts, but only because it is a *mapped* state that means nothing is running, and this
     * pump clears its bolus status when delivery ends (§4f). Both observed endings pass through it:
     * a completed bolus polls `DELIVERING → IDLE`, and a pump-side cancel polls `DELIVERING → 2 →
     * IDLE`. Without it the wait always ran to the timeout — correct, but up to a minute of it on a
     * large bolus cancelled early.
     */
    @Test
    fun `idle finishes the wait once delivery has been seen`() {
        assertThat(BolusNotificationStatus.hasFinished(BolusNotificationStatus.IDLE)).isTrue()
    }

    @Test
    fun `delivering does not finish the wait`() {
        assertThat(BolusNotificationStatus.hasFinished(BolusNotificationStatus.DELIVERING)).isFalse()
    }

    /**
     * ⚠ The regression this file exists for.
     *
     * The predicate was `status != IDLE && status != DELIVERING`, so every value nobody had ever
     * seen counted as "delivery finished". Cancelling a 4 U bolus on the pump produced status **2**,
     * which is in no table here, and the driver stopped waiting. The event-log entry read at that
     * instant still said `type 19 (running) v1=400` — the amount asked for — and AAPS recorded
     * 4.0 U against roughly 2.5 U delivered.
     *
     * A blacklist of the values known to be safe makes a claim about every value that is not.
     */
    @Test
    fun `an unmapped status does not finish the wait`() {
        assertThat(BolusNotificationStatus.hasFinished(2)).isFalse()
        assertThat(BolusNotificationStatus.name(2)).isEqualTo("Unknown(2)")

        val finished = setOf(
            BolusNotificationStatus.COMPLETED,
            BolusNotificationStatus.CANCELLED,
            BolusNotificationStatus.IDLE
        )
        (0..255).filterNot { it in finished }
            .forEach { assertThat(BolusNotificationStatus.hasFinished(it)).isFalse() }
    }
}
