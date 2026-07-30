package com.ypsopump.sdk

/**
 * The pump history event types a caller needs to see through
 * [com.pump.common.PumpHistoryEvent.Unknown].
 *
 * `PumpHistoryEvent` is the hardware-agnostic contract from pump-common and has no variants for
 * consumable changes, so those events come back as `Unknown` carrying their raw type. These are
 * the IDs worth recognising, re-exported from the SDK's internal `EventType`.
 */
object YpsoEventTypes {

    /** Priming finished. `value1` is the primed amount in centi-units. */
    const val PRIMING_FINISHED = 4

    /** Cartridge rewind finished — the mechanical half of a reservoir change. */
    const val REWIND_FINISHED = 16

    /** Battery removed. */
    const val BATTERY_REMOVED = 24

    /** Cannula priming finished — the pump's marker for a new infusion site. */
    const val CANNULA_PRIMING_FINISHED = 25

    /**
     * A prime larger than this (in units) is treated as a tubing change rather than a top-up.
     * The 9 U threshold comes from the mylife Lotus configuration.
     */
    const val TUBE_CHANGE_PRIME_THRESHOLD_UNITS = 9.0

    // Alert codes, as carried by PumpHistoryEvent.Alert.code. The ones re-exported here are those
    // a caller has to be able to act on — every one of them means insulin has stopped.

    /** Battery removed while running. */
    const val A_BATTERY_REMOVED = 100

    /** Battery flat. */
    const val A_BATTERY_EMPTY = 101

    /** Reusable (pump body) error. */
    const val A_REUSABLE_ERROR = 102

    /** No cartridge fitted. */
    const val A_NO_CARTRIDGE = 103

    /** Cartridge empty. */
    const val A_CARTRIDGE_EMPTY = 104

    /** Occlusion — the line is blocked and insulin is not reaching the patient. */
    const val A_OCCLUSION = 105

    /** The pump stopped itself. */
    const val A_AUTO_STOP = 106

    /** Reads `value1` back out of [com.pump.common.PumpHistoryEvent.Unknown.rawData], in units. */
    fun primedUnits(rawData: ByteArray): Double {
        if (rawData.size < 2) return 0.0
        val centiUnits = (rawData[0].toInt() and 0xFF) or ((rawData[1].toInt() and 0xFF) shl 8)
        return centiUnits / 100.0
    }
}
