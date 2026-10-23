package com.ypsopump.sdk

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The split decides how much insulin goes out in each of up to fifty separate commands, so every
 * case is pinned here rather than discovered on a bench.
 *
 * Two properties matter above the rest and are checked on every amount: **the chunks sum to exactly
 * what was asked for**, and **no chunk exceeds the configured size**. A rounding error that lands
 * high is a dose the user did not ask for.
 */
class BolusChunkPlanTest {

    @Test
    fun `unlimited is one command, which is the path every phase so far has run`() {
        val plan = BolusChunkPlan.plan(10.0, BolusSpeed.Unlimited)
        assertThat(plan.isChunked).isFalse()
        assertThat(plan.chunkCentiUnits).containsExactly(1000)
        assertThat(plan.intervalMillis).isEqualTo(0)
    }

    @Test
    fun `9a - 2 U at 12 U per min is two chunks of exactly 1 U, five seconds apart`() {
        val plan = BolusChunkPlan.plan(2.0, BolusSpeed.Twelve)
        assertThat(plan.chunkUnits).containsExactly(1.0, 1.0).inOrder()
        assertThat(plan.intervalMillis).isEqualTo(5_000)
        assertThat(plan.isChunked).isTrue()
        assertThat(plan.ceilingSteppedUp).isFalse()
    }

    @Test
    fun `Lotus's own capture - 2 U at 3 U per min is five chunks eight seconds apart`() {
        // The pump's event log, 2026-08-03: five type=2 entries 8 s apart summing to 2.00 U.
        // Lotus split it 0.41 0.41 0.41 0.41 0.36 and this splits it 0.40 x5. Same count, same
        // cadence, same total; the trim lands differently because the nominal chunk is not scaled
        // by how long the previous cycle actually took. That is deliberate — a time-compensating
        // controller delivers *more* when the phone is slow, which is the wrong direction for a
        // comfort feature and is not a parameter Lotus publishes.
        val plan = BolusChunkPlan.plan(2.0, BolusSpeed.Three)
        assertThat(plan.chunkCentiUnits).hasSize(5)
        assertThat(plan.chunkCentiUnits.sum()).isEqualTo(200)
        assertThat(plan.intervalMillis).isEqualTo(8_000)
    }

    @Test
    fun `9b - 6 U at 3 U per min is fifteen chunks over about two minutes`() {
        val plan = BolusChunkPlan.plan(6.0, BolusSpeed.Three)
        assertThat(plan.chunkCentiUnits).hasSize(15)
        assertThat(plan.nominalDurationSeconds).isEqualTo(120)
        assertThat(plan.ceilingSteppedUp).isFalse()
    }

    @Test
    fun `a bolus at or below one chunk is delivered as one command`() {
        // Nothing about small boluses changes. 1.0 U at 12 U/min is one chunk exactly, and a
        // sequence of one is not a sequence.
        assertThat(BolusChunkPlan.plan(1.0, BolusSpeed.Twelve).isChunked).isFalse()
        assertThat(BolusChunkPlan.plan(0.5, BolusSpeed.Twelve).chunkCentiUnits).containsExactly(50)
        assertThat(BolusChunkPlan.plan(0.4, BolusSpeed.Three).isChunked).isFalse()
        assertThat(BolusChunkPlan.plan(0.1, BolusSpeed.Three).chunkCentiUnits).containsExactly(10)
    }

    @Test
    fun `the remainder goes last and small, never first and never oversized`() {
        assertThat(BolusChunkPlan.plan(2.5, BolusSpeed.Twelve).chunkUnits)
            .containsExactly(1.0, 1.0, 0.5).inOrder()
        // A remainder of a single centi-unit is still issued rather than being folded into the
        // chunk before it. Folding would put 1.05 U into a cycle the user configured for 1.00.
        assertThat(BolusChunkPlan.plan(2.01, BolusSpeed.Twelve).chunkUnits)
            .containsExactly(1.0, 1.0, 0.01).inOrder()
    }

    @Test
    fun `every amount on the grid sums exactly and never exceeds the chunk size`() {
        // The property that matters. Floating point is kept out of the split entirely — 0.1 + 0.2
        // is the classic, and this one becomes a dose.
        for (speed in listOf(BolusSpeed.Twelve, BolusSpeed.Six, BolusSpeed.Three)) {
            for (centiUnits in 1..2500) {
                val plan = BolusChunkPlan.plan(centiUnits / 100.0, speed)
                assertThat(plan.chunkCentiUnits.sum()).isEqualTo(centiUnits)
                assertThat(plan.chunkCentiUnits.max()).isAtMost(plan.speed.chunkCentiUnits)
                assertThat(plan.chunkCentiUnits.min()).isGreaterThan(0)
            }
        }
    }

    @Test
    fun `the ceiling steps 3 U per min up to 6 rather than refusing or going native`() {
        // 25 U at 3 U/min is 63 chunks over 504 s, well past the five-minute ceiling. Stepping up
        // gives 50 chunks over 250 s — still slow, still a manufacturer figure, and it never has
        // to reach the pump's own 33 U/min.
        val plan = BolusChunkPlan.plan(25.0, BolusSpeed.Three)
        assertThat(plan.speed).isEqualTo(BolusSpeed.Six)
        assertThat(plan.requestedSpeed).isEqualTo(BolusSpeed.Three)
        assertThat(plan.ceilingSteppedUp).isTrue()
        assertThat(plan.nominalDurationSeconds).isAtMost(BolusChunkPlan.MAX_SEQUENCE_SECONDS)
        assertThat(plan.chunkCentiUnits.sum()).isEqualTo(2500)
    }

    @Test
    fun `no rate at the pump's 25 U maximum can breach the ceiling`() {
        for (speed in BolusSpeed.entries) {
            val plan = BolusChunkPlan.plan(25.0, speed)
            assertThat(plan.nominalDurationSeconds).isAtMost(BolusChunkPlan.MAX_SEQUENCE_SECONDS)
        }
    }

    @Test
    fun `the ceiling binds at 3 U per min just above 14 point 8 U and not below it`() {
        assertThat(BolusChunkPlan.plan(14.8, BolusSpeed.Three).speed).isEqualTo(BolusSpeed.Three)
        assertThat(BolusChunkPlan.plan(14.81, BolusSpeed.Three).speed).isEqualTo(BolusSpeed.Six)
    }

    @Test
    fun `an unrecognised stored value is unlimited, not the slowest thing in the list`() {
        // The fallback has to be the delivery path eight hardware phases have run. It is also what
        // a preference stored under the old index list ("0", "1", "2") lands on, and that is the
        // intended outcome: under the old code those values set a timeout estimate, not a rate.
        assertThat(BolusSpeed.from(null)).isEqualTo(BolusSpeed.Unlimited)
        assertThat(BolusSpeed.from("")).isEqualTo(BolusSpeed.Unlimited)
        assertThat(BolusSpeed.from("1")).isEqualTo(BolusSpeed.Unlimited)
        assertThat(BolusSpeed.from("33.3")).isEqualTo(BolusSpeed.Unlimited)
    }

    @Test
    fun `the stored values round-trip, so the fourth option can be added without remapping`() {
        for (speed in BolusSpeed.entries) {
            assertThat(BolusSpeed.from(speed.storedValue)).isEqualTo(speed)
        }
    }

    @Test
    fun `the published parameters are the ones Lotus shows`() {
        // unlimited 33.3 U/min | 12 = 1 U per 5 s | 6 = 0.5 U per 5 s | 3 = 0.4 U per 8 s.
        // Pinned because they are read off a manufacturer's screen and nothing in the code would
        // otherwise stop somebody "tidying" 0.4/8 into 0.5/10.
        assertThat(BolusSpeed.Twelve.chunkCentiUnits to BolusSpeed.Twelve.intervalSeconds).isEqualTo(100 to 5)
        assertThat(BolusSpeed.Six.chunkCentiUnits to BolusSpeed.Six.intervalSeconds).isEqualTo(50 to 5)
        assertThat(BolusSpeed.Three.chunkCentiUnits to BolusSpeed.Three.intervalSeconds).isEqualTo(40 to 8)
        for (speed in BolusSpeed.entries.filter { it.chunks }) {
            val perMinute = speed.chunkCentiUnits / 100.0 * (60.0 / speed.intervalSeconds)
            assertThat(perMinute).isWithin(0.001).of(speed.unitsPerMinute)
        }
    }
}
