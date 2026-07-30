package com.ypsopump.sdk.internal.data

import com.google.common.truth.Truth.assertThat
import com.ypsopump.sdk.internal.protocol.EventType
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The 17-byte history entry layout and its 2000-01-01 epoch, both verified on hardware against
 * actions performed on the pump minutes before the read (PORTING_NOTES §5).
 */
class HistoryEntryTest {

    /** Builds an entry the way the pump lays one out, so the parser is tested against the format. */
    private fun encode(
        pumpSeconds: Long,
        type: Int,
        value1: Int,
        value2: Int = 0,
        value3: Int = 0,
        sequence: Long,
        index: Int
    ): ByteArray = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(pumpSeconds.toInt())
        .put(type.toByte())
        .putShort(value1.toShort())
        .putShort(value2.toShort())
        .putShort(value3.toShort())
        .putInt(sequence.toInt())
        .putShort(index.toShort())
        .array()

    /**
     * The 1.5 U bolus from §5, at 2026-07-29 21:58 local. The epoch offset is the thing under test:
     * converted timestamps landed on plausible real dates across a two-year span, which is what
     * confirmed the pump counts from 2000-01-01.
     */
    @Test
    fun `an entry decodes its fields and converts the pump epoch`() {
        val wanted = ZonedDateTime.of(2026, 7, 29, 21, 58, 0, 0, ZoneOffset.UTC).toEpochSecond()
        val pumpSeconds = wanted - 946_684_800L

        val parsed = HistoryEntry.parse(
            encode(pumpSeconds, EventType.BOLUS_IMMEDIATE, value1 = 150, sequence = 160_265L, index = 9)
        )

        assertThat(parsed.timestamp).isEqualTo(wanted)
        assertThat(parsed.entryType).isEqualTo(EventType.BOLUS_IMMEDIATE)
        assertThat(parsed.entryTypeName).isEqualTo("Bolus Immediate")
        assertThat(parsed.value1).isEqualTo(150)
        assertThat(parsed.sequence).isEqualTo(160_265L)
        assertThat(parsed.index).isEqualTo(9)
    }

    /**
     * The clock-change pair logged by phase 1's write: type 12 carries day/month/year and type 13
     * carries hour/minute/second, both in `v1/v2/v3`. `07:09:32` is exactly the payload that went
     * out to the pump.
     */
    @Test
    fun `clock-change entries carry the new date and time in their value fields`() {
        val dateChanged = HistoryEntry.parse(
            encode(0, EventType.DATE_CHANGED, value1 = 30, value2 = 7, value3 = 26, sequence = 160_285L, index = 1)
        )
        assertThat(Triple(dateChanged.value1, dateChanged.value2, dateChanged.value3)).isEqualTo(Triple(30, 7, 26))

        val timeChanged = HistoryEntry.parse(
            encode(0, EventType.TIME_CHANGED, value1 = 7, value2 = 9, value3 = 32, sequence = 160_286L, index = 0)
        )
        assertThat(Triple(timeChanged.value1, timeChanged.value2, timeChanged.value3)).isEqualTo(Triple(7, 9, 32))
    }

    /**
     * The sequence number is a u32 and must not go negative once it passes 2^31 — it is the
     * PumpSync `pumpId`, so a sign flip there would collide records.
     */
    @Test
    fun `sequence and timestamp are unsigned`() {
        val parsed = HistoryEntry.parse(
            encode(0xFFFFFFFFL, EventType.BOLUS_IMMEDIATE, value1 = 1, sequence = 0xFFFFFFFFL, index = 0xFFFF)
        )

        assertThat(parsed.sequence).isEqualTo(4_294_967_295L)
        assertThat(parsed.timestamp).isGreaterThan(0L)
        assertThat(parsed.index).isEqualTo(65_535)
    }

    /** `v1` is a u16: an amount above 327.67 U must not wrap negative. */
    @Test
    fun `value fields are unsigned 16-bit`() {
        val parsed = HistoryEntry.parse(
            encode(0, EventType.BOLUS_IMMEDIATE, value1 = 0xFFFF, sequence = 1L, index = 0)
        )

        assertThat(parsed.value1).isEqualTo(65_535)
    }

    @Test
    fun `a short entry is rejected rather than half-parsed`() {
        val truncated = ByteArray(16)

        assertThat(runCatching { HistoryEntry.parse(truncated) }.isFailure).isTrue()
    }
}
