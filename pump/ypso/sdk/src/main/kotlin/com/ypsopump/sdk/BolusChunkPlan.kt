package com.ypsopump.sdk

import kotlin.math.roundToInt

/**
 * How a slow bolus is split, and at what cadence — the whole of "bolus speed", decided on the phone.
 *
 * **There is no pump-side bolus speed** (§8.6). The settings-index hunt came back negative on every
 * index anybody has ever read, and a 2 U bolus commanded by AAPS ran at 31.2 U/min with mylife Lotus
 * set to 3 U/min. What Lotus actually does is issue **ordinary standard boluses on a timer**,
 * confirmed in the pump's own event log: five `type=2` entries eight seconds apart summing to exactly
 * 2.00 U. So this is a chunk size and an interval, and the pump never learns that anything unusual
 * happened.
 *
 * The four parameter sets below are **Lotus's**, read off its settings screen. Phase 9 is explicit
 * that comfort is not something a saline bench can assess, so these are taken from the manufacturer
 * and none are invented.
 *
 * Kept pure and separate from [YpsoPumpManagerImpl] for the reason [HistorySyncDecision] is: the
 * arithmetic here decides how much insulin goes out in each of up to fifty commands, and it can be
 * tested against every amount and every rate without a pump.
 */
enum class BolusSpeed(
    /** What is stored in the preference. Self-describing on purpose — see [from]. */
    val storedValue: String,
    /** The label's rate. Nominal: the real cadence is [intervalSeconds], and it can only slip later. */
    val unitsPerMinute: Double,
    /** Chunk size in centi-units, or 0 for "do not chunk". */
    val chunkCentiUnits: Int,
    /** Seconds between chunk **starts**, or 0 for "do not chunk". */
    val intervalSeconds: Int
) {

    /**
     * The pump's own rate, no chunking at all — one command, exactly as every phase from 1 to 8 ran.
     *
     * ~33.3 U/min is measured rather than declared: two boluses timed on 2026-08-03 (0.3 U in 1.27 s,
     * 3.92 U in 8.33 s) both fit ~33 U/min with a consistent ~0.7 s overhead, and the 0.3 U one rules
     * out 12 U/min on its own because 12 needs 1.50 s for the delivery alone.
     */
    Unlimited("unlimited", 33.3, 0, 0),

    /** Lotus: 1 U per 5 s. */
    Twelve("12", 12.0, 100, 5),

    /**
     * Lotus: 0.5 U per 5 s.
     *
     * ⚠ **Deliberately not offered in the UI yet.** It is the option the old three-way list was
     * missing, and Phase 9 says it appears only once 9a–9e have passed. It is defined here because
     * the ceiling in [plan] steps *through* it, and because leaving a known parameter out of the
     * table to keep it out of a dropdown is how the list came to be missing it in the first place.
     */
    Six("6", 6.0, 50, 5),

    /** Lotus: 0.4 U per 8 s. The rate the five-entry log capture above was taken at. */
    Three("3", 3.0, 40, 8);

    /** True when this rate chunks at all. */
    val chunks: Boolean get() = chunkCentiUnits > 0

    companion object {

        /**
         * The stored preference value, or [Unlimited] for anything unrecognised.
         *
         * ⚠ **The values are strings that say what they mean, not indices.** The list they replace
         * was `0 → 3 U/min, 1 → 12, 2 → 33.3`, and it was missing 6 U/min from the *middle* of its
         * own range — so adding the fourth option to an index list would silently re-point every
         * stored preference at a different rate. There is no migration from the old values because
         * there is nothing to migrate: under the old code the preference set a timeout estimate and
         * a log line, and a stored `1` landing here as [Unlimited] restores exactly the delivery
         * behaviour that value already produced.
         *
         * Unrecognised falls back to [Unlimited] because that is the path eight hardware phases
         * have run.
         */
        fun from(storedValue: String?): BolusSpeed =
            entries.firstOrNull { it.storedValue == storedValue } ?: Unlimited
    }
}

/**
 * A bolus split into the commands that will actually be sent.
 *
 * @param requestedSpeed what the preference asked for.
 * @param speed what will be used — different only when [ceilingSteppedUp] is true.
 * @param chunkCentiUnits the amounts, in order. Always sums to the request **exactly**, because the
 *   split is done in integer centi-units; the wire format carries centi-units and the pump logs
 *   partials at 0.01 U resolution, so nothing is lost by staying on that grid.
 */
data class BolusChunkPlan(
    val requestedCentiUnits: Int,
    val requestedSpeed: BolusSpeed,
    val speed: BolusSpeed,
    val chunkCentiUnits: List<Int>,
    val intervalMillis: Long
) {

    /** False for a single command — the ordinary delivery path, unchanged since Phase 2. */
    val isChunked: Boolean get() = chunkCentiUnits.size > 1

    /** True when the duration ceiling forced a faster rate than the user configured. */
    val ceilingSteppedUp: Boolean get() = speed != requestedSpeed

    val chunkUnits: List<Double> get() = chunkCentiUnits.map { it / 100.0 }

    /** Nominal span, first chunk start to last chunk finish. The real one can only be longer. */
    val nominalDurationSeconds: Int get() = chunkCentiUnits.size * speed.intervalSeconds

    companion object {

        /**
         * The longest a sequence may hold the connection mutex, and with it the AAPS command queue.
         *
         * For this long there is no status read, no temporary basal, no SMB and **no alert check** —
         * and an occlusion partway through a slow bolus is precisely the case the alert check exists
         * for (§4g). Five minutes is one AAPS loop cycle. The standard bolus already holds the queue
         * for ~20 s, so this is a factor of fifteen rather than a factor of a thousand.
         *
         * It binds only at 3 U/min above ~14.8 U: at the 25 U pump maximum the nominal spans are
         * 125 s at 12 U/min, 250 s at 6, and 504 s at 3.
         */
        const val MAX_SEQUENCE_SECONDS = 300

        /**
         * Splits [units] at [requestedSpeed], stepping the rate up if the ceiling would be breached.
         *
         * **Stepping up rather than refusing or falling back to the pump's rate.** Refusing is the
         * wrong failure — the user asked for insulin and a comfort setting should not withhold it —
         * and dropping straight to unchunked hands somebody who configured 3 U/min a 20 U bolus in
         * 36 seconds without warning. Walking to the next faster option keeps every parameter a
         * figure Lotus publishes, which is Phase 9's instruction. At the 25 U maximum it never has to
         * reach [BolusSpeed.Unlimited], but that is the terminating case rather than an assumption.
         */
        fun plan(units: Double, requestedSpeed: BolusSpeed): BolusChunkPlan {
            val total = (units * 100).roundToInt()
            var speed = requestedSpeed
            while (speed.chunks && nominalSeconds(total, speed) > MAX_SEQUENCE_SECONDS) {
                speed = nextFaster(speed) ?: BolusSpeed.Unlimited
            }
            return BolusChunkPlan(
                requestedCentiUnits = total,
                requestedSpeed = requestedSpeed,
                speed = speed,
                chunkCentiUnits = split(total, speed),
                intervalMillis = speed.intervalSeconds * 1000L
            )
        }

        /**
         * The chunks, in order: whole ones, then whatever is left.
         *
         * The remainder goes **last and small**, which is the shape Lotus produced —
         * `0.41 0.41 0.41 0.41 0.36`, the last one trimmed so the total lands exactly. A total at or
         * below one chunk is one command, so a 0.5 U bolus at 12 U/min is delivered exactly as it
         * was before any of this existed.
         */
        private fun split(totalCentiUnits: Int, speed: BolusSpeed): List<Int> {
            if (totalCentiUnits <= 0) return listOf(totalCentiUnits)
            if (!speed.chunks || totalCentiUnits <= speed.chunkCentiUnits) return listOf(totalCentiUnits)
            val whole = (totalCentiUnits - 1) / speed.chunkCentiUnits
            val remainder = totalCentiUnits - whole * speed.chunkCentiUnits
            return List(whole) { speed.chunkCentiUnits } + remainder
        }

        private fun nominalSeconds(totalCentiUnits: Int, speed: BolusSpeed): Int =
            split(totalCentiUnits, speed).size * speed.intervalSeconds

        /** The next option up the published list, or null at the top. */
        private fun nextFaster(speed: BolusSpeed): BolusSpeed? =
            BolusSpeed.entries.getOrNull(speed.ordinal - 1)
    }
}
