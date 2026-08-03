package app.aaps.pump.ypso.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey

enum class YpsoStringKey(
    override val key: String,
    override val defaultValue: String,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val isPassword: Boolean = false,
    override val isPin: Boolean = false,
    override val exportable: Boolean = true
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
     */
    YpsoBolusSpeed("ypso_bolus_speed", "unlimited"),
}
