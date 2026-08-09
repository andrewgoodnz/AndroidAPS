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
     * The one bolus that has been sent to the pump but whose outcome AAPS has not yet resolved,
     * as `temporaryId|requestedUnits|baselineSequence`, or empty when there is none.
     *
     * ⚠ **This exists because the provisional record cannot be found again without it.** `PumpSync`
     * can settle a `temporaryId` it is handed, but offers no way to *list* unsettled ones, and the
     * driver must survive its own process dying between sending a bolus and confirming it — which
     * Phase 10b did, deliberately, with `am force-stop`. An in-memory marker would not have been
     * there afterwards.
     *
     * `baselineSequence` is the pump's newest event sequence immediately before the command went
     * out. It is what makes reconciliation exact rather than a guess: the bolus this driver is
     * looking for is the first `BolusDelivered` *above* that number, which is the same rule the
     * live confirmation path uses. Matching on amount would confuse two identical boluses, and
     * matching on the pump's clock is unsafe — §5 has already produced an entry 12 hours out.
     */
    PendingBolus("ypso_pending_bolus", ""),

}
