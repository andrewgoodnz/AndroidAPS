package com.ypsopump.sdk.internal.data

import com.ypsopump.sdk.internal.protocol.BolusNotificationStatus
import com.ypsopump.sdk.internal.protocol.DeliveryMode
import com.ypsopump.sdk.internal.protocol.EventType
import com.ypsopump.sdk.internal.protocol.YpsoCrc
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar

/**
 * Datos de estado del sistema de la bomba, obtenidos descifrando la característica
 * [com.ypsopump.sdk.internal.ble.YpsoPumpUuids.CHAR_SYSTEM_STATUS] (`fcbee48b7bc5`).
 *
 * @property deliveryMode Modo de entrega actual (ver [com.ypsopump.sdk.internal.protocol.DeliveryMode]).
 * @property deliveryModeName Nombre legible del modo de entrega actual.
 * @property insulinRemaining Insulina restante en el reservorio, en unidades (U).
 * @property batteryPercent Nivel de batería actual, en porcentaje (0–100).
 */
/**
 * The full 18-byte system status payload, mapped against the pump's own display:
 *
 * ```
 * 0A  2B 16 00 00  02  9C 00 00 00  82 00 00 00  F0 00 00 00
 * │   └reservoir┘  │   └── rate ─┘  └─ TBR % ─┘  └─ TBR min ┘
 * └mode           battery bars
 * ```
 *
 * @property currentBasalRate the rate the pump is *actually* running, i.e. the programmed rate
 *   already scaled by [tbrPercent] — 1.20 U/h under a 130% TBR reads as 1.56 U/h.
 * @property tbrPercent 100 when no temporary basal is running.
 * @property tbrRemainingMinutes counts down once a minute; 0 when no TBR is running.
 */
internal data class SystemStatusData(
    val deliveryMode: Int,
    val deliveryModeName: String,
    val insulinRemaining: Float,
    val batteryPercent: Int,
    val batteryBars: Int,
    val currentBasalRate: Double,
    val tbrPercent: Int,
    val tbrRemainingMinutes: Int,
    val reservoirKnown: Boolean = true
) {

    val isTbrRunning: Boolean get() = tbrPercent != TBR_PERCENT_NONE

    companion object {

        /** The pump reports 100% when no temporary basal is active. */
        const val TBR_PERCENT_NONE = 100

        /**
         * The pump writes -1 into the reservoir field when it has no cartridge to measure — the
         * same "unset" convention as an unprogrammed basal slot (§4c).
         *
         * Observed after an occlusion alarm, where it surfaced as a reservoir of **-0.01 U**. That
         * is worse than useless: `reservoirLevel` feeds AAPS's low-reservoir handling, and a small
         * negative number reads as "empty" rather than "don't know".
         */
        const val RESERVOIR_UNKNOWN_RAW = -1

        /** The pump's battery gauge has four bars; byte 5 carries a bar count, not a percent. */
        const val BATTERY_BARS_MAX = 4

        /** Bytes the driver decodes, i.e. the reply with its trailing CRC already removed. */
        const val PAYLOAD_BYTES = 18

        /**
         * Decodes an 18-byte system-status payload, or returns null if it is short.
         *
         * A pure function on purpose: this is the most safety-critical decode in the driver —
         * reservoir, delivery mode and temporary basal all come from here — and leaving it
         * inline in a BLE callback put it out of reach of any test. [HistoryEntry.parse] and
         * [BolusNotification.parse] were already shaped this way.
         *
         * @param payload the reply **without** its CRC.
         */
        fun parse(payload: ByteArray): SystemStatusData? {
            if (payload.size < PAYLOAD_BYTES) return null

            fun le32(at: Int) = ByteBuffer.wrap(payload, at, 4).order(ByteOrder.LITTLE_ENDIAN).int

            val mode = payload[0].toInt() and 0xFF
            val reservoirRaw = le32(1)
            // Byte 5 is a bar count, verified against a pump displaying "2 of 4 bars" for a raw
            // value of 2. Upstream passed it through as a percentage, turning a half-charged
            // battery into a 2% reading — and AAPS acts on pump battery level.
            val bars = payload[5].toInt() and 0xFF
            return SystemStatusData(
                deliveryMode = mode,
                deliveryModeName = DeliveryMode.nameFor(mode),
                insulinRemaining = if (reservoirRaw == RESERVOIR_UNKNOWN_RAW) 0f else reservoirRaw / 100f,
                reservoirKnown = reservoirRaw != RESERVOIR_UNKNOWN_RAW,
                batteryPercent = (bars.coerceIn(0, BATTERY_BARS_MAX) * 100) / BATTERY_BARS_MAX,
                batteryBars = bars,
                currentBasalRate = le32(6) / 100.0,
                tbrPercent = le32(10),
                tbrRemainingMinutes = le32(14)
            )
        }
    }
}

/**
 * The pump's own date and time, read back from `CHAR_SYSTEM_DATE` and `CHAR_SYSTEM_TIME`.
 *
 * Held as separate calendar fields rather than as a timestamp so that a wrong field layout is
 * visible as nonsense — month 34 rather than a plausible-looking millisecond value. That is what
 * [isPlausible] checks, and it is the reason the clock is read before it is ever written: the
 * read confirms the layout this code assumes.
 *
 * @property dateWire raw bytes of the date characteristic, as hex.
 * @property timeWire raw bytes of the time characteristic, as hex. Together these are the only
 *   record of what this firmware actually puts on the wire for the clock, and the write path
 *   derives its envelope from the same read — see `YpsoBleManager.syncTime`.
 */
internal data class PumpClockData(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val dateWire: String,
    val timeWire: String
) {

    /** Interpreted in the phone's own zone, which is the clock the pump is meant to agree with. */
    fun epochMillis(): Long = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, hour, minute, second)
    }.timeInMillis

    fun formatted(): String = "%04d-%02d-%02d %02d:%02d:%02d".format(year, month, day, hour, minute, second)

    /**
     * Whether these fields could be a real date at all.
     *
     * A read that fails this did not come from the layout this code assumes, so nothing may be
     * written back to those characteristics on the strength of it.
     */
    val isPlausible: Boolean
        get() = year in PLAUSIBLE_YEARS && month in 1..12 && day in 1..31 &&
            hour in 0..23 && minute in 0..59 && second in 0..59

    companion object {

        /** The pump counts from 2000-01-01, so nothing outside this century is a real reading. */
        private val PLAUSIBLE_YEARS = 2000..2099
    }
}

/**
 * Estado detallado del bolo en curso, leído de la característica
 * [com.ypsopump.sdk.internal.ble.YpsoPumpUuids.CHAR_BOLUS_STATUS] (`fcbee28b7bc5`).
 *
 * Esta característica es cifrada y devuelve hasta 42+ bytes con información del
 * bolo rápido (inmediato) y del bolo lento (extendido) simultáneamente.
 *
 * Los valores de insulina están en unidades (U), convertidos desde centiunidades.
 *
 * @property fastStatus Estado del bolo rápido (ver [com.ypsopump.sdk.internal.protocol.BolusNotificationStatus]).
 * @property fastSequence Número de secuencia del bolo rápido.
 * @property fastInjected Unidades de bolo rápido ya inyectadas.
 * @property fastTotal Unidades totales programadas para el bolo rápido.
 * @property slowStatus Estado del bolo extendido.
 * @property slowSequence Número de secuencia del bolo extendido.
 * @property slowInjected Unidades de bolo extendido ya inyectadas.
 * @property slowTotal Unidades totales programadas para el bolo extendido.
 * @property slowFastPartInjected Unidades de la parte rápida del bolo extendido ya inyectadas.
 * @property slowFastPartTotal Unidades totales de la parte rápida del bolo extendido.
 * @property actualDuration Duración transcurrida del bolo extendido, en minutos.
 * @property totalDuration Duración total programada del bolo extendido, en minutos.
 */
internal data class BolusStatusData(
    val fastStatus: Int,
    val fastSequence: Long,
    val fastInjected: Float,
    val fastTotal: Float,
    val slowStatus: Int,
    val slowSequence: Long,
    val slowInjected: Float,
    val slowTotal: Float,
    val slowFastPartInjected: Float,
    val slowFastPartTotal: Float,
    val actualDuration: Int,
    val totalDuration: Int
)

/**
 * Entrada del historial de eventos de la bomba YpsoPump.
 *
 * Cada entrada tiene 17 bytes mínimo y se obtiene leyendo la característica
 * [com.ypsopump.sdk.internal.ble.YpsoPumpUuids.Events.VALUE] tras establecer
 * el índice con [com.ypsopump.sdk.internal.ble.YpsoPumpUuids.Events.INDEX].
 *
 * El timestamp de la bomba usa su propia época (2000-01-01 00:00:00 UTC = 946684800 Unix).
 * La función [parse] convierte automáticamente al timestamp Unix estándar.
 *
 * Los valores `value1`, `value2` y `value3` tienen significados distintos según el tipo
 * de evento. Para eventos de bolo, `value1` y `value2` están en centiunidades
 * (dividir entre 100 para obtener unidades). Para TBR, `value1` es el porcentaje
 * y `value2` es la duración en minutos.
 *
 * @property timestamp Timestamp Unix en segundos (convertido desde la época YpsoPump).
 * @property entryType Identificador del tipo de evento (ver [com.ypsopump.sdk.internal.protocol.EventType]).
 * @property entryTypeName Nombre legible del tipo de evento.
 * @property value1 Primer valor del evento (significado depende del tipo).
 * @property value2 Segundo valor del evento (significado depende del tipo).
 * @property value3 Tercer valor del evento (significado depende del tipo).
 * @property sequence Número de secuencia del evento en la bomba.
 * @property index Índice de la entrada en el historial de la bomba.
 */
internal data class HistoryEntry(
    val timestamp: Long,       // Unix timestamp in seconds (converted from YpsoPump epoch)
    val entryType: Int,
    val entryTypeName: String,
    val value1: Int,
    val value2: Int,
    val value3: Int,
    val sequence: Long,
    val index: Int
) {
    companion object {

        /**
         * The pump's epoch, 2000-01-01 00:00:00 — but in the pump's **own local time**, not UTC.
         *
         * ⚠ This is the correction. Treating the counter as seconds since 2000-01-01 *UTC* put every
         * timestamp out by the local UTC offset, which in New Zealand is 12 hours. It went unnoticed
         * because the dates were checked for plausibility and the times were compared as wall-clock
         * readings — and a local counter read as UTC gives back the original wall time, so the
         * numbers *looked* right while the instants were wrong. It surfaced when a bolus recorded at
         * one of these timestamps landed 12 hours in the future and produced no IOB.
         *
         * The pump has no idea what a time zone is: [syncTime] writes local hour/minute/second into
         * it, so its counter measures local wall-clock seconds. Converting therefore has to go
         * through a local calendar rather than adding a fixed offset, which also gets DST right —
         * a fixed offset would be wrong for half the year.
         */
        private const val YPSO_EPOCH_YEAR = 2000

        fun parse(data: ByteArray): HistoryEntry {
            require(data.size >= 17) { "History entry must be at least 17 bytes, got ${data.size}" }
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val pumpSeconds = buf.int.toLong() and 0xFFFFFFFFL
            val timestamp = pumpSecondsToUnix(pumpSeconds)
            val entryType = buf.get().toInt() and 0xFF
            val value1 = buf.short.toInt() and 0xFFFF
            val value2 = buf.short.toInt() and 0xFFFF
            val value3 = buf.short.toInt() and 0xFFFF
            val sequence = buf.int.toLong() and 0xFFFFFFFFL
            val index = buf.short.toInt() and 0xFFFF
            return HistoryEntry(
                timestamp = timestamp,
                entryType = entryType,
                entryTypeName = EventType.name(entryType),
                value1 = value1,
                value2 = value2,
                value3 = value3,
                sequence = sequence,
                index = index
            )
        }

        /**
         * Converts the pump's counter — local wall-clock seconds since 2000-01-01 — to Unix seconds.
         *
         * Goes through a local [Calendar] rather than adding a constant, so the answer is right on
         * both sides of a daylight-saving change.
         */
        fun pumpSecondsToUnix(pumpSeconds: Long): Long {
            val cal = Calendar.getInstance().apply {
                clear()
                set(YPSO_EPOCH_YEAR, Calendar.JANUARY, 1, 0, 0, 0)
            }
            // Added in two steps: the seconds count exceeds what Calendar.add takes comfortably in
            // one go for dates far from the epoch.
            cal.add(Calendar.DAY_OF_YEAR, (pumpSeconds / 86_400L).toInt())
            cal.add(Calendar.SECOND, (pumpSeconds % 86_400L).toInt())
            return cal.timeInMillis / 1000L
        }
    }
}

/**
 * Notificación de estado de bolo recibida por BLE desde la característica
 * [com.ypsopump.sdk.internal.ble.YpsoPumpUuids.CHAR_BOLUS_NOTIFICATION] (`fcbee58b7bc5`).
 *
 * Esta característica **no está cifrada** y envía 13 bytes con CRC al final (2 bytes).
 * El payload útil es de 10 bytes con el formato:
 * `fast_status(1B) + fast_seq(4B LE) + slow_status(1B) + slow_seq(4B LE)`
 *
 * La función [parse] elimina el CRC si está presente y parsea el payload.
 * Si los datos son insuficientes o están malformados, devuelve `null`.
 *
 * @property fastStatus Estado del bolo rápido (ver [com.ypsopump.sdk.internal.protocol.BolusNotificationStatus]).
 * @property fastStatusName Nombre legible del estado del bolo rápido.
 * @property fastSequence Número de secuencia del bolo rápido.
 * @property slowStatus Estado del bolo extendido (lento).
 * @property slowStatusName Nombre legible del estado del bolo extendido.
 * @property slowSequence Número de secuencia del bolo extendido.
 */
internal data class BolusNotification(
    val fastStatus: Int,
    val fastStatusName: String,
    val fastSequence: Long,
    val slowStatus: Int,
    val slowStatusName: String,
    val slowSequence: Long
) {
    companion object {
        fun parse(data: ByteArray): BolusNotification? {
            if (data.size < 10) return null
            // Strip CRC if present (12+ bytes = 10B payload + 2B CRC)
            val payload = if (data.size >= 12 && YpsoCrc.isValid(data)) {
                data.copyOfRange(0, data.size - 2)
            } else {
                data
            }
            if (payload.size < 10) return null
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val fastStatus = buf.get().toInt() and 0xFF
            val fastSeq = buf.int.toLong() and 0xFFFFFFFFL
            val slowStatus = buf.get().toInt() and 0xFF
            val slowSeq = buf.int.toLong() and 0xFFFFFFFFL
            return BolusNotification(
                fastStatus = fastStatus,
                fastStatusName = BolusNotificationStatus.name(fastStatus),
                fastSequence = fastSeq,
                slowStatus = slowStatus,
                slowStatusName = BolusNotificationStatus.name(slowStatus),
                slowSequence = slowSeq
            )
        }
    }
}
