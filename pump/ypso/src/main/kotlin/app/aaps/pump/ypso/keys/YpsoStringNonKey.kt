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

    /**
     * Pump sequence numbers this driver recorded itself, newest last, comma separated and bounded.
     *
     * History sync skips these. A bolus AAPS delivered is already in the database at the **phone's**
     * timestamp, which is deliberate (see §5) and better evidence than the pump's entry, whose time
     * is only as good as the pump's clock. Re-syncing it would upsert on the same `pumpId` and
     * silently move the treatment to pump time — quietly undoing a decision made after a clock bug
     * put boluses 12 hours into the future.
     */
    SelfRecordedSequences("ypso_self_recorded_sequences", ""),
}
