package com.ypsopump.sdk.internal.data

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The 18-byte system-status decode, against two payloads captured from the research pump.
 *
 * This is the most safety-critical decode in the driver: reservoir, delivery mode and the active
 * temporary basal all come from here, and two of the fields were decoded *wrongly* by upstream in
 * ways that were invisible from the driver's own output. Both captures are real — one with the
 * pump stopped and no temporary basal, one running under a 130% TBR.
 */
class SystemStatusDataTest {

    /** PORTING_NOTES §4a: pump stopped, 57.71 U in the reservoir, 2 of 4 battery bars. */
    private val stopped = byteArrayOf(
        0x03, 0x8B.toByte(), 0x16, 0x00, 0x00, 0x02, 0x78, 0x00, 0x00,
        0x00, 0x64, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    )

    /** PORTING_NOTES §4: pump running a 130% TBR with 15 minutes left, effective rate 1.56 U/h. */
    private val runningUnderTbr = byteArrayOf(
        0x0A, 0x2B, 0x16, 0x00, 0x00, 0x02, 0x9C.toByte(), 0x00, 0x00,
        0x00, 0x82.toByte(), 0x00, 0x00, 0x00, 0xF0.toByte(), 0x00, 0x00, 0x00
    )

    @Test
    fun `stopped pump decodes every field`() {
        val status = SystemStatusData.parse(stopped)!!

        assertThat(status.deliveryMode).isEqualTo(3)
        assertThat(status.deliveryModeName).isEqualTo("Stopped")
        assertThat(status.insulinRemaining).isWithin(0.001f).of(57.71f)
        assertThat(status.currentBasalRate).isWithin(1e-9).of(1.20)
        assertThat(status.isTbrRunning).isFalse()
        assertThat(status.tbrRemainingMinutes).isEqualTo(0)
    }

    /**
     * The 130% TBR is what made the whole payload readable, and 1.56 U/h is why: it is the
     * programmed 1.20 U/h scaled by the percentage in the neighbouring field, which is what shows
     * the three fields belong together rather than happening to sit next to each other.
     */
    @Test
    fun `running pump decodes the temporary basal and the effective rate`() {
        val status = SystemStatusData.parse(runningUnderTbr)!!

        assertThat(status.deliveryMode).isEqualTo(10)
        assertThat(status.deliveryModeName).isEqualTo("Running")
        assertThat(status.insulinRemaining).isWithin(0.001f).of(56.75f)
        assertThat(status.tbrPercent).isEqualTo(130)
        assertThat(status.tbrRemainingMinutes).isEqualTo(240)
        assertThat(status.isTbrRunning).isTrue()

        // 1.20 U/h at 130% — the pump reports the rate it is actually running.
        assertThat(status.currentBasalRate).isWithin(1e-9).of(1.56)
    }

    /**
     * Byte 5 is a **bar count**, not a percentage. The pump displayed "2 of 4 bars" for a raw
     * value of 2; upstream passed that straight through as `2%`, turning a half-charged battery
     * into one apparently about to die — and AAPS acts on pump battery level.
     */
    @Test
    fun `battery is scaled from bars, not passed through as a percentage`() {
        val status = SystemStatusData.parse(stopped)!!

        assertThat(status.batteryBars).isEqualTo(2)
        assertThat(status.batteryPercent).isEqualTo(50)
    }

    @Test
    fun `battery scales across the full gauge and clamps above it`() {
        fun percentFor(bars: Int) = SystemStatusData.parse(
            stopped.copyOf().also { it[5] = bars.toByte() }
        )!!.batteryPercent

        assertThat(percentFor(0)).isEqualTo(0)
        assertThat(percentFor(4)).isEqualTo(100)
        // A value above the gauge must not produce a percentage above 100.
        assertThat(percentFor(9)).isEqualTo(100)
    }

    /** 100 means "no temporary basal", not "a temporary basal at 100%". */
    @Test
    fun `a TBR percent of 100 means none is running`() {
        val status = SystemStatusData.parse(
            runningUnderTbr.copyOf().also { it[10] = SystemStatusData.TBR_PERCENT_NONE.toByte() }
        )!!

        assertThat(status.tbrPercent).isEqualTo(100)
        assertThat(status.isTbrRunning).isFalse()
    }

    /** A short read must decode to nothing rather than to plausible-looking rubbish. */
    @Test
    fun `a truncated payload returns null`() {
        assertThat(SystemStatusData.parse(stopped.copyOfRange(0, 17))).isNull()
        assertThat(SystemStatusData.parse(byteArrayOf())).isNull()
    }

    /**
     * §4d. Rates must be divided in `Double`. Widening `120 / 100f` gives 1.2000000476837158, and
     * `isThisProfileSet` compares the pump against the AAPS profile — so a Float divide would
     * report a mismatch on a correct profile for ever.
     */
    @Test
    fun `the basal rate is exact, not a widened Float`() {
        val status = SystemStatusData.parse(stopped)!!

        assertThat(status.currentBasalRate).isEqualTo(1.20)
        assertThat(status.currentBasalRate).isNotEqualTo((120 / 100f).toDouble())
    }
}
