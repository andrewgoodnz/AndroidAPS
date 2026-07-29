package app.aaps.pump.ypso.keys

import app.aaps.core.keys.interfaces.DoubleNonPreferenceKey

enum class YpsoDoubleNonKey(
    override val key: String,
    override val defaultValue: Double,
    override val exportable: Boolean = true
) : DoubleNonPreferenceKey {

    Reservoir("ypso_reservoir", 0.0),
    LastBolusAmount("ypso_last_bolus_amount", 0.0),

    /**
     * The pump's own maximum bolus, from the Lotus export. This is the pump-side cap the driver
     * will clamp to; AAPS's own `SafetyMaxBolus` is the user's setting and is left alone.
     */
    MaxBolus("ypso_max_bolus", 10.0),
}
