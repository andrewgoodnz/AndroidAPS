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
    fun `cannula priming maps to a site change - a branch no pump has ever reached`() {
        // ⚠ Read this before trusting it. Type 25 has never appeared in this pump's log, in any
        // state, which is why AAPS's site-change timer can never restart itself here (§8.4). The
        // mapping is right if an entry ever appears; the test pins the mapping and this comment
        // pins the fact that nothing has exercised it against real data.
        assertThat(YpsoEventPolicy.consumableEventFor(YpsoEventTypes.CANNULA_PRIMING_FINISHED, 0.0))
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
