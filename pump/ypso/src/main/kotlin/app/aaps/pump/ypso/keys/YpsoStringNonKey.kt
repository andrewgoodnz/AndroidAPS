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
    override val exportable: Boolean = true
) : StringNonPreferenceKey {

    MacAddress("ypso_mac_address", ""),
    DeviceName("ypso_device_name", ""),
    SerialNumber("ypso_serial_number", ""),
    FirmwareVersion("ypso_firmware_version", ""),

}
