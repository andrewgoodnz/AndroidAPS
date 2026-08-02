package app.aaps.pump.ypso.services

import com.google.common.truth.Truth.assertThat
import com.pump.common.OperatingMode
import org.junit.jupiter.api.Test

/**
 * The renewal arithmetic behind the 0% `PUMP_SUSPEND` record.
 *
 * Four bugs have produced the same wrong belief — *basal is being delivered* — while a stopped pump
 * delivered nothing (`PORTING_NOTES` §8.6). The fourth is the one decided here, and until now the
 * only thing that had ever exercised it was a fourteen-hour unattended hardware run, on a build
 * that turned out to predate the fix.
 *
 * The cases below are written from what that run measured, in real numbers: a 60-minute record, a
 * ~20-minute poll interval, and a 30-minute renewal threshold.
 */
class SuspendRecordDecisionTest {

    private val minute = 60_000L
    private val renewBelow = 30 * minute
    private val recordLength = 60 * minute

    // ==================== the case the overnight run failed ====================

    @Test
    fun `renews before expiry, not after`() {
        // Two polls into a 60-minute record: 40 min elapsed, 20 left. The old code waited for the
        // record to be gone; this must top it up while it is still covering.
        assertThat(SuspendRecordDecision.decide(OperatingMode.STOPPED, 20 * minute, renewBelow))
            .isEqualTo(SuspendRecordDecision.Renew)
    }

    @Test
    fun `holds while there is more than the threshold left`() {
        // One poll in: 40 min left. Renewing here too would churn the record every poll.
        assertThat(SuspendRecordDecision.decide(OperatingMode.STOPPED, 40 * minute, renewBelow))
            .isEqualTo(SuspendRecordDecision.Hold)
    }

    @Test
    fun `renewal leaves a whole poll of margin`() {
        // The point of the threshold: at the moment renewal is decided, there must still be more
        // than one ~20-minute poll interval of cover left, so that missing *this* poll is survivable
        // too. Anything at or below the threshold renews, and the threshold is 30 min.
        val pollIntervalMs = 20 * minute
        val worstCaseRemainingAtRenewal = renewBelow
        assertThat(worstCaseRemainingAtRenewal).isGreaterThan(pollIntervalMs)
    }

    @Test
    fun `the threshold boundary renews rather than holds`() {
        // Exactly at the threshold. The safe direction is to renew: holding here would let the
        // decision turn on a millisecond, which is how the original phase-lock decided outcomes.
        assertThat(SuspendRecordDecision.decide(OperatingMode.STOPPED, renewBelow, renewBelow))
            .isEqualTo(SuspendRecordDecision.Renew)
        assertThat(SuspendRecordDecision.decide(OperatingMode.STOPPED, renewBelow + 1, renewBelow))
            .isEqualTo(SuspendRecordDecision.Hold)
    }

    @Test
    fun `the phase-lock that caused the two holes is broken`() {
        // The failure: 60 is an exact multiple of the ~20-minute poll interval, so a poll landed
        // within seconds of every expiry and drift decided whether it caught it. One miss was by a
        // single second. Walk three polls of a record and assert that the renewal is decided on a
        // poll that is nowhere near the expiry boundary.
        val polls = listOf(20 * minute, 40 * minute, 60 * minute)
        val decisions = polls.map { elapsed ->
            SuspendRecordDecision.decide(OperatingMode.STOPPED, recordLength - elapsed, renewBelow)
        }
        assertThat(decisions).containsExactly(
            SuspendRecordDecision.Hold,     // 40 min left
            SuspendRecordDecision.Renew,    // 20 min left — renewed with a third of the record spare
            SuspendRecordDecision.Renew     // never reached in practice; the record was replaced at 40
        ).inOrder()
    }

    // ==================== opening, closing, and the state that writes nothing ====================

    @Test
    fun `a stopped pump with no record opens one`() {
        assertThat(SuspendRecordDecision.decide(OperatingMode.STOPPED, null, renewBelow))
            .isEqualTo(SuspendRecordDecision.Open)
    }

    @Test
    fun `an expired record is indistinguishable from none and opens a fresh one`() {
        // Negative remaining cannot arrive from expectedPumpState() — an expired record is simply
        // absent — but the arithmetic must not treat "less than zero" as "plenty left".
        assertThat(SuspendRecordDecision.decide(OperatingMode.STOPPED, -5 * minute, renewBelow))
            .isEqualTo(SuspendRecordDecision.Renew)
    }

    @Test
    fun `delivering again closes the record`() {
        assertThat(SuspendRecordDecision.decide(OperatingMode.STARTED, 40 * minute, renewBelow))
            .isEqualTo(SuspendRecordDecision.Close)
    }

    @Test
    fun `delivering with nothing open does nothing`() {
        assertThat(SuspendRecordDecision.decide(OperatingMode.STARTED, null, renewBelow))
            .isEqualTo(SuspendRecordDecision.Clear)
    }

    @Test
    fun `an unidentified delivery mode writes no record, whatever is open`() {
        // Only two of byte 0's 256 values are mapped (§8.3). A therapy record is a claim that
        // persists, and this driver does not make claims from bytes nobody has identified — the
        // loop is still held off by isSuspended(), which costs nothing and reverses instantly.
        assertThat(SuspendRecordDecision.decide(OperatingMode.UNKNOWN, null, renewBelow))
            .isEqualTo(SuspendRecordDecision.Ignore)
        assertThat(SuspendRecordDecision.decide(OperatingMode.UNKNOWN, 5 * minute, renewBelow))
            .isEqualTo(SuspendRecordDecision.Ignore)
    }

    @Test
    fun `PAUSED is treated as stopped, though this pump has no pause`() {
        // Retired on hardware 2026-08-01 — the pump's menus offer stop only, so upstream's
        // PAUSED = 7 is unreachable on V05.02.03. If a firmware ever produces it, "not delivering"
        // is the reading that does not invent insulin.
        assertThat(SuspendRecordDecision.decide(OperatingMode.PAUSED, null, renewBelow))
            .isEqualTo(SuspendRecordDecision.Open)
    }

    // ==================== the lapse detector — the check that can fail ====================

    @Test
    fun `a record that vanished when it should have been open is a lapse, measured`() {
        val expiry = 1_000_000L
        val now = expiry + 19 * minute + 48_000  // one of the two real holes: 19m48s
        assertThat(SuspendRecordDecision.lapsedByMs(recordIsOpen = false, watchedExpiryMs = expiry, now = now))
            .isEqualTo(19 * minute + 48_000)
    }

    @Test
    fun `no lapse while the record is open`() {
        // The whole point. The old check sampled `suspendRecord=open` after a replacement had been
        // written, so it reported a pass through a twenty-minute hole. This one asks the question
        // before anything acts.
        assertThat(SuspendRecordDecision.lapsedByMs(recordIsOpen = true, watchedExpiryMs = 1_000_000L, now = 2_000_000L))
            .isNull()
    }

    @Test
    fun `no lapse on the first poll of a suspension`() {
        // Nothing was being watched, so the absent record is the start of the suspension, not a
        // hole in it. Reporting one here would make the line meaningless — it would fire every time
        // the pump was stopped.
        assertThat(SuspendRecordDecision.lapsedByMs(recordIsOpen = false, watchedExpiryMs = null, now = 2_000_000L))
            .isNull()
    }

    @Test
    fun `a clean run never reports a lapse`() {
        // Four polls, 20 minutes apart, against a record renewed at the 40-minute mark. This is
        // what 6b-RERUN must produce, and it is the sequence the pre-fix build could not.
        var expiryWatch: Long? = null
        var recordExpiry: Long? = null
        val lapses = mutableListOf<Long>()

        for (poll in 0..8) {
            val now = poll * 20 * minute
            val open = recordExpiry?.takeIf { it > now }
            SuspendRecordDecision.lapsedByMs(open != null, expiryWatch, now)?.let { lapses += it }

            when (SuspendRecordDecision.decide(OperatingMode.STOPPED, open?.minus(now), renewBelow)) {
                SuspendRecordDecision.Open, SuspendRecordDecision.Renew -> {
                    recordExpiry = now + recordLength
                    expiryWatch = recordExpiry
                }

                SuspendRecordDecision.Hold                              -> expiryWatch = open

                else                                                   -> {}
            }
        }

        assertThat(lapses).isEmpty()
    }
}
