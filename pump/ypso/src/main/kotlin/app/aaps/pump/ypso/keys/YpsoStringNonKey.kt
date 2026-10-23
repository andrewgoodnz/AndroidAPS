package app.aaps.pump.ypso.keys

import app.aaps.core.keys.interfaces.StringNonPreferenceKey

/**
 * Driver state that is persisted but never shown as an editable preference.
 *
 * The MAC is written by the pairing screen and is the single source of truth for which pump
 * this driver talks to — the vendored SDK deliberately keeps no device store of its own.
 */
enum class YpsoStringNonKey(
    override val key: String,
    override val defaultValue: String,
    // Pump runtime state and pump identity, not a user setting. Kept OUT of a settings export:
    // these are facts about one physical pump, and carrying them into another phone's import
    // would hand it a MAC address, a serial and a stale reservoir reading for a pump it is not
    // paired to. The driver re-reads every one of them from the pump on connection.
    override val exportable: Boolean = false
) : StringNonPreferenceKey {

    MacAddress("ypso_mac_address", ""),
    DeviceName("ypso_device_name", ""),
    SerialNumber("ypso_serial_number", ""),
    FirmwareVersion("ypso_firmware_version", ""),

}
