package app.aaps.pump.ypso.keys

import app.aaps.core.keys.interfaces.DoubleNonPreferenceKey

enum class YpsoDoubleNonKey(
    override val key: String,
    override val defaultValue: Double,
    // Pump runtime state and pump identity, not a user setting. Kept OUT of a settings export:
    // these are facts about one physical pump, and carrying them into another phone's import
    // would hand it a MAC address, a serial and a stale reservoir reading for a pump it is not
    // paired to. The driver re-reads every one of them from the pump on connection.
    override val exportable: Boolean = false
) : DoubleNonPreferenceKey {

    Reservoir("ypso_reservoir", 0.0),
    LastBolusAmount("ypso_last_bolus_amount", 0.0),

    /**
     * The pump's own maximum bolus, from the Lotus export. This is the pump-side cap the driver
     * will clamp to; AAPS's own `SafetyMaxBolus` is the user's setting and is left alone.
     */
    MaxBolus("ypso_max_bolus", 10.0),
}
