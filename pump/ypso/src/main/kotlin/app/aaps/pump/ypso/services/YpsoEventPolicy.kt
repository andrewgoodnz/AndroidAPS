package app.aaps.pump.ypso.services

import app.aaps.core.data.model.TE
import com.ypsopump.sdk.YpsoEventTypes

/**
 * Which of the pump's own events AAPS is told about, and how loudly.
 *
 * **Lifted out of [YpsoService] for the reason `SuspendRecordDecision` was**: the service is a
 * `DaggerService` and cannot be constructed without an Android runtime, so two decisions that change
 * what a user is told — and which of AAPS's consumable timers restart — had never been exercised by
 * anything except a hardware run that happened to reach them. Both are total functions of an integer.
 * The behaviour is unchanged; the extraction is mechanical.
 *
 * Neither decision is arithmetic, which is why they are enumerations rather than formulas. What
 * makes them worth a test is that a wrong answer is **silent in both directions**: an alert demoted
 * out of [isUrgent] stops making a sound and nobody notices until an occlusion goes unheard, and a
 * consumable mapped to the wrong [TE.Type] restarts the wrong timer on the AAPS home screen.
 */
object YpsoEventPolicy {

    /**
     * Alert codes that mean **insulin is not being delivered**. These get an urgent, audible
     * notification; everything else the pump logs as an alert is informational by comparison.
     *
     * The membership rule is that one sentence and nothing else. `A_BATTERY_REMOVED` (100) is
     * deliberately **absent**: the pump keeps delivering across a battery change, so it is a notice
     * rather than a stoppage, and putting it here would train the reader to silence the sound that
     * `A_OCCLUSION` needs them to hear.
     */
    private val URGENT_ALERT_CODES = setOf(
        YpsoEventTypes.A_OCCLUSION,
        YpsoEventTypes.A_CARTRIDGE_EMPTY,
        YpsoEventTypes.A_NO_CARTRIDGE,
        YpsoEventTypes.A_AUTO_STOP,
        YpsoEventTypes.A_BATTERY_EMPTY,
        YpsoEventTypes.A_REUSABLE_ERROR
    )

    /** True when [code] means delivery has stopped, and so warrants a sound (§4g). */
    fun isUrgent(code: Int): Boolean = code in URGENT_ALERT_CODES

    /**
     * The AAPS therapy event a mechanical pump event becomes, or `null` for "record nothing".
     *
     * The mapping is the one confirmed against mylife Lotus (§8.5): cannula priming is a site
     * change, battery removal is a battery change, and a prime counts as a **tubing** change only
     * when it was large enough to have actually filled a line. A small prime is a top-up after a
     * bubble, and recording it as a set change would reset the AAPS timer that tells the user when
     * their infusion set is due.
     *
     * ✅ **`CANNULA_PRIMING_FINISHED` (25) is real, and this branch is live — verified 2026-08-05.**
     * It was called a dead branch here for weeks, on the strength of two history dumps that contained
     * no type 25 and a session that was believed to have primed a cannula. It appeared the moment
     * somebody actually performed the pump's **cannula fill** step: a cartridge top-up produced
     * `type=16` rewind, `type=4` for the 2.0 U *tubing* prime, and then `type=25` for the 0.3 U
     * **cannula** fill, seven seconds apart. AAPS recorded a `CANNULA_CHANGE` from it and the site
     * timer restarted.
     *
     * So the pump does distinguish the two primes, and the distinction is the one this mapping always
     * assumed: **type 4 is the tubing, type 25 is the cannula.** What was missing was never the event
     * — it was a tester pressing that step.
     *
     * @param primedUnits how much the prime delivered, for [YpsoEventTypes.PRIMING_FINISHED]. The
     *   caller decodes it from the entry's raw data; it is passed in so this stays a function of
     *   numbers.
     */
    fun consumableEventFor(eventType: Int, primedUnits: Double): TE.Type? = when (eventType) {
        YpsoEventTypes.CANNULA_PRIMING_FINISHED -> TE.Type.CANNULA_CHANGE
        YpsoEventTypes.BATTERY_REMOVED          -> TE.Type.PUMP_BATTERY_CHANGE
        YpsoEventTypes.PRIMING_FINISHED         ->
            TE.Type.TUBE_CHANGE.takeIf { primedUnits > YpsoEventTypes.TUBE_CHANGE_PRIME_THRESHOLD_UNITS }

        else                                    -> null
    }
}
