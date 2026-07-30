package com.ypsopump.sdk.internal.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * When the driver is allowed to stop waiting for a bolus.
 *
 * This decides the instant the outcome is read out of the pump's log, and the pump is still
 * revising that log while it finishes. Stopping early reads a half-written answer.
 */
class BolusNotificationStatusTest {

    @Test
    fun `only a confirmed end state is terminal`() {
        assertThat(BolusNotificationStatus.isTerminal(BolusNotificationStatus.COMPLETED)).isTrue()
        assertThat(BolusNotificationStatus.isTerminal(BolusNotificationStatus.CANCELLED)).isTrue()
    }

    @Test
    fun `idle and delivering are not terminal`() {
        assertThat(BolusNotificationStatus.isTerminal(BolusNotificationStatus.IDLE)).isFalse()
        assertThat(BolusNotificationStatus.isTerminal(BolusNotificationStatus.DELIVERING)).isFalse()
    }

    /**
     * ⚠ The regression this file exists for.
     *
     * `isTerminal` was `status != IDLE && status != DELIVERING`, so every value nobody had ever seen
     * counted as "delivery finished". Cancelling a 4 U bolus on the pump produced status **2**,
     * which is in no table here, and the driver stopped waiting. `4 COMPLETED` arrived 2.2 seconds
     * later — after the outcome had been decided from an event-log entry that still read
     * `type 19 (running) v1=400`, i.e. the amount asked for. AAPS recorded 4.0 U against roughly
     * 2.5 U actually delivered.
     *
     * A blacklist of two known-safe values makes a claim about 254 unknown ones. Only a state
     * confirmed to mean "finished" may end the wait.
     */
    @Test
    fun `an unmapped status is not terminal`() {
        assertThat(BolusNotificationStatus.isTerminal(2)).isFalse()
        assertThat(BolusNotificationStatus.name(2)).isEqualTo("Unknown(2)")

        (0..255).filterNot { it == BolusNotificationStatus.COMPLETED || it == BolusNotificationStatus.CANCELLED }
            .forEach { assertThat(BolusNotificationStatus.isTerminal(it)).isFalse() }
    }
}
