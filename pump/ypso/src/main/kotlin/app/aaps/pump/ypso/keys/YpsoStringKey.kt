package app.aaps.pump.ypso.keys

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.ypso.R
import com.ypsopump.sdk.BolusSpeed

enum class YpsoStringKey(
    override val key: String,
    override val defaultValue: String,
    private val titleResId: Int,
    private val summaryResId: Int? = null,
    override val isPassword: Boolean = false,
    override val isPin: Boolean = false,
    override val preferenceType: PreferenceType = PreferenceType.TEXT_FIELD,
    override val entries: Map<String, TextRef> = emptyMap()
) : StringPreferenceKey {

    /**
     * How fast a bolus is delivered, by **chunking it in the phone** — see `BolusSpeed`.
     *
     * ⚠ This used to claim to be "the pump's own speed index" and it was not: nothing was ever
     * written to the pump, and with mylife Lotus set to 3 U/min a bolus commanded by AAPS still ran
     * at 31.2 U/min (§8.6). All it configured was a timeout estimate and a log line. It is a real
     * setting for the first time now that the driver does what Lotus does — issue ordinary standard
     * boluses on a timer.
     *
     * The stored values are self-describing rather than indices, because the option this list is
     * missing (6 U/min) belongs in the *middle* of its range, and adding it to an index list would
     * silently re-point every stored preference at a different rate.
     *
     * ⚠ **The default is `unlimited` — no chunking**, which is the delivery path every hardware
     * phase from 1 to 8 ran. Phase 9 flips it, or does not, on the evidence; Phase 7 set the
     * precedent that a default is a statement the shipped build is ready.
     *
     * The four options are the ones mylife Lotus offers, with Lotus's parameters. 6 U/min was held
     * out of the list until 9a-9e had all passed, which they did on 2026-08-04.
     *
     * ⚠ The first option is "no splitting", NOT "the pump's speed setting". The pump has no speed
     * setting — that fiction is what §8.6 spent a session disproving, and a label saying otherwise
     * puts it straight back. Every option describes what the *phone* will do, and the chunk
     * parameters are shown so it is obvious that is what is being configured.
     */
    YpsoBolusSpeed(
        key = "ypso_bolus_speed",
        // Taken from BolusSpeed rather than written out again. The stored values are "unlimited",
        // "12", "6" and "3", and a typo here would not fail to compile - it would silently stop
        // matching a stored preference and fall back to the default, which changes how a bolus is
        // delivered without saying so.
        defaultValue = BolusSpeed.Unlimited.storedValue,
        titleResId = R.string.ypso_bolus_speed_title,
        summaryResId = R.string.ypso_bolus_speed_summary,
        preferenceType = PreferenceType.LIST,
        entries = mapOf(
            BolusSpeed.Unlimited.storedValue to TextRef.AndroidRes(R.string.ypso_bolus_speed_unlimited),
            BolusSpeed.Twelve.storedValue to TextRef.AndroidRes(R.string.ypso_bolus_speed_twelve),
            BolusSpeed.Six.storedValue to TextRef.AndroidRes(R.string.ypso_bolus_speed_six),
            BolusSpeed.Three.storedValue to TextRef.AndroidRes(R.string.ypso_bolus_speed_three)
        )
    ),
    ;

    override val title: TextRef = TextRef.AndroidRes(titleResId)
    override val summary: TextRef? = summaryResId?.let { TextRef.AndroidRes(it) }
}
