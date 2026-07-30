package com.ypsopump.sdk.internal.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The wire-format findings from PORTING_NOTES §4, as executable assertions.
 *
 * Every byte string here was captured from the research pump (10074292, firmware V05.02.03), not
 * constructed. They are the corrections that made the read path work at all, and each of them
 * looked like reasonable code before the raw bytes were examined — so they are exactly the sort
 * of thing a later "tidy-up" reintroduces.
 */
class YpsoWireFormatTest {

    /**
     * §4a. The system-status reply is a bare CRC-terminated message: 18 payload bytes then the
     * CRC. Upstream instead read the first *data* byte as a frame count, chased continuation
     * frames that do not exist, and killed every session a second after authentication.
     *
     * The CRC validating over the bytes already in hand is what says the message is complete.
     * **The CRC is the discriminator, never the frame nibble** — the nibble may be data, and here
     * it is: the leading `0x03` is the delivery mode.
     */
    /**
     * A 20-byte status reply logged verbatim off the pump on 2026-07-30:
     * `03 42 14 00 00 02 00 00 00 00 64 00 00 00 00 00 00 00 15 30`. The driver only decoded it
     * because [YpsoCrc.isValid] passed, and the decode agreed with the pump (`Stopped, 51.86 U,
     * 2/4 bars, 0.0 U/h`), so the payload and its CRC are both known good.
     *
     * The frame written into PORTING_NOTES §4a as an illustration carries a **wrong CRC** — this
     * test is what caught it. Its payload is fine, but `F1 52` is not the CRC of it. The notes
     * have been corrected; use the bytes here as the reference, because these came from a log
     * rather than from prose.
     */
    private val statusFrame = byteArrayOf(
        0x03, 0x42, 0x14, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00,
        0x64, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x15, 0x30
    )

    @Test
    fun `status reply captured from the pump carries a valid CRC over its 18 payload bytes`() {
        val payload = statusFrame.copyOfRange(0, 18)
        val crc = statusFrame.copyOfRange(18, 20)

        assertThat(YpsoCrc.crc16(payload).toList()).isEqualTo(crc.toList())
        assertThat(YpsoCrc.isValid(statusFrame)).isTrue()
    }

    @Test
    fun `a corrupted status reply fails its CRC`() {
        // Flip one bit in the reservoir field: the amount would read wrong and, without the CRC,
        // nothing else would notice.
        val corrupted = statusFrame.copyOf().also { it[1] = 0x43 }

        assertThat(YpsoCrc.isValid(corrupted)).isFalse()
    }

    /** Every byte position matters — a single-byte change anywhere must break the check. */
    @Test
    fun `the CRC covers every payload byte`() {
        for (index in 0 until 18) {
            val corrupted = statusFrame.copyOf().also { it[index] = (it[index] + 1).toByte() }
            assertThat(YpsoCrc.isValid(corrupted)).isFalse()
        }
    }

    @Test
    fun `appendCrc produces a frame that validates`() {
        val payload = byteArrayOf(0x2A, 0x00, 0x07, 0x1E)
        assertThat(YpsoCrc.isValid(YpsoCrc.appendCrc(payload))).isTrue()
    }

    /**
     * §4c. Setting values come back as a bare GLB pair — the value then its bitwise complement —
     * with no CRC and no framing. Both vectors below are real reads; the second is the one that
     * broke the old code, because 120 (`0x78`) was taken as "frame 1 of 8".
     */
    @Test
    fun `GLB pairs captured from the pump decode to their settings values`() {
        val activeProgram = byteArrayOf(0x03, 0x00, 0x00, 0x00, 0xFC.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val basalRateSlot = byteArrayOf(0x78, 0x00, 0x00, 0x00, 0x87.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

        assertThat(YpsoGlb.decode(activeProgram)).isEqualTo(SettingsIndex.PROGRAM_A_VALUE)
        assertThat(YpsoGlb.decode(basalRateSlot)).isEqualTo(120)
    }

    /** The complement is the integrity check for these, standing in for the absent CRC. */
    @Test
    fun `a GLB pair whose complement does not match is rejected`() {
        val broken = byteArrayOf(0x78, 0x00, 0x00, 0x00, 0x88.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

        assertThat(runCatching { YpsoGlb.decode(broken) }.isFailure).isTrue()
        assertThat(YpsoGlb.findInPayload(broken)).isNull()
    }

    @Test
    fun `GLB encoding round-trips, including the unprogrammed-slot marker`() {
        listOf(0, 1, 120, 2500, -1).forEach { value ->
            assertThat(YpsoGlb.decode(YpsoGlb.encode(value))).isEqualTo(value)
        }
    }

    /**
     * §4b/§4e. A GLB write must go out as the bare 8 bytes. Framing it makes 9 and the pump
     * answers `GATT_INVALID_ATTRIBUTE_LENGTH`, which broke every basal profile read — and the same
     * mistake, in reverse, is what phase 2 hit on the bolus characteristic.
     */
    @Test
    fun `framing a GLB write is what produces the length the pump rejects`() {
        val glb = YpsoGlb.encode(14)
        assertThat(glb).hasLength(8)
        assertThat(YpsoFraming.chunkPayload(glb).first()).hasLength(9)
    }

    /**
     * §4. `DeliveryMode` reports only what has been confirmed against the pump's own display.
     * Upstream's table called `3` a fast bolus, which made `isDelivering` true for a **stopped**
     * pump — telling AAPS that basal was running when it was not, the direction that causes harm.
     *
     * An unverified value must therefore be `null` ("don't know"), never `true`.
     */
    @Test
    fun `an unverified delivery mode is never reported as delivering`() {
        assertThat(DeliveryMode.isDeliveringOrNull(DeliveryMode.V05_STOPPED)).isFalse()
        assertThat(DeliveryMode.isDeliveringOrNull(DeliveryMode.V05_RUNNING)).isTrue()

        // Every other value, including the ones upstream's table named.
        listOf(0, 1, 2, 4, 5, 6, 7, 42).forEach { mode ->
            assertThat(DeliveryMode.isDeliveringOrNull(mode)).isNull()
        }
    }

    @Test
    fun `delivery mode names say plainly when a value is a guess`() {
        assertThat(DeliveryMode.nameFor(DeliveryMode.V05_STOPPED)).isEqualTo("Stopped")
        assertThat(DeliveryMode.nameFor(DeliveryMode.V05_RUNNING)).isEqualTo("Running")
        assertThat(DeliveryMode.nameFor(7)).contains("Unverified")
    }
}
