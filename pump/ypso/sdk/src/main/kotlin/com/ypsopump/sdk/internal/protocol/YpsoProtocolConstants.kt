package com.ypsopump.sdk.internal.protocol

/**
 * Modos de entrega de insulina de la bomba YpsoPump.
 *
 * Estos valores corresponden al byte 0 del payload descifrado de la característica
 * [YpsoPumpUuids.CHAR_SYSTEM_STATUS] (`fcbee48b7bc5`). Indican el estado actual
 * de infusión de la bomba en tiempo real.
 *
 * La función [name] convierte un valor numérico en una cadena legible para la UI.
 */
internal object DeliveryMode {

    /**
     * **Upstream's table, and it does not match firmware V05.02.03.**
     *
     * Checked against the pump's own status screen: with the pump displaying **Stopped**, byte 0
     * of the system status read `3`, which this table calls `BOLUS_FAST`. Reporting that as a
     * bolus in progress also made [isDelivering] true, so AAPS believed a stopped pump was
     * running basal — the opposite of the truth, and the direction that matters, because
     * anything reasoning about insulin on board would have assumed background delivery that was
     * not happening.
     *
     * These constants are kept only for reference. Use [nameFor] and [isDeliveringOrNull], which
     * report what has actually been confirmed on hardware and nothing more.
     */
    const val STOPPED = 0
    const val BASAL = 1
    const val TBR = 2
    const val BOLUS_FAST = 3
    const val BOLUS_EXTENDED = 4
    const val BOLUS_AND_BASAL = 5
    const val PRIMING = 6
    const val PAUSED = 7

    // Byte-0 values observed on V05.02.03, each confirmed by watching the pump's own display
    // while it was switched between states.

    /** Pump display read "Stopped". */
    const val V05_STOPPED = 3

    /** Pump display read "Running"; the reservoir fell and byte 6 began reporting a rate. */
    const val V05_RUNNING = 10

    private val CONFIRMED_NOT_DELIVERING = setOf(V05_STOPPED)
    private val CONFIRMED_DELIVERING = setOf(V05_RUNNING)

    /**
     * Whether the pump is delivering, or `null` when this raw value has never been checked
     * against the pump's display.
     *
     * Callers must treat `null` as "don't know" and must not turn it into "delivering" —
     * telling AAPS that basal is running when it isn't is the error that causes harm.
     */
    fun isDeliveringOrNull(mode: Int): Boolean? = when (mode) {
        in CONFIRMED_DELIVERING     -> true
        in CONFIRMED_NOT_DELIVERING -> false
        else                        -> null
    }

    /** Names only what has been verified; anything else says plainly that it is a guess. */
    fun nameFor(mode: Int): String = when (mode) {
        V05_STOPPED -> "Stopped"
        V05_RUNNING -> "Running"
        else        -> "Unverified mode $mode"
    }
}

/**
 * Identificadores de tipo para las entradas del historial de la bomba YpsoPump.
 *
 * Cada entrada del historial (leída desde [YpsoPumpUuids.Events]) contiene un byte
 * de tipo que identifica el evento. Los IDs aquí definidos coinciden con los de la
 * referencia Python (`Ypso-main/pump/constants.py`, `EVENT_NAMES`), confirmados
 * empíricamente en el firmware V05.02.03.
 *
 * Los eventos se agrupan en tres categorías:
 * - **Eventos de bolo**: inmediatos, extendidos, combinados, ciegos y sus variantes
 *   (en curso, completado, abortado).
 * - **Eventos de basal**: cambios de perfil, TBR (tasa basal temporal) y sus variantes.
 * - **Eventos del sistema**: cebado, cambios de fecha/hora, totales diarios, alertas.
 *
 * La función [name] convierte un ID numérico en su nombre legible correspondiente.
 */
internal object EventType {
    // Bolus events (from pump firmware, confirmed via Python reference)
    const val BOLUS_DELAYED_RUNNING = 1        // Extended bolus in progress
    const val BOLUS_IMMEDIATE = 2              // Fast bolus completed
    const val BOLUS_DELAYED = 3                // Extended bolus completed
    const val BOLUS_STEP_CHANGED = 5           // Bolus step changed
    const val BOLUS_COMBINED_RUNNING = 17      // Combined bolus in progress
    const val BOLUS_COMBINED = 18              // Combined bolus completed
    const val BOLUS_IMMEDIATE_RUNNING = 19     // Fast bolus in progress
    const val BOLUS_DELAYED_BACKUP = 20        // Extended bolus backup
    const val BOLUS_COMBINED_BACKUP = 21       // Combined bolus backup
    const val BOLUS_BLIND = 26                 // Blind bolus
    const val BOLUS_BLIND_RUNNING = 27         // Blind bolus in progress
    const val BOLUS_BLIND_ABORT = 28           // Blind bolus aborted
    const val BOLUS_IMMEDIATE_ABORT = 29       // Fast bolus aborted
    const val BOLUS_DELAYED_ABORT = 30         // Extended bolus aborted
    const val BOLUS_COMBINED_ABORT = 31        // Combined bolus aborted
    const val BOLUS_AMOUNT_CAP_CHANGED = 33    // Bolus amount cap changed

    // Basal events
    const val BASAL_PROFILE_CHANGED = 6
    const val BASAL_PROFILE_A_CHANGED = 7
    const val BASAL_PROFILE_B_CHANGED = 8
    const val BASAL_PROFILE_TEMP_RUNNING = 9   // TBR in progress
    const val BASAL_PROFILE_TEMP = 10          // TBR completed
    const val BASAL_PROFILE_TEMP_BACKUP = 22   // TBR backup
    const val BASAL_PROFILE_TEMP_ABORT = 32    // TBR aborted
    const val BASAL_RATE_CAP_CHANGED = 34

    // System events
    const val PRIMING_FINISHED = 4
    const val DATE_CHANGED = 12
    const val TIME_CHANGED = 13
    const val PUMP_MODE_CHANGED = 14
    const val REWIND_FINISHED = 16
    const val DAILY_TOTAL_INSULIN = 23
    const val BATTERY_REMOVED = 24
    const val CANNULA_PRIMING_FINISHED = 25
    const val DELIVERY_STATUS_CHANGED = 150

    // Alert events
    const val A_BATTERY_REMOVED = 100
    const val A_BATTERY_EMPTY = 101
    const val A_REUSABLE_ERROR = 102
    const val A_NO_CARTRIDGE = 103
    const val A_CARTRIDGE_EMPTY = 104
    const val A_OCCLUSION = 105
    const val A_AUTO_STOP = 106
    const val A_LIPO_DISCHARGED = 107
    const val A_BATTERY_REJECTED = 108

    fun name(type: Int): String = when (type) {
        BOLUS_DELAYED_RUNNING -> "Bolus Delayed Running"
        BOLUS_IMMEDIATE -> "Bolus Immediate"
        BOLUS_DELAYED -> "Bolus Delayed"
        PRIMING_FINISHED -> "Priming Finished"
        BOLUS_STEP_CHANGED -> "Bolus Step Changed"
        BASAL_PROFILE_CHANGED -> "Basal Profile Changed"
        BASAL_PROFILE_A_CHANGED -> "Basal Profile A Changed"
        BASAL_PROFILE_B_CHANGED -> "Basal Profile B Changed"
        BASAL_PROFILE_TEMP_RUNNING -> "Basal Profile Temp Running"
        BASAL_PROFILE_TEMP -> "Basal Profile Temp"
        DATE_CHANGED -> "Date Changed"
        TIME_CHANGED -> "Time Changed"
        PUMP_MODE_CHANGED -> "Pump Mode Changed"
        REWIND_FINISHED -> "Rewind Finished"
        BOLUS_COMBINED_RUNNING -> "Bolus Combined Running"
        BOLUS_COMBINED -> "Bolus Combined"
        BOLUS_IMMEDIATE_RUNNING -> "Bolus Immediate Running"
        BOLUS_DELAYED_BACKUP -> "Bolus Delayed Backup"
        BOLUS_COMBINED_BACKUP -> "Bolus Combined Backup"
        BASAL_PROFILE_TEMP_BACKUP -> "Basal Profile Temp Backup"
        DAILY_TOTAL_INSULIN -> "Daily Total Insulin"
        BATTERY_REMOVED -> "Battery Removed"
        CANNULA_PRIMING_FINISHED -> "Cannula Priming Finished"
        BOLUS_BLIND -> "Bolus Blind"
        BOLUS_BLIND_RUNNING -> "Bolus Blind Running"
        BOLUS_BLIND_ABORT -> "Bolus Blind Abort"
        BOLUS_IMMEDIATE_ABORT -> "Bolus Immediate Abort"
        BOLUS_DELAYED_ABORT -> "Bolus Delayed Abort"
        BOLUS_COMBINED_ABORT -> "Bolus Combined Abort"
        BASAL_PROFILE_TEMP_ABORT -> "Basal Profile Temp Abort"
        BOLUS_AMOUNT_CAP_CHANGED -> "Bolus Amount Cap Changed"
        BASAL_RATE_CAP_CHANGED -> "Basal Rate Cap Changed"
        A_BATTERY_REMOVED -> "Alert: Battery Removed"
        A_BATTERY_EMPTY -> "Alert: Battery Empty"
        A_REUSABLE_ERROR -> "Alert: Reusable Error"
        A_NO_CARTRIDGE -> "Alert: No Cartridge"
        A_CARTRIDGE_EMPTY -> "Alert: Cartridge Empty"
        A_OCCLUSION -> "Alert: Occlusion"
        A_AUTO_STOP -> "Alert: Auto Stop"
        A_LIPO_DISCHARGED -> "Alert: LiPo Discharged"
        A_BATTERY_REJECTED -> "Alert: Battery Rejected"
        DELIVERY_STATUS_CHANGED -> "Delivery Status Changed"
        else -> "Unknown($type)"
    }
}

/**
 * Valores de estado de notificación de bolo para la característica
 * [YpsoPumpUuids.CHAR_BOLUS_NOTIFICATION] (`fcbee58b7bc5`).
 *
 * La característica de notificación de bolo envía 13 bytes sin cifrar (con CRC en los
 * 2 últimos bytes). El payload de 10 bytes contiene:
 * `fast_status(1B) + fast_seq(4B LE) + slow_status(1B) + slow_seq(4B LE)`
 *
 * Los valores de estado han sido confirmados empíricamente con el firmware V05.02.03.
 * Nota: el valor 4 ([COMPLETED]) fue inicialmente confundido con un error; la bomba
 * lo envía en las entregas exitosas.
 *
 * La función [isTerminal] devuelve `true` para los estados que indican fin de la entrega
 * (completado o cancelado), lo que permite detener la espera activa de notificaciones.
 */
internal object BolusNotificationStatus {
    const val IDLE = 0
    const val DELIVERING = 1
    const val CANCELLED = 3
    const val COMPLETED = 4   // confirmed: pump sends 4 on successful delivery

    fun name(status: Int): String = when (status) {
        IDLE -> "Idle"
        DELIVERING -> "Delivering"
        CANCELLED -> "Cancelled"
        COMPLETED -> "Completed"
        else -> "Unknown($status)"
    }

    /**
     * Whether this status, **seen after `DELIVERING`**, means delivery has finished.
     *
     * ⚠ This was `status != IDLE && status != DELIVERING`, which makes every unmapped value
     * terminal. Cancelling a 4 U bolus on the pump produced status **2** — a value not in this
     * table — and the driver concluded delivery was over while the pump was still revising its
     * event log, then read the requested amount out of it and reported that as delivered.
     *
     * `IDLE` is here and `2` is not, which is the whole distinction. `IDLE` is a *mapped* state
     * meaning nothing is running, and §4f established that this pump **clears** its bolus status
     * when delivery ends — so `IDLE` after `DELIVERING` is exactly "it finished and the status was
     * cleared". `2` is an intermediate nobody has characterised.
     *
     * Both of the observed endings are covered: a completed bolus polls `DELIVERING → IDLE` (the
     * `COMPLETED` notification arrives on the notify characteristic and the *polled* status has
     * already been cleared by then), and a pump-side cancel polls `DELIVERING → 2 → IDLE`.
     *
     * Anything unmapped means keep waiting, bounded by the caller's own timeout — which is what
     * caught both cases before this was tightened, at the cost of up to a minute of waiting on a
     * large bolus cancelled early.
     */
    fun hasFinished(status: Int): Boolean = status == CANCELLED || status == COMPLETED || status == IDLE
}

/**
 * Índices de configuración para el par de características SETTING_ID / SETTING_VALUE.
 *
 * Para leer o escribir un ajuste, se escribe el índice en [YpsoPumpUuids.CHAR_SETTING_ID]
 * (`fcbeb3147bc5`) codificado como GLB (ver [YpsoGlb]), y luego se lee o escribe el valor
 * en [YpsoPumpUuids.CHAR_SETTING_VALUE] (`fcbeb4147bc5`).
 *
 * Los índices de perfil de basal están organizados en rangos:
 * - Perfil A: índices [PROGRAM_A_START] a [PROGRAM_A_END] (14 a 37), valor de referencia [PROGRAM_A_VALUE].
 * - Perfil B: índices [PROGRAM_B_START] a [PROGRAM_B_END] (38 a 61), valor de referencia [PROGRAM_B_VALUE].
 */
internal object SettingsIndex {
    const val ACTIVE_PROGRAM = 1
    const val PROGRAM_A_START = 14
    const val PROGRAM_A_END = 37
    const val PROGRAM_B_START = 38
    const val PROGRAM_B_END = 61

    const val PROGRAM_A_VALUE = 3
    const val PROGRAM_B_VALUE = 10
}
