package app.aaps.pump.ypso

import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.pump.ypso.keys.YpsoDoubleNonKey
import app.aaps.pump.ypso.keys.YpsoLongNonKey
import app.aaps.pump.ypso.keys.YpsoStringKey
import app.aaps.pump.ypso.keys.YpsoStringNonKey
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import com.ypsopump.sdk.BolusSpeed
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever

/**
 * `:pump:ypso` had no test source set at all: the SDK's decodes were tested and the driver on top of
 * them was not (`PORTING_NOTES` §8.5, "Unit tests").
 *
 * What can be reached without an Android runtime is the plugin's own logic, and it turns out to
 * include the two things most worth pinning: **what this driver tells AAPS it can do**, and **that a
 * method which refuses never reports success** — §0's oldest rule, written down after another
 * driver's dummy connector returned `success=true` for boluses.
 *
 * [YpsoService] itself is a `DaggerService` and cannot be constructed here; its one piece of
 * defect-prone arithmetic was lifted into `SuspendRecordDecision`, which is tested separately.
 */
class YpsoPluginTest : TestBaseWithProfile() {

    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var uiInteraction: UiInteraction

    private lateinit var ypsoPump: YpsoPump
    private lateinit var plugin: YpsoPlugin

    @BeforeEach
    fun prepare() {
        // Every refusal path ends in `comment(rh.gs(...))`, and PumpEnactResult rejects a null
        // comment — so an unstubbed string turns a legitimate refusal into an NPE rather than the
        // failed result the test is looking for.
        whenever(rh.gs(anyInt())).thenReturn("mocked")
        whenever(rh.gs(anyInt(), anyOrNull(), anyOrNull())).thenReturn("mocked")
        whenever(preferences.get(YpsoStringNonKey.MacAddress)).thenReturn("")
        whenever(preferences.get(YpsoLongNonKey.LastConnection)).thenReturn(0L)
        // `YpsoPump.firmwareVersion` is a non-null String backed by a preference read, so an
        // unstubbed mock hands `null` to `ifBlank` and NPEs inside pumpSpecificShortStatus rather
        // than returning the "?" the code is written to show.
        whenever(preferences.get(YpsoStringNonKey.FirmwareVersion)).thenReturn("")

        ypsoPump = YpsoPump(aapsLogger, preferences, dateUtil)
        plugin = YpsoPlugin(
            aapsLogger, rh, preferences, commandQueue, aapsSchedulers, rxBus, context,
            fabricPrivacy, ypsoPump, uiInteraction, pumpEnactResultProvider
        )
    }

    // ==================== what the driver promises AAPS ====================

    @Test
    fun `does not advertise the extended bolus it refuses`() {
        // PumpType.YPSOPUMP declares ExtendedBolus through YpsomedCapabilities, and setExtendedBolus
        // returns a failed result without contacting the pump. Left declared, AAPS offers an
        // extended-bolus button and a Treatments tab that can only fail. A capability is a promise
        // AAPS acts on, not a note about the hardware.
        assertThat(PumpType.YPSOPUMP.pumpCapability()?.hasCapability(app.aaps.core.data.pump.defs.Capability.ExtendedBolus)).isTrue()
        assertThat(plugin.pumpDescription.isExtendedBolusCapable).isFalse()
    }

    @Test
    fun `does not advertise TDD loading that is not implemented`() {
        assertThat(plugin.pumpDescription.supportsTDDs).isFalse()
        assertThat(plugin.pumpDescription.needsManualTDDLoad).isFalse()
    }

    @Test
    fun `still advertises everything it has been verified to do`() {
        // Each of these is confirmed on hardware behind its own saline phase (§8.1), so withdrawing
        // one would be as wrong as promising the extended bolus.
        with(plugin.pumpDescription) {
            assertThat(isBolusCapable).isTrue()
            assertThat(isTempBasalCapable).isTrue()
            assertThat(isSetBasalProfileCapable).isTrue()
            assertThat(isRefillingCapable).isTrue()
            assertThat(isBatteryReplaceable).isTrue()
        }
    }

    @Test
    fun `bolus step is the pump's own 0_1 U, and is not the pump's menu increment`() {
        // Settled on hardware 2026-08-01 (§8.8): with the pump's own increment set to 2 U, AAPS
        // asked for 0.3 U and the pump delivered exactly that. The increment governs what the
        // pump's menu offers, not what it accepts over BLE, so sourcing the step from PumpType is
        // right and nothing needs to read settings index 3 for safety.
        assertThat(plugin.pumpDescription.bolusStep).isEqualTo(0.1)
        assertThat(PumpType.YPSOPUMP.bolusSize()).isEqualTo(0.1)
        assertThat(PumpType.YPSOPUMP.specialBolusSize()).isNull()
    }

    @Test
    fun `the description is one object, not rebuilt per read`() {
        // Read from the UI. fillFor() re-derives and allocates on every call.
        assertThat(plugin.pumpDescription).isSameInstanceAs(plugin.pumpDescription)
    }

    // ==================== a refusal never reports success ====================

    @Test
    fun `extended bolus refuses without claiming success`() {
        val result = plugin.setExtendedBolus(1.0, 30)
        assertThat(result.success).isFalse()
        assertThat(result.enacted).isFalse()
    }

    @Test
    fun `cancel extended bolus refuses without claiming success`() {
        val result = plugin.cancelExtendedBolus()
        assertThat(result.success).isFalse()
        assertThat(result.enacted).isFalse()
    }

    @Test
    fun `loadTDDs refuses without claiming success`() {
        val result = plugin.loadTDDs()
        assertThat(result.success).isFalse()
        assertThat(result.enacted).isFalse()
    }

    @Test
    fun `a bolus with no pump paired fails rather than reporting a delivery`() {
        // isInitialized() is false — nothing is paired — so nothing can have been delivered.
        whenever(preferences.get(YpsoDoubleNonKey.MaxBolus)).thenReturn(25.0)
        val result = plugin.deliverTreatment(DetailedBolusInfo().also { it.insulin = 1.0 })
        assertThat(result.success).isFalse()
        assertThat(result.enacted).isFalse()
        assertThat(result.bolusDelivered).isEqualTo(0.0)
    }

    @Test
    fun `a temp basal with no pump paired fails rather than reporting an enactment`() {
        val result = plugin.setTempBasalPercent(150, 30, validProfile, false, app.aaps.core.interfaces.pump.PumpSync.TemporaryBasalType.NORMAL)
        assertThat(result.success).isFalse()
        assertThat(result.enacted).isFalse()
    }

    @Test
    fun `carbs never reach the pump`() {
        // Matches Medtrum exactly. A carbs-only treatment is not this pump's business, and the
        // guard is an assertion rather than a silent no-op so a caller cannot mistake it for one.
        assertThrows(IllegalArgumentException::class.java) {
            plugin.deliverTreatment(DetailedBolusInfo().also { it.insulin = 1.0; it.carbs = 20.0 })
        }
        assertThrows(IllegalArgumentException::class.java) {
            plugin.deliverTreatment(DetailedBolusInfo().also { it.insulin = 0.0 })
        }
    }

    // ==================== state reported to AAPS ====================

    @Test
    fun `an unpaired pump is not initialised`() {
        whenever(preferences.get(YpsoStringNonKey.MacAddress)).thenReturn("")
        assertThat(plugin.isInitialized()).isFalse()
    }

    @Test
    fun `suspended is false until the pump has answered at least once`() {
        // lastConnection == 0: nothing has been read, so "suspended" would be a claim about a pump
        // nobody has spoken to. isInitialized() is what holds the loop off at this point.
        assertThat(plugin.isSuspended()).isFalse()
    }

    @Test
    fun `a pump that has answered and is not delivering reads as suspended`() {
        whenever(preferences.get(YpsoLongNonKey.LastConnection)).thenReturn(1L)
        ypsoPump.isDelivering = false
        assertThat(plugin.isSuspended()).isTrue()

        ypsoPump.isDelivering = true
        assertThat(plugin.isSuspended()).isFalse()
    }

    @Test
    fun `after a restart the pump reads as suspended until it answers, and that is every restart`() {
        // ⚠ This is the SAME condition as the test above, and for a long time that was the only way
        // it was read — as "a genuinely stopped pump". It is also the state of every single app
        // start, which is a different situation with different consequences, and nothing said so.
        //
        // `lastConnection` is persisted in preferences; `isDelivering` is a plain in-memory field
        // that starts false. So after any restart both are true at once — a pump that has answered
        // (once, in some previous process) and is not delivering (as far as this process knows).
        // Observed on hardware 2026-08-04: AAPS opened SUSPENDED_BY_PUMP for 30.3 s on a pump that
        // was running the whole time, cancelled the temporary basal, and then healed itself.
        //
        // It is left this way deliberately. Before the first read the pump's state is genuinely
        // unknown, and "suspended" is the conservative guess — it withholds dosing rather than
        // dosing blind. Medtrum, the driver this one is shaped on, does exactly the same thing
        // (`pumpState` starts at NONE, and NONE < ACTIVE). Dana and Diaconn guess the other way.
        whenever(preferences.get(YpsoLongNonKey.LastConnection)).thenReturn(1_785_000_000_000L)
        // isDelivering is left at its constructed default on purpose — that IS the restart state.
        assertThat(ypsoPump.isDelivering).isFalse()
        assertThat(plugin.isSuspended()).isTrue()

        // The first status read that finds the pump running is what ends it.
        ypsoPump.isDelivering = true
        assertThat(plugin.isSuspended()).isFalse()
    }

    @Test
    fun `model and manufacturer are the ones the rest of AAPS keys on`() {
        assertThat(plugin.model()).isEqualTo(PumpType.YPSOPUMP)
        assertThat(plugin.pumpDescription.pumpType).isEqualTo(PumpType.YPSOPUMP)
    }

    // ==================== the profile comparison that decides whether AAPS rewrites ====================

    @Test
    fun `a profile is not set while the pump's rates are unknown`() {
        // The honest answer when there is nothing to compare, and it makes AAPS offer the write
        // rather than assume agreement.
        ypsoPump.basalProfileRates = emptyList()
        assertThat(plugin.isThisProfileSet(validProfile)).isFalse()
    }

    @Test
    fun `a partial read is not mistaken for a match`() {
        ypsoPump.basalProfileRates = List(23) { 1.0 }
        assertThat(plugin.isThisProfileSet(validProfile)).isFalse()
    }

    @Test
    fun `a profile the pump already holds reads as set`() {
        // validProfile is a flat 1.0 U/h, which is on the pump's grid at that rate, so rounding
        // moves nothing.
        ypsoPump.basalProfileRates = List(24) { 1.0 }
        assertThat(plugin.isThisProfileSet(validProfile)).isTrue()
    }

    @Test
    fun `one differing hour is enough to be not set`() {
        // The trap this guards: comparing against the *unrounded* profile would report a mismatch
        // on a correctly written profile for ever, and AAPS would offer to rewrite it on every loop
        // iteration. Comparing too loosely would leave a wrong hour in the pump. This is the
        // boundary — half the pump's finest step.
        ypsoPump.basalProfileRates = List(24) { 1.0 }.toMutableList().also { it[7] = 1.02 }
        assertThat(plugin.isThisProfileSet(validProfile)).isFalse()

        ypsoPump.basalProfileRates = List(24) { 1.0 }.toMutableList().also { it[7] = 1.004 }
        assertThat(plugin.isThisProfileSet(validProfile)).isTrue()
    }

    // ==================== preferences ====================

    @Test
    fun `the preference screen builds`() {
        val screen = preferenceManager.createPreferenceScreen(context)
        plugin.addPreferenceScreen(preferenceManager, screen, context, null)
        assertThat(screen.preferenceCount).isGreaterThan(0)
    }

    // ==================== the bolus speed list ====================

    /** Every preference in the tree, depth first. The driver nests its own under a category. */
    private fun PreferenceGroup.flatten(): List<Preference> =
        (0 until preferenceCount).map { getPreference(it) }.flatMap { child ->
            listOf(child) + if (child is PreferenceGroup) child.flatten() else emptyList()
        }

    private fun bolusSpeedPreference(): ListPreference {
        val screen = preferenceManager.createPreferenceScreen(context)
        plugin.addPreferenceScreen(preferenceManager, screen, context, null)
        val all = screen.flatten()
        // Walked rather than `findPreference`d: that matches on the key, and a preference whose key
        // did not get set would come back null with nothing to say about why. The dump names what
        // is actually on the screen.
        return all.filterIsInstance<ListPreference>().singleOrNull { it.key == YpsoStringKey.YpsoBolusSpeed.key }
            ?: error(
                "No ListPreference keyed '${YpsoStringKey.YpsoBolusSpeed.key}' on the screen. Found: " +
                    all.joinToString { "${it::class.simpleName}(key=${it.key})" }
            )
    }

    @Test
    fun `the bolus speed list offers all four of Lotus's speeds`() {
        // 6 U/min was held out of this list until Phase 9 passed on 2026-08-04, and its absence was
        // how the list came to be missing a rate from the middle of its own range in the first
        // place. Four options, and the values are the enum's own.
        val preference = bolusSpeedPreference()
        assertThat(preference.entryValues.map { it.toString() })
            .containsExactly("unlimited", "12", "6", "3")
            .inOrder()
        assertThat(BolusSpeed.entries).hasSize(4)
    }

    @Test
    fun `each label describes the value stored beside it`() {
        // ⚠ This is the one part of the mapping that is not correct by construction. The values come
        // from `BolusSpeed.storedValue`, so they cannot drift; the labels are hand-written strings
        // whose ONLY link to them is list position. Reordering one array and not the other would
        // silently offer "3 U/min" and store 12 — a four-fold error in the direction of a faster
        // bolus than the user asked for, and nothing in the UI would look wrong.
        val preference = bolusSpeedPreference()
        assertThat(preference.entries).hasLength(preference.entryValues.size)

        preference.entryValues.forEachIndexed { index, value ->
            val speed = BolusSpeed.from(value.toString())
            val label = preference.entries[index].toString()
            assertThat(label).contains(
                if (speed == BolusSpeed.Unlimited) "Unlimited" else "${speed.unitsPerMinute.toInt()} U/min"
            )
        }
    }

    @Test
    fun `every offered value resolves to a real speed, and an unknown one is the pump's own`() {
        // `from` falls back to Unlimited for anything unrecognised, which is the path eight hardware
        // phases ran. That is the safe fallback for a *comfort* setting: it delivers, at the rate
        // the pump has always used, rather than refusing or picking a rate nobody chose.
        bolusSpeedPreference().entryValues.forEach { value ->
            assertThat(BolusSpeed.from(value.toString()).storedValue).isEqualTo(value.toString())
        }
        assertThat(BolusSpeed.from(null)).isEqualTo(BolusSpeed.Unlimited)
        assertThat(BolusSpeed.from("")).isEqualTo(BolusSpeed.Unlimited)
        assertThat(BolusSpeed.from("33.3")).isEqualTo(BolusSpeed.Unlimited)

        // ⚠ And the old index-valued list: `0 -> 3 U/min, 1 -> 12, 2 -> 33.3`. A stored `1` from
        // that list lands here as Unlimited rather than as 12 U/min, which is deliberate — under
        // the old code the preference set a timeout estimate and a log line and did not chunk at
        // all, so Unlimited *is* the delivery behaviour that value already produced.
        assertThat(BolusSpeed.from("0")).isEqualTo(BolusSpeed.Unlimited)
        assertThat(BolusSpeed.from("1")).isEqualTo(BolusSpeed.Unlimited)
        assertThat(BolusSpeed.from("2")).isEqualTo(BolusSpeed.Unlimited)
    }

    @Test
    fun `the shipped default does not chunk`() {
        // Passing Phase 9 licensed the option, not a change for anyone who has not picked one.
        assertThat(YpsoStringKey.YpsoBolusSpeed.defaultValue).isEqualTo(BolusSpeed.Unlimited.storedValue)
        assertThat(BolusSpeed.from(YpsoStringKey.YpsoBolusSpeed.defaultValue).chunks).isFalse()
    }

    // ==================== what the user is told the build is ====================

    @Test
    fun `the short status does not claim a verification that has not happened`() {
        // This line has been stale twice — "profile write UNTESTED" after Phase 4 passed, then
        // "Saline test build — not for use with insulin" once a build was made for a phone that
        // will carry insulin. It is now a fact about the driver's history rather than a verdict
        // about its use, so it cannot go stale the same way; this pins that it stays one.
        val status = plugin.pumpSpecificShortStatus(false)
        assertThat(status).contains("saline only")
        assertThat(status).doesNotContain("UNTESTED")

        // veryShort is the home-screen field and carries the firmware alone.
        assertThat(plugin.pumpSpecificShortStatus(true)).doesNotContain("saline")
    }
}
