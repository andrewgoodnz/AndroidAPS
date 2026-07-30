package com.ypsopump.sdk.internal.data

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The clock decode, and the sanity check that stands between a misread layout and a wrongly-set
 * pump clock.
 *
 * The bytes here are the ones actually read from the pump during phase 1 (PORTING_NOTES §4e).
 */
class PumpClockDataTest {

    /** `E9 07 05 1C` / `01 08 32` — read from the pump with its clock wound back 14 months. */
    private val woundBack = PumpClockData(
        year = 2025, month = 5, day = 28, hour = 1, minute = 8, second = 50,
        dateWire = "E9 07 05 1C", timeWire = "01 08 32"
    )

    /** `EA 07 07 1E` / `07 09 20` — read back after the write, matching what went out. */
    private val afterWrite = PumpClockData(
        year = 2026, month = 7, day = 30, hour = 7, minute = 9, second = 32,
        dateWire = "EA 07 07 1E", timeWire = "07 09 20"
    )

    @Test
    fun `the captured readings are plausible and format for the log`() {
        assertThat(woundBack.isPlausible).isTrue()
        assertThat(woundBack.formatted()).isEqualTo("2025-05-28 01:08:50")

        assertThat(afterWrite.isPlausible).isTrue()
        assertThat(afterWrite.formatted()).isEqualTo("2026-07-30 07:09:32")
    }

    /**
     * The year is a u16 little-endian, which is what the raw bytes confirm: `EA 07` is `0x07EA` =
     * 2026, not `0xEA07`. Worth an assertion because it is easy to get backwards by hand — I
     * predicted `F2 07` for 2026 during the hardware session, which is 2034.
     */
    @Test
    fun `the year bytes are little-endian`() {
        assertThat(0x07EA).isEqualTo(2026)
        assertThat(0x07E9).isEqualTo(2025)
        assertThat(0x07F2).isNotEqualTo(2026)
    }

    /**
     * The plausibility check is what stops a wrong field layout becoming a wrong clock: a read
     * that fails it means these characteristics do not carry what this code assumes, so nothing
     * may be written back on the strength of it.
     */
    @Test
    fun `an implausible reading is rejected field by field`() {
        val base = afterWrite

        assertThat(base.copy(year = 1999).isPlausible).isFalse()
        assertThat(base.copy(year = 2100).isPlausible).isFalse()
        assertThat(base.copy(month = 0).isPlausible).isFalse()
        assertThat(base.copy(month = 13).isPlausible).isFalse()
        assertThat(base.copy(day = 0).isPlausible).isFalse()
        assertThat(base.copy(day = 32).isPlausible).isFalse()
        assertThat(base.copy(hour = 24).isPlausible).isFalse()
        assertThat(base.copy(minute = 60).isPlausible).isFalse()
        assertThat(base.copy(second = 60).isPlausible).isFalse()
    }

    /**
     * A characteristic full of `0xFF` — the shape of an unset or unreadable attribute — must not
     * decode to something the driver would act on.
     */
    @Test
    fun `an all-ones reading is not plausible`() {
        val allOnes = PumpClockData(
            year = 0xFFFF, month = 0xFF, day = 0xFF, hour = 0xFF, minute = 0xFF, second = 0xFF,
            dateWire = "FF FF FF FF", timeWire = "FF FF FF"
        )

        assertThat(allOnes.isPlausible).isFalse()
    }

    @Test
    fun `epochMillis interprets the reading in the phone's own zone`() {
        val expected = ZonedDateTime.of(2026, 7, 30, 7, 9, 32, 0, java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()

        assertThat(afterWrite.epochMillis()).isEqualTo(expected)
    }

    /**
     * The 14-month skew that made phase 1's result unambiguous. Any tolerance the driver uses to
     * decide "the pump adopted our time" has to be far smaller than this.
     */
    @Test
    fun `the wound-back clock is far outside any sane match tolerance`() {
        val skewMs = woundBack.epochMillis() - afterWrite.epochMillis()

        assertThat(skewMs).isLessThan(-300L * 24 * 60 * 60 * 1000)
    }

    /** Sanity: the pump's own epoch, used by history entries, really is 2000-01-01 UTC. */
    @Test
    fun `the pump epoch is 2000-01-01 UTC`() {
        val epoch2000 = ZonedDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toEpochSecond()

        assertThat(epoch2000).isEqualTo(946_684_800L)
    }
}
