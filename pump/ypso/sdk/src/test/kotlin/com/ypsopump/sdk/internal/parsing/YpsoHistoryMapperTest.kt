package com.ypsopump.sdk.internal.parsing

import com.google.common.truth.Truth.assertThat
import com.pump.common.BolusType
import com.pump.common.PumpHistoryEvent
import com.ypsopump.sdk.internal.data.HistoryEntry
import com.ypsopump.sdk.internal.protocol.EventType
import org.junit.jupiter.api.Test

/**
 * The history mapping, which decides the amount of every bolus that reaches `PumpSync` and the
 * identity it is stored under.
 *
 * The entries below mirror ones read from the research pump and checked against actions performed
 * on it minutes earlier (PORTING_NOTES §5).
 */
class YpsoHistoryMapperTest {

    private fun entry(
        type: Int,
        value1: Int = 0,
        value2: Int = 0,
        value3: Int = 0,
        sequence: Long = 160_286L,
        index: Int = 0,
        timestamp: Long = 1_785_362_330L
    ) = HistoryEntry(
        timestamp = timestamp,
        entryType = type,
        entryTypeName = EventType.name(type),
        value1 = value1,
        value2 = value2,
        value3 = value3,
        sequence = sequence,
        index = index
    )

    /**
     * ⚠ The finding that matters most in this file.
     *
     * `position` becomes the PumpSync `pumpId`, and it must be the pump's **sequence number**, not
     * the slot index. Index 0 is the *newest* entry, so every new event renumbers every older one
     * and today's bolus inherits yesterday's index. Feeding that to `syncBolusWithPumpId` makes
     * PumpSync either merge two unrelated boluses or reject a real one as a duplicate — silent,
     * persistent IOB corruption that cannot be cleanly undone. Plan §4 specified the index; this
     * is a deliberate departure from it.
     */
    @Test
    fun `position carries the pump sequence number and never the slot index`() {
        val mapped = YpsoHistoryMapper.toHistoryEvent(
            entry(EventType.BOLUS_IMMEDIATE, value1 = 150, sequence = 160_265L, index = 9)
        )!!

        assertThat(mapped.position).isEqualTo(160_265L)
        assertThat(mapped.position).isNotEqualTo(9L)
    }

    /**
     * A 1.5 U bolus delivered on the pump logged `v1=150`, so `v1` is centi-units. This is the
     * only delivery-related value that could be verified before the driver could deliver anything,
     * and it is what becomes the amount in an AAPS treatment record.
     */
    @Test
    fun `a bolus decodes centi-units into units`() {
        val mapped = YpsoHistoryMapper.toHistoryEvent(entry(EventType.BOLUS_IMMEDIATE, value1 = 150))

        assertThat(mapped).isInstanceOf(PumpHistoryEvent.BolusDelivered::class.java)
        with(mapped as PumpHistoryEvent.BolusDelivered) {
            assertThat(amount).isWithin(1e-9).of(1.50)
            assertThat(bolusType).isEqualTo(BolusType.STANDARD)
        }
    }

    @Test
    fun `the smallest bolus the pump can deliver decodes to five centi-units`() {
        val mapped = YpsoHistoryMapper.toHistoryEvent(entry(EventType.BOLUS_IMMEDIATE, value1 = 5))
            as PumpHistoryEvent.BolusDelivered

        assertThat(mapped.amount).isWithin(1e-9).of(0.05)
    }

    @Test
    fun `bolus variants map to their AAPS bolus types`() {
        fun typeOf(eventType: Int) =
            (YpsoHistoryMapper.toHistoryEvent(entry(eventType, value1 = 100)) as PumpHistoryEvent.BolusDelivered).bolusType

        assertThat(typeOf(EventType.BOLUS_IMMEDIATE)).isEqualTo(BolusType.STANDARD)
        assertThat(typeOf(EventType.BOLUS_DELAYED)).isEqualTo(BolusType.EXTENDED)
        assertThat(typeOf(EventType.BOLUS_COMBINED)).isEqualTo(BolusType.MULTIWAVE)
        assertThat(typeOf(EventType.BOLUS_BLIND)).isEqualTo(BolusType.STANDARD)
    }

    /** A 130% TBR logged `v1=130`, with the duration in `v2`. */
    @Test
    fun `a temporary basal carries percent and minutes`() {
        val mapped = YpsoHistoryMapper.toHistoryEvent(
            entry(EventType.BASAL_PROFILE_TEMP_RUNNING, value1 = 130, value2 = 15)
        ) as PumpHistoryEvent.TbrStarted

        assertThat(mapped.percentage).isEqualTo(130)
        assertThat(mapped.durationMinutes).isEqualTo(15)
    }

    /**
     * ⚠ A *finished* temporary basal is a type-10 entry, and it must still map to a complete TBR.
     *
     * This pump keeps one entry per temporary basal and rewrites it in place: type 9 while running,
     * type 10 once stopped, with `v2` rewritten from the requested duration to the actual one, and
     * `timestamp` staying the start. Verified on hardware — `seq=160371` read `type=9 v1=150 v2=30`
     * while running and `type=10 v1=150 v2=2` after a cancel.
     *
     * Mapping type 10 to `TbrEnded` discarded the percentage and duration and handed AAPS an end for
     * a start it had never seen, because a read taken after the TBR finished never sees type 9 at
     * all. The entry describes the whole temporary basal, so that is what it maps to.
     */
    @Test
    fun `a finished temporary basal maps to a complete TBR with its actual duration`() {
        val mapped = YpsoHistoryMapper.toHistoryEvent(
            entry(EventType.BASAL_PROFILE_TEMP, value1 = 150, value2 = 2, sequence = 160_371L)
        )

        assertThat(mapped).isInstanceOf(PumpHistoryEvent.TbrStarted::class.java)
        with(mapped as PumpHistoryEvent.TbrStarted) {
            assertThat(percentage).isEqualTo(150)
            // The 2 minutes it ran, not the 30 that were asked for.
            assertThat(durationMinutes).isEqualTo(2)
            assertThat(position).isEqualTo(160_371L)
        }
    }

    @Test
    fun `an aborted temporary basal also maps to a complete TBR`() {
        val mapped = YpsoHistoryMapper.toHistoryEvent(
            entry(EventType.BASAL_PROFILE_TEMP_ABORT, value1 = 80, value2 = 5)
        )

        assertThat(mapped).isInstanceOf(PumpHistoryEvent.TbrStarted::class.java)
        with(mapped as PumpHistoryEvent.TbrStarted) {
            assertThat(percentage).isEqualTo(80)
            assertThat(durationMinutes).isEqualTo(5)
        }
    }

    /**
     * The start and end of one temporary basal share a sequence number, because they are one entry.
     * Both states must therefore produce the same `position`, or PumpSync would store a finished TBR
     * as a second, separate one.
     */
    @Test
    fun `both states of one TBR entry share the same position`() {
        val running = YpsoHistoryMapper.toHistoryEvent(
            entry(EventType.BASAL_PROFILE_TEMP_RUNNING, value1 = 150, value2 = 30, sequence = 160_371L)
        )!!
        val finished = YpsoHistoryMapper.toHistoryEvent(
            entry(EventType.BASAL_PROFILE_TEMP, value1 = 150, value2 = 2, sequence = 160_371L)
        )!!

        assertThat(finished.position).isEqualTo(running.position)
    }

    @Test
    fun `timestamps are converted from seconds to milliseconds`() {
        val mapped = YpsoHistoryMapper.toHistoryEvent(
            entry(EventType.BOLUS_IMMEDIATE, value1 = 5, timestamp = 1_785_362_330L)
        )!!

        assertThat(mapped.timestamp).isEqualTo(1_785_362_330_000L)
    }

    /**
     * ⚠ Clock changes must not reach PumpSync. A type 12 entry's own timestamp is whatever the
     * clock said *before* the change — one real entry reads 2022-01-01 — so syncing it would place
     * a therapy record at a wildly wrong time. Type 13 has the same problem.
     */
    @Test
    fun `clock changes are not mapped`() {
        assertThat(YpsoHistoryMapper.toHistoryEvent(entry(EventType.DATE_CHANGED, 30, 7, 26))).isNull()
        assertThat(YpsoHistoryMapper.toHistoryEvent(entry(EventType.TIME_CHANGED, 7, 9, 32))).isNull()
    }

    @Test
    fun `daily totals and unrecognised types are not mapped`() {
        assertThat(YpsoHistoryMapper.toHistoryEvent(entry(EventType.DAILY_TOTAL_INSULIN, 3503))).isNull()
        assertThat(YpsoHistoryMapper.toHistoryEvent(entry(200))).isNull()
    }

    @Test
    fun `alerts map across the whole alert range`() {
        listOf(EventType.A_BATTERY_REMOVED, EventType.A_OCCLUSION, EventType.A_BATTERY_REJECTED).forEach { code ->
            val mapped = YpsoHistoryMapper.toHistoryEvent(entry(code))
            assertThat(mapped).isInstanceOf(PumpHistoryEvent.Alert::class.java)
        }
    }

    /**
     * A prime's amount decides whether it was a tubing change or a top-up, so it has to survive
     * into `rawData` — the pump has no single "tubing changed" event.
     */
    @Test
    fun `a prime keeps its amount in rawData as little-endian 16-bit values`() {
        val mapped = YpsoHistoryMapper.toHistoryEvent(
            entry(EventType.PRIMING_FINISHED, value1 = 200, value2 = 0, value3 = 0)
        ) as PumpHistoryEvent.Unknown

        assertThat(mapped.eventType).isEqualTo(EventType.PRIMING_FINISHED)
        // 200 centi-units = 2.00 U, little-endian across the first two bytes.
        assertThat(mapped.rawData[0]).isEqualTo(200.toByte())
        assertThat(mapped.rawData[1]).isEqualTo(0.toByte())
    }

    @Test
    fun `a rewind is a cartridge change`() {
        assertThat(YpsoHistoryMapper.toHistoryEvent(entry(EventType.REWIND_FINISHED)))
            .isInstanceOf(PumpHistoryEvent.CartridgeChanged::class.java)
    }
}
