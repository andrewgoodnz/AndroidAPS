package app.aaps.pump.ypso.services

import app.aaps.core.data.model.TE
import com.google.common.truth.Truth.assertThat
import com.ypsopump.sdk.YpsoEventTypes
import org.junit.jupiter.api.Test

/**
 * The two decisions [YpsoEventPolicy] took out of `YpsoService`, both of which are silent when wrong.
 *
 * `YpsoService` is a `DaggerService` and cannot be constructed without an Android runtime, so before
 * this these were reachable only by putting the pump into the state that produces the event — which
 * for half the table means an occlusion, a flat battery or a cartridge change on a bench.
 */
class YpsoEventPolicyTest {

    // ==================== which alerts make a sound ====================

    @Test
    fun `every alert that stops delivery is urgent`() {
        // The membership rule is one sentence: insulin is not being delivered. Each of these has
        // been seen on hardware except A_REUSABLE_ERROR, and each stops the pump.
        assertThat(YpsoEventPolicy.isUrgent(YpsoEventTypes.A_OCCLUSION)).isTrue()
        assertThat(YpsoEventPolicy.isUrgent(YpsoEventTypes.A_CARTRIDGE_EMPTY)).isTrue()
        assertThat(YpsoEventPolicy.isUrgent(YpsoEventTypes.A_NO_CARTRIDGE)).isTrue()
        assertThat(YpsoEventPolicy.isUrgent(YpsoEventTypes.A_AUTO_STOP)).isTrue()
        assertThat(YpsoEventPolicy.isUrgent(YpsoEventTypes.A_BATTERY_EMPTY)).isTrue()
        assertThat(YpsoEventPolicy.isUrgent(YpsoEventTypes.A_REUSABLE_ERROR)).isTrue()
    }

    @Test
    fun `a battery being removed is not urgent, and that is deliberate`() {
        // 100 sits in the middle of the urgent range (101-106) and is the one exclusion, so it is
        // the one an edit is most likely to sweep in by accident. The pump keeps delivering across
        // a battery change; making this sound would teach the user to silence the alarm that
        // A_OCCLUSION needs them to hear.
        assertThat(YpsoEventPolicy.isUrgent(YpsoEventTypes.A_BATTERY_REMOVED)).isFalse()
    }

    @Test
    fun `an unknown alert code is not urgent`() {
        // The pump's alert log goes back to 2019 and this driver has identified seven codes of it.
        // An unrecognised alert is still announced — it is only the sound that is withheld — so the
        // failure direction here is a notification without a noise, not a missing one.
        assertThat(YpsoEventPolicy.isUrgent(0)).isFalse()
        assertThat(YpsoEventPolicy.isUrgent(99)).isFalse()
        assertThat(YpsoEventPolicy.isUrgent(107)).isFalse()
        assertThat(YpsoEventPolicy.isUrgent(255)).isFalse()
    }

    // ==================== which consumable timer restarts ====================

    @Test
    fun `battery removal is a battery change`() {
        assertThat(YpsoEventPolicy.consumableEventFor(YpsoEventTypes.BATTERY_REMOVED, 0.0))
            .isEqualTo(TE.Type.PUMP_BATTERY_CHANGE)
    }

    @Test
    fun `a large prime is a tube change and a small one is nothing`() {
        // The threshold is what separates filling a line from topping up after a bubble. Recording
        // a top-up as a set change would reset the timer that tells the user their infusion set is
        // due, which is the direction that matters: the user believes they have three days left.
        val threshold = YpsoEventTypes.TUBE_CHANGE_PRIME_THRESHOLD_UNITS

        assertThat(YpsoEventPolicy.consumableEventFor(YpsoEventTypes.PRIMING_FINISHED, threshold + 0.1))
            .isEqualTo(TE.Type.TUBE_CHANGE)
        assertThat(YpsoEventPolicy.consumableEventFor(YpsoEventTypes.PRIMING_FINISHED, 20.0))
            .isEqualTo(TE.Type.TUBE_CHANGE)

        // Strictly greater than, so the threshold itself is a top-up.
        assertThat(YpsoEventPolicy.consumableEventFor(YpsoEventTypes.PRIMING_FINISHED, threshold)).isNull()
        assertThat(YpsoEventPolicy.consumableEventFor(YpsoEventTypes.PRIMING_FINISHED, 0.5)).isNull()
        assertThat(YpsoEventPolicy.consumableEventFor(YpsoEventTypes.PRIMING_FINISHED, 0.0)).isNull()
    }

    @Test
    fun `cannula priming maps to a site change - and the pump really does log it`() {
        // This comment used to say type 25 had never appeared in this pump's log and the branch was
        // dead. That was wrong, and instructively so: it rested on two history dumps that contained
        // no type 25 plus a session believed to have primed a cannula. On 2026-08-05 a cartridge
        // top-up in which somebody actually pressed the pump's **cannula fill** step produced
        // type=4 for the 2.0 U tubing prime and then type=25 for the 0.3 U cannula fill, seven
        // seconds apart, and AAPS recorded a CANNULA_CHANGE from it.
        //
        // The absence was never evidence about the pump; it was evidence about which buttons had
        // been pressed. Both primes are now live branches, and the split this mapping assumed —
        // type 4 tubing, type 25 cannula — is the split the pump actually makes.
        assertThat(YpsoEventPolicy.consumableEventFor(YpsoEventTypes.CANNULA_PRIMING_FINISHED, 0.0))
            .isEqualTo(TE.Type.CANNULA_CHANGE)

        // ⚠ And the amount is irrelevant to this branch, which matters because the real one was
        // 0.3 U — far below the tube-change threshold that governs type 4. A cannula fill is a site
        // change however small it is.
        assertThat(YpsoEventPolicy.consumableEventFor(YpsoEventTypes.CANNULA_PRIMING_FINISHED, 0.3))
            .isEqualTo(TE.Type.CANNULA_CHANGE)
    }

    @Test
    fun `an unmapped event type records nothing`() {
        // The event log carries far more types than this driver maps, and the safe answer for an
        // unrecognised one is no therapy record at all. A wrong record is worse than none here:
        // nothing reconciles a consumable change, so it stands for ever.
        assertThat(YpsoEventPolicy.consumableEventFor(0, 0.0)).isNull()
        assertThat(YpsoEventPolicy.consumableEventFor(2, 0.0)).isNull()   // a standard bolus
        assertThat(YpsoEventPolicy.consumableEventFor(23, 0.0)).isNull()  // daily total insulin
        assertThat(YpsoEventPolicy.consumableEventFor(99, 50.0)).isNull() // a big number is not a prime
    }
}
