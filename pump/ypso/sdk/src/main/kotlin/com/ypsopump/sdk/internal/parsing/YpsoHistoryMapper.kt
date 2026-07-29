package com.ypsopump.sdk.internal.parsing

import com.pump.common.BolusType
import com.pump.common.PumpHistoryEvent
import com.ypsopump.sdk.internal.data.HistoryEntry
import com.ypsopump.sdk.internal.protocol.EventType

/**
 * Turns a raw pump history entry into the `PumpHistoryEvent` that AAPS syncs.
 *
 * Pure and free-standing on purpose. This mapping decides what reaches `PumpSync` — the amount
 * of every bolus and the identity it is stored under — so it is the piece of the driver most
 * worth being able to test without a pump attached. It used to be a private method on
 * `YpsoPumpManagerImpl`, which cannot be constructed without a `Context` and a BLE stack.
 */
internal object YpsoHistoryMapper {

    /**
     * @return the mapped event, or null for entry types that deliberately reach nothing —
     *   notably type 12 `Date Changed`, whose own timestamp predates the change it records.
     */
    fun toHistoryEvent(entry: HistoryEntry): PumpHistoryEvent? {
        val ts = entry.timestamp * 1000L

        // `position` becomes the PumpSync pumpId, so it has to be stable for the life of the
        // event. The slot index is NOT: index 0 is the most recent entry, so every new event
        // renumbers every older one, and today's bolus inherits yesterday's index. Using it
        // would make PumpSync merge unrelated boluses or reject real ones as duplicates —
        // silent, persistent IOB corruption.
        //
        // The pump's own sequence number is the stable identifier: verified monotonic and
        // unique across a 50-entry read, and it does not move when new events arrive. This
        // deliberately departs from plan section 4, which specified the slot index.
        val pos = entry.sequence

        return when (entry.entryType) {
            EventType.BOLUS_IMMEDIATE       ->
                PumpHistoryEvent.BolusDelivered(entry.value1 / 100.0, BolusType.STANDARD, ts, pos)

            EventType.BOLUS_DELAYED         ->
                PumpHistoryEvent.BolusDelivered(entry.value1 / 100.0, BolusType.EXTENDED, ts, pos)

            EventType.BOLUS_COMBINED        ->
                PumpHistoryEvent.BolusDelivered(entry.value1 / 100.0, BolusType.MULTIWAVE, ts, pos)

            EventType.BOLUS_BLIND           ->
                PumpHistoryEvent.BolusDelivered(entry.value1 / 100.0, BolusType.STANDARD, ts, pos)

            EventType.BASAL_PROFILE_TEMP_RUNNING ->
                PumpHistoryEvent.TbrStarted(entry.value1, entry.value2, ts, pos)

            EventType.BASAL_PROFILE_TEMP,
            EventType.BASAL_PROFILE_TEMP_ABORT   ->
                PumpHistoryEvent.TbrEnded(ts, pos)

            EventType.BASAL_PROFILE_CHANGED,
            EventType.BASAL_PROFILE_A_CHANGED,
            EventType.BASAL_PROFILE_B_CHANGED    ->
                PumpHistoryEvent.BasalRateChanged(entry.value1 / 100.0, ts, pos)

            // Consumable changes. The pump does not have a single "cartridge changed" event;
            // it records the mechanical steps, and the caller turns those into therapy events.
            // value1 carries the primed amount in centi-units, which is what distinguishes a
            // real tubing change from a small top-up prime.
            EventType.REWIND_FINISHED            ->
                PumpHistoryEvent.CartridgeChanged(ts, pos)

            EventType.PRIMING_FINISHED,
            EventType.CANNULA_PRIMING_FINISHED,
            EventType.BATTERY_REMOVED            ->
                PumpHistoryEvent.Unknown(entry.entryType, encodeValues(entry), ts, pos)

            in EventType.A_BATTERY_REMOVED..EventType.A_BATTERY_REJECTED ->
                PumpHistoryEvent.Alert(entry.entryType, entry.entryTypeName, ts, pos)

            else                                 -> null
        }
    }

    /**
     * Packs an entry's three value fields into [PumpHistoryEvent.Unknown.rawData] so the caller
     * can still reach them — the prime amount decides whether a prime was a tubing change.
     * Little-endian 16-bit each, matching how the pump laid them out.
     */
    private fun encodeValues(entry: HistoryEntry): ByteArray = byteArrayOf(
        (entry.value1 and 0xFF).toByte(), ((entry.value1 shr 8) and 0xFF).toByte(),
        (entry.value2 and 0xFF).toByte(), ((entry.value2 shr 8) and 0xFF).toByte(),
        (entry.value3 and 0xFF).toByte(), ((entry.value3 shr 8) and 0xFF).toByte()
    )
}
