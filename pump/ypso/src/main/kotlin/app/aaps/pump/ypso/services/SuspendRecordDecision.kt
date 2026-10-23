package app.aaps.pump.ypso.services

import com.pump.common.OperatingMode

/**
 * What the 0% `PUMP_SUSPEND` temporary basal should do at this poll, decided from the pump's
 * delivery mode and how much of the current record is left.
 *
 * **Lifted out of [YpsoService] because it is the single most defect-prone decision in this driver
 * and nothing could reach it from a test.** `YpsoService` is a `DaggerService`; without an Android
 * runtime it cannot be constructed, so the arithmetic below had never been exercised by anything
 * but a fourteen-hour hardware run. It has been wrong four times, each time in a different way and
 * each time producing the same wrong belief — *basal is being delivered* — while a stopped pump
 * delivered nothing (`PORTING_NOTES` §8.6):
 *
 * 1. no record was written at all;
 * 2. it was written but never re-asserted, because a `StateFlow` conflates equal values;
 * 3. it was re-asserted but closed within seconds by AAPS's own automatic `cancelTempBasal`;
 * 4. it was renewed only *after* expiring, which phase-locked to the poll interval and left two
 *    twenty-minute holes in thirteen hours.
 *
 * Only the fourth is decided here, and it is the one a test can settle: [decide] is a total
 * function of three numbers. The other three are about ordering and identification and live in the
 * service, where they belong.
 *
 * Same move, and the same reason, as `HistorySyncDecision` in the SDK.
 */
enum class SuspendRecordDecision {

    /**
     * The delivery mode has never been identified, so **write nothing**.
     *
     * Only two of byte 0's 256 values are mapped (§8.3). `isSuspended()` still reports true for an
     * unknown mode, so the loop stays held off — that costs nothing and reverses instantly. A
     * therapy record is a claim that persists, and this driver does not make claims from bytes
     * nobody has identified.
     */
    Ignore,

    /** Stopped, and the open record has comfortably more than [renewBelowMs] left. Nothing to do. */
    Hold,

    /**
     * Stopped, and the open record is close enough to expiry to be topped up now.
     *
     * ⚠ **Before expiry, never after.** Waiting for the record to lapse and then noticing leaves a
     * hole the width of one poll interval. The record is opened *by* a poll, so its expiry falls
     * exactly one record-length later; AAPS polls every ~20 minutes and the record lasts 60, so a
     * poll landed within *seconds* of every expiry and drift decided the outcome. One observed miss
     * was by a single second. Renewing early leaves margin **and** breaks the phase-lock.
     */
    Renew,

    /**
     * Stopped with no record open. Open one, and tell the user.
     *
     * Reached on the first poll of a suspension — and, if something has gone wrong, after a lapse.
     * The two are indistinguishable from here, which is why [lapsedByMs] is a separate question.
     */
    Open,

    /** Delivering again, with a record of ours open: close it. */
    Close,

    /** Delivering, nothing of ours open. The ordinary case, and the overwhelmingly common one. */
    Clear;

    companion object {

        /**
         * @param mode        the pump's delivery mode this poll.
         * @param remainingMs milliseconds left on the currently open `PUMP_SUSPEND` record this
         *                    driver recognises as its own, or `null` if none is open.
         * @param renewBelowMs renew once the record has less than this left. Must comfortably
         *                    exceed the status-poll interval, which is ~20 min and not this
         *                    driver's to choose.
         */
        fun decide(mode: OperatingMode, remainingMs: Long?, renewBelowMs: Long): SuspendRecordDecision = when {
            mode == OperatingMode.UNKNOWN -> Ignore
            mode == OperatingMode.STARTED -> if (remainingMs == null) Clear else Close
            remainingMs == null           -> Open
            remainingMs > renewBelowMs    -> Hold
            else                          -> Renew
        }

        /**
         * How long the record was missing for, or `null` if it was not.
         *
         * **This is the check the old one could not be.** `syncSuspendedState` opens a replacement
         * before `logDeliveryState` writes a line, so every line reads `suspendRecord=open` —
         * including the pairs bracketing a twenty-minute hole. The 13h40 run containing two real
         * gaps reported zero. A lapse is only visible by noticing that the record which *should*
         * still have been open is gone, which needs one number carried from the previous poll.
         *
         * The width is measured, not inferred: expiry was known and [now] is known.
         *
         * @param recordIsOpen whether a record of ours is open right now.
         * @param watchedExpiryMs when the record seen at the *previous* poll was due to expire,
         *                    or `null` if there was none to watch.
         */
        fun lapsedByMs(recordIsOpen: Boolean, watchedExpiryMs: Long?, now: Long): Long? =
            if (!recordIsOpen && watchedExpiryMs != null) now - watchedExpiryMs else null
    }
}
