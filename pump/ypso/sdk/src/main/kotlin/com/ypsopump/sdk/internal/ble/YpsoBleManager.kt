package com.ypsopump.sdk.internal.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.util.Log
import com.ypsopump.sdk.internal.data.BolusStatusData
import com.ypsopump.sdk.internal.data.HistoryEntry
import com.ypsopump.sdk.internal.data.PumpClockData
import com.ypsopump.sdk.internal.data.SystemStatusData
import com.ypsopump.sdk.internal.protocol.BolusNotificationStatus
import com.ypsopump.sdk.internal.protocol.SettingsIndex
import com.ypsopump.sdk.internal.protocol.YpsoCrc
import com.ypsopump.sdk.internal.protocol.YpsoFraming
import com.ypsopump.sdk.internal.protocol.YpsoGlb
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Calendar
import java.util.UUID

/**
 * Capa de comunicación BLE con la bomba YpsoPump, basada en la librería Nordic BLE.
 *
 * Esta clase gestiona todo el ciclo de vida de la conexión Bluetooth: descubrimiento de servicios
 * y características, autenticación mediante contraseña MD5 derivada de la MAC del dispositivo,
 * lecturas y escrituras cifradas con XChaCha20-Poly1305, el protocolo multi-trama (framing)
 * propio de Ypsomed, y la recepción de notificaciones BLE.
 *
 * Todos los comandos a la bomba pasan por aquí: bolo, TBR, sincronización de hora,
 * lectura de perfil basal, historial, y configuración general.
 *
 * AAPS fork: plaintext only. Upstream supports an optional XChaCha20-Poly1305 layer
 * (`PumpCryptor`) plus a Proregia key exchange; both packages are deliberately absent from
 * this vendored copy, so every payload is sent and received in the clear. This is a
 * structural property of the driver, not a runtime setting — see PORTING_NOTES.md.
 */
internal class YpsoBleManager(context: Context) : BleManager(context) {

    companion object {
        private const val TAG = "YpsoBleManager"
        const val YPSOPUMP_NAME_PREFIX = "YpsoPump_"

        /** `CHAR_SYSTEM_DATE` payload: year u16 LE, month u8, day u8. */
        private const val DATE_PAYLOAD_BYTES = 4

        /** `CHAR_SYSTEM_TIME` payload: hour u8, minute u8, second u8. */
        private const val TIME_PAYLOAD_BYTES = 3

        /**
         * `BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH`. The pump's answer to a write of the wrong
         * length, and the one negative signal this firmware gives reliably — it is refused by the
         * ATT layer, so nothing reaches the pump's application.
         */
        private const val GATT_INVALID_ATTRIBUTE_LENGTH = 13

        /** Confirmed by the pump accepting 13 + 2 CRC where a bare 13 was refused. */
        private const val BOLUS_PAYLOAD_BYTES = 13

        /** Two GLB pairs: percent with its complement, minutes with its complement. */
        private const val TBR_PAYLOAD_BYTES = 16

        /** The pump's "no temporary basal" percentage, in both directions. */
        private const val TBR_PERCENT_NONE = 100

        /** Which arrangement of those 13 bytes to send. See [buildBolusPayload]. */
        private val BOLUS_PAYLOAD_LAYOUT = BolusPayloadLayout.UPSTREAM

        /** Salt used by vicktor's SDK, verified against firmware V05.02.03. */
        val AUTH_SALT_SDK = byteArrayOf(
            0x4F, 0xC2.toByte(), 0x45, 0x4D, 0x9B.toByte(),
            0x81.toByte(), 0x59, 0xA4.toByte(), 0x93.toByte(), 0xBB.toByte()
        )

        /**
         * Salt from Andy Rozman's 2022 `computeUserLevelPassword`. Same construction
         * (MD5 of 6 MAC bytes + 10 salt bytes) but different constant; kept as a
         * fallback in case the research pump's firmware predates the SDK's value.
         */
        val AUTH_SALT_LEGACY = byteArrayOf(
            0x5B, 0xD7.toByte(), 0x10, 0x66, 0x01,
            0xF2.toByte(), 0x4F, 0x3C, 0x8F.toByte(), 0x6B
        )
    }

    /**
     * Candidate arrangements of the 13-byte bolus payload.
     *
     * The length is settled; only the arrangement is open, and hardware has ruled one out already.
     */
    private enum class BolusPayloadLayout {

        /**
         * `total(4) + ~total(4) + duration(2) + ~duration(2) + type(1)`.
         *
         * Matches how every other safety-critical value this pump accepts is framed — the value
         * beside its own bitwise complement.
         */
        GLB_PAIRS,

        /**
         * `total(4) + duration(4) + immediate(4) + type(1)`.
         *
         * **Ruled out on hardware, 2026-07-30.** The pump accepted the write and then did nothing:
         * the bolus status stayed all zeros and the reservoir did not move. Kept so the record of
         * what was tried lives with the code.
         */
        UPSTREAM
    }

    /** Which salt [computeAuthPassword] mixes in. Defaults to the SDK's. */
    var authSalt: ByteArray = AUTH_SALT_SDK

    init {
        connectionObserver = object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {
                Log.d(TAG, "Connecting to ${device.name}...")
            }

            override fun onDeviceConnected(device: BluetoothDevice) {
                Log.d(TAG, "Connected to ${device.name}")
            }

            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                Log.e(TAG, "Failed to connect: reason=$reason")
            }

            override fun onDeviceReady(device: BluetoothDevice) {
                Log.d(TAG, "Device ready: ${device.name}")
            }

            override fun onDeviceDisconnecting(device: BluetoothDevice) {
                Log.d(TAG, "Disconnecting from ${device.name}")
            }

            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                Log.d(TAG, "Disconnected: reason=$reason")
                _isConnected.value = false
                _isAuthenticated.value = false
                onDisconnectCallback?.invoke(reason)
            }
        }
    }

    override fun shouldClearCacheWhenDisconnected(): Boolean = true

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated

    private val _logEvents = MutableSharedFlow<LogEvent>(replay = 100)
    val logEvents: SharedFlow<LogEvent> = _logEvents

    private val _dataReceived = MutableSharedFlow<DataEvent>(replay = 10)
    val dataReceived: SharedFlow<DataEvent> = _dataReceived

    // Standard BLE characteristics
    private var charDeviceName: BluetoothGattCharacteristic? = null
    private var charManufacturer: BluetoothGattCharacteristic? = null
    private var charSoftwareRevision: BluetoothGattCharacteristic? = null

    // Authentication
    private var charAuthPassword: BluetoothGattCharacteristic? = null

    // Version
    private var charMasterVersion: BluetoothGattCharacteristic? = null

    // Control
    private var charBolusStartStop: BluetoothGattCharacteristic? = null
    private var charBolusStatus: BluetoothGattCharacteristic? = null
    private var charTbrStartStop: BluetoothGattCharacteristic? = null
    private var charSystemStatus: BluetoothGattCharacteristic? = null
    private var charBolusNotification: BluetoothGattCharacteristic? = null

    // Time
    private var charSystemDate: BluetoothGattCharacteristic? = null
    private var charSystemTime: BluetoothGattCharacteristic? = null

    // Extended read
    private var charExtendedRead: BluetoothGattCharacteristic? = null

    // Settings
    private var charSettingId: BluetoothGattCharacteristic? = null
    private var charSettingValue: BluetoothGattCharacteristic? = null

    // History - Events
    private var charHistoryEventsCount: BluetoothGattCharacteristic? = null
    private var charHistoryEventsIndex: BluetoothGattCharacteristic? = null
    private var charHistoryEventsValue: BluetoothGattCharacteristic? = null

    // History - Alerts
    private var charHistoryAlertsCount: BluetoothGattCharacteristic? = null
    private var charHistoryAlertsIndex: BluetoothGattCharacteristic? = null
    private var charHistoryAlertsValue: BluetoothGattCharacteristic? = null

    // History - System
    private var charHistorySystemCount: BluetoothGattCharacteristic? = null
    private var charHistorySystemIndex: BluetoothGattCharacteristic? = null
    private var charHistorySystemValue: BluetoothGattCharacteristic? = null

    // Security
    private var charSecurityStatus: BluetoothGattCharacteristic? = null

    private val discoveredServices = mutableListOf<BluetoothGattService>()
    private var connectedDeviceMac: String? = null

    var onDisconnectCallback: ((Int) -> Unit)? = null

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        discoveredServices.clear()
        discoveredServices.addAll(gatt.services)
        connectedDeviceMac = gatt.device.address

        log("Discovered ${gatt.services.size} services")

        gatt.services.forEach { service ->
            service.characteristics.forEach { char ->
                when (char.uuid) {
                    YpsoPumpUuids.CHAR_DEVICE_NAME -> charDeviceName = char
                    YpsoPumpUuids.CHAR_MANUFACTURER -> charManufacturer = char
                    YpsoPumpUuids.CHAR_SOFTWARE -> charSoftwareRevision = char
                    YpsoPumpUuids.CHAR_AUTH_PASSWORD -> charAuthPassword = char
                    YpsoPumpUuids.CHAR_MASTER_VERSION -> charMasterVersion = char
                    YpsoPumpUuids.CHAR_BOLUS_START_STOP -> charBolusStartStop = char
                    YpsoPumpUuids.CHAR_BOLUS_STATUS -> charBolusStatus = char
                    YpsoPumpUuids.CHAR_TBR_START_STOP -> charTbrStartStop = char
                    YpsoPumpUuids.CHAR_SYSTEM_STATUS -> charSystemStatus = char
                    YpsoPumpUuids.CHAR_BOLUS_NOTIFICATION -> charBolusNotification = char
                    YpsoPumpUuids.CHAR_SYSTEM_DATE -> charSystemDate = char
                    YpsoPumpUuids.CHAR_SYSTEM_TIME -> charSystemTime = char
                    YpsoPumpUuids.CHAR_EXTENDED_READ -> charExtendedRead = char
                    YpsoPumpUuids.CHAR_SETTING_ID -> charSettingId = char
                    YpsoPumpUuids.CHAR_SETTING_VALUE -> charSettingValue = char
                    YpsoPumpUuids.Events.COUNT -> charHistoryEventsCount = char
                    YpsoPumpUuids.Events.INDEX -> charHistoryEventsIndex = char
                    YpsoPumpUuids.Events.VALUE -> charHistoryEventsValue = char
                    YpsoPumpUuids.Alerts.COUNT -> charHistoryAlertsCount = char
                    YpsoPumpUuids.Alerts.INDEX -> charHistoryAlertsIndex = char
                    YpsoPumpUuids.Alerts.VALUE -> charHistoryAlertsValue = char
                    YpsoPumpUuids.System.COUNT -> charHistorySystemCount = char
                    YpsoPumpUuids.System.INDEX -> charHistorySystemIndex = char
                    YpsoPumpUuids.System.VALUE -> charHistorySystemValue = char
                    YpsoPumpUuids.CHAR_SEC_STATUS -> charSecurityStatus = char
                }
            }
        }

        val hasSecurityService = gatt.getService(YpsoPumpUuids.SERVICE_SECURITY) != null
        val hasWriteService = gatt.getService(YpsoPumpUuids.SERVICE_WRITE) != null
        return hasSecurityService || hasWriteService || discoveredServices.isNotEmpty()
    }

    override fun onServicesInvalidated() {
        charDeviceName = null
        charManufacturer = null
        charSoftwareRevision = null
        charAuthPassword = null
        charMasterVersion = null
        charBolusStartStop = null
        charBolusStatus = null
        charTbrStartStop = null
        charSystemStatus = null
        charBolusNotification = null
        charSystemDate = null
        charSystemTime = null
        charExtendedRead = null
        charSettingId = null
        charSettingValue = null
        charHistoryEventsCount = null
        charHistoryEventsIndex = null
        charHistoryEventsValue = null
        charHistoryAlertsCount = null
        charHistoryAlertsIndex = null
        charHistoryAlertsValue = null
        charHistorySystemCount = null
        charHistorySystemIndex = null
        charHistorySystemValue = null
        charSecurityStatus = null
        discoveredServices.clear()
        connectedDeviceMac = null
    }

    override fun initialize() {
        log("Initializing connection...")

        enableNotificationOnChar(charBolusStatus, "BolusStatus")
        enableNotificationOnChar(charBolusNotification, "BolusNotification")
        enableNotificationOnChar(charSystemStatus, "SystemStatus")

        _isConnected.value = true
        log("Connection initialized")
    }

    private fun enableNotificationOnChar(char: BluetoothGattCharacteristic?, name: String) {
        char?.let {
            if (it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                setNotificationCallback(it).with { _, data ->
                    val bytes = data.value ?: return@with
                    log("$name notification: ${bytes.toHexString()}")
                    emitData(DataEvent.ProBluetoothNotification(name, bytes))
                }
                enableNotifications(it).enqueue()
                log("Enabled $name notifications")
            } else if (it.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                setNotificationCallback(it).with { _, data ->
                    val bytes = data.value ?: return@with
                    log("$name indication: ${bytes.toHexString()}")
                    emitData(DataEvent.ProBluetoothNotification(name, bytes))
                }
                enableIndications(it).enqueue()
                log("Enabled $name indications")
            }
        }
    }

    // ==================== AUTHENTICATION ====================

    private fun computeAuthPassword(macAddress: String): ByteArray {
        val macBytes = macAddress.replace(":", "")
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

        val buf = macBytes + authSalt
        return MessageDigest.getInstance("MD5").digest(buf)
    }

    /**
     * Autentica la conexión BLE con la bomba.
     *
     * Calcula la contraseña como MD5(MAC_bytes + AUTH_SALT) y la escribe en la característica
     * [YpsoPumpUuids.CHAR_AUTH_PASSWORD]. La bomba acepta o rechaza la contraseña implícitamente:
     * si la escritura tiene éxito, la conexión queda autenticada y se pueden realizar lecturas
     * y escrituras cifradas.
     *
     * @param callback Llamado con `true` si la autenticación fue exitosa, `false` en caso contrario.
     */
    fun authenticate(callback: (Boolean) -> Unit) {
        val char = charAuthPassword
        if (char == null) {
            log("Auth password characteristic not found!")
            callback(false)
            return
        }

        val mac = connectedDeviceMac
        if (mac == null) {
            log("No connected device MAC address!")
            callback(false)
            return
        }

        val password = computeAuthPassword(mac)
        log("Authenticating with MD5 password for MAC $mac")

        writeCharacteristic(char, password, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            .with { _, _ ->
                log("Auth password written successfully")
                _isAuthenticated.value = true
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    callback(true)
                }, 200)
            }
            .fail { _, status ->
                log("Auth write failed with status: $status")
                callback(false)
            }
            .enqueue()
    }

    fun authenticate(passkey: String, callback: (Boolean) -> Unit) {
        authenticate(callback)
    }

    // ==================== PUBLIC API ====================

    fun readDeviceName(callback: (String?) -> Unit) {
        charDeviceName?.let { char ->
            readCharacteristic(char).with { _, data ->
                callback(data.getStringValue(0))
            }.enqueue()
        } ?: callback(null)
    }

    fun readManufacturer(callback: (String?) -> Unit) {
        charManufacturer?.let { char ->
            readCharacteristic(char).with { _, data ->
                callback(data.getStringValue(0))
            }.enqueue()
        } ?: callback(null)
    }

    fun readSoftwareRevision(callback: (String?) -> Unit) {
        charSoftwareRevision?.let { char ->
            readCharacteristic(char).with { _, data ->
                val bytes = data.value
                callback(bytes?.let { formatVersion(it) } ?: "Unknown")
            }.enqueue()
        } ?: callback(null)
    }

    fun readMasterVersion(callback: (String?) -> Unit) {
        charMasterVersion?.let { char ->
            readCharacteristic(char).with { _, data ->
                callback(data.getStringValue(0))
            }.fail { _, _ ->
                callback(null)
            }.enqueue()
        } ?: callback(null)
    }

    fun readCharacteristic(uuid: UUID, callback: (ByteArray?) -> Unit) {
        val char = findCharacteristic(uuid)
        if (char == null) {
            callback(null)
            return
        }

        readCharacteristic(char).with { _, data ->
            callback(data.value)
        }.fail { _, _ ->
            callback(null)
        }.enqueue()
    }

    /**
     * Realiza una lectura multi-trama desde la bomba.
     *
     * Lee primero la característica identificada por [firstUuid]. Si el byte de cabecera indica
     * que hay más de una trama, continúa leyendo tramas adicionales desde la característica
     * de lectura extendida ([YpsoPumpUuids.CHAR_EXTENDED_READ]) hasta completar el mensaje.
     * Finalmente ensambla todas las tramas con [YpsoFraming.parseMultiFrameRead].
     *
     * Si alguna trama falla, se devuelve `null` (nunca datos parciales, ya que corromperían
     * el descifrado posterior).
     *
     * @param firstUuid UUID de la característica primaria de la que se inicia la lectura.
     * @param callback Llamado con el payload ensamblado (sin cabeceras de trama), o `null` si falló.
     */
    fun readExtended(firstUuid: UUID, callback: (ByteArray?) -> Unit) {
        val firstChar = findCharacteristic(firstUuid)
        val extChar = charExtendedRead
        if (firstChar == null) {
            callback(null)
            return
        }

        readCharacteristic(firstChar).with { _, data ->
            val firstFrame = data.value
            if (firstFrame == null || firstFrame.isEmpty()) {
                log("readExtended($firstUuid): empty first frame")
                callback(null)
                return@with
            }

            log("readExtended($firstUuid): read ${firstFrame.size}B = ${firstFrame.toHexString()}")

            // Firmware V05.02.03 answers reads with a bare CRC-terminated message and no
            // framing header — the whole reply arrives in one read. Upstream assumed reads are
            // framed like writes are, took the low nibble of the first *data* byte as a frame
            // count, and then read continuation frames that do not exist. A status reply
            // starting 0x03 was read as "frame 1 of 3", and the follow-up read of the extended
            // characteristic failed with GATT_INVALID_OFFSET, killing every session right
            // after authentication.
            //
            // The CRC is the reliable discriminator: if it validates over the bytes we already
            // have, this is a complete message and there is nothing to continue.
            if (YpsoCrc.isValid(firstFrame)) {
                callback(firstFrame)
                return@with
            }

            val totalFrames = YpsoFraming.getTotalFrames(firstFrame[0])
            log("readExtended($firstUuid): no valid CRC yet, treating as frame 1/$totalFrames")
            if (totalFrames <= 1) {
                callback(if (firstFrame.size > 1) firstFrame.copyOfRange(1, firstFrame.size) else byteArrayOf())
                return@with
            }

            if (extChar == null) {
                callback(if (firstFrame.size > 1) firstFrame.copyOfRange(1, firstFrame.size) else byteArrayOf())
                return@with
            }

            val frames = mutableListOf(firstFrame)
            readRemainingFrames(extChar, totalFrames - 1, frames) { allFrames ->
                if (allFrames == null) {
                    callback(null)
                } else {
                    callback(YpsoFraming.parseMultiFrameRead(allFrames))
                }
            }
        }.fail { _, _ ->
            callback(null)
        }.enqueue()
    }

    private fun readRemainingFrames(
        char: BluetoothGattCharacteristic,
        remaining: Int,
        frames: MutableList<ByteArray>,
        callback: (List<ByteArray>?) -> Unit
    ) {
        if (remaining <= 0) {
            callback(frames)
            return
        }

        readCharacteristic(char).with { _, data ->
            val frame = data.value
            if (frame != null) {
                frames.add(frame)
                log("  continuation frame ${frames.size} = ${frame.toHexString()}")
            }
            readRemainingFrames(char, remaining - 1, frames, callback)
        }.fail { _, status ->
            log(
                "Frame read failed on ${char.uuid} (GATT status=$status), " +
                    "got ${frames.size} of ${frames.size + remaining} frames"
            )
            callback(null) // Never return partial data — a truncated payload parses as garbage.
        }.enqueue()
    }

    fun writeCharacteristic(uuid: UUID, data: ByteArray, callback: (Boolean) -> Unit) {
        val char = findCharacteristic(uuid)
        if (char == null) {
            callback(false)
            return
        }

        val writeType = if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        writeCharacteristic(char, data, writeType)
            .with { _, _ -> callback(true) }
            .fail { _, _ -> callback(false) }
            .enqueue()
    }

    /**
     * Escribe un payload en la bomba usando el protocolo multi-trama de Ypsomed.
     *
     * Divide [payload] en tramas mediante [YpsoFraming.chunkPayload] y las escribe
     * secuencialmente en la característica indicada por [uuid]. Cada trama se envía solo
     * cuando la anterior ha sido confirmada por la bomba.
     *
     * @param uuid UUID de la característica BLE de destino.
     * @param payload Datos a enviar (sin cabeceras de trama; el framing se aplica aquí).
     * @param callback Llamado con `true` si todas las tramas se escribieron correctamente.
     */
    fun writeMultiFrame(uuid: UUID, payload: ByteArray, callback: (Boolean) -> Unit) {
        val char = findCharacteristic(uuid)
        if (char == null) {
            callback(false)
            return
        }

        val frames = YpsoFraming.chunkPayload(payload)
        log("Writing ${payload.size} bytes in ${frames.size} frames to $uuid")
        writeFramesSequentially(char, frames, 0, callback)
    }

    private fun writeFramesSequentially(
        char: BluetoothGattCharacteristic,
        frames: List<ByteArray>,
        index: Int,
        callback: (Boolean) -> Unit
    ) {
        if (index >= frames.size) {
            callback(true)
            return
        }

        writeCharacteristic(char, frames[index], BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            .with { _, _ ->
                writeFramesSequentially(char, frames, index + 1, callback)
            }
            .fail { _, status ->
                log("Frame ${index + 1} write failed: $status")
                callback(false)
            }
            .enqueue()
    }

    // ==================== PUMP COMMANDS ====================

    /**
     * Envía un comando cifrado a la bomba a través de la característica indicada.
     *
     * El flujo es: añadir CRC con [YpsoCrc.appendCrc] → trocear en tramas con
     * [YpsoFraming.chunkPayload] → escribir secuencialmente en [uuid].
     *
     * @param uuid UUID de la característica BLE de destino.
     * @param payload Datos del comando (sin CRC; se aplica aquí).
     * @param callback Llamado con `true` si el envío fue exitoso.
     */
    fun sendCommand(uuid: UUID, payload: ByteArray, callback: (Boolean) -> Unit) {
        val char = findCharacteristic(uuid)
        if (char == null) {
            callback(false)
            return
        }

        val frames = YpsoFraming.chunkPayload(YpsoCrc.appendCrc(payload))
        writeFramesSequentially(char, frames, 0, callback)
    }

    /**
     * Lee una respuesta de la bomba y elimina el CRC.
     *
     * Realiza una lectura multi-trama con [readExtended] y, si el CRC al final es válido,
     * lo elimina antes de devolver el payload limpio.
     *
     * @param uuid UUID de la característica BLE a leer.
     * @param callback Llamado con el payload sin CRC, o `null` si la operación falló.
     */
    fun readCommand(uuid: UUID, callback: (ByteArray?) -> Unit) {
        readExtended(uuid) { data ->
            if (data == null) {
                callback(null)
                return@readExtended
            }
            if (YpsoCrc.isValid(data)) {
                callback(data.copyOfRange(0, data.size - 2))
            } else {
                callback(data)
            }
        }
    }

    /**
     * Sends a 13-byte bolus command to [YpsoPumpUuids.CHAR_BOLUS_START_STOP], unframed.
     *
     * Upstream sent this CRC-terminated *and* framed, which would offer 16 bytes where the pump
     * wants 13 — the same mistake §4b found in the GLB write, and phase 1 confirmed the firmware
     * uses fixed-length attributes throughout.
     *
     * **A `true` here means the bytes went out and nothing more.** The pump does not NAK a bad
     * write, so the amount actually delivered has to come from the pump's own bolus status or its
     * history, never from this callback.
     */
    fun sendBolusCommand(payload: ByteArray, callback: (Boolean) -> Unit) {
        val char = charBolusStartStop
        if (char == null) {
            log("sendBolusCommand: CHAR_BOLUS_START_STOP not found")
            callback(false)
            return
        }
        writeCommandPayload(char, payload, "bolus", callback)
    }

    /**
     * How a command payload is wrapped for a write-only characteristic.
     *
     * The pump uses different conventions on different attributes and does not advertise which:
     * simple registers take the payload bare (the clock, §4e; GLB safe variables, §4b), while the
     * command and status channels carry a CRC-16 (the status *read* is 18 bytes plus its CRC,
     * §4a). Upstream wrapped everything as `frame(payload + CRC)`.
     */
    private enum class CommandEnvelope(val label: String) {

        /** Payload plus CRC-16, no framing header. Matches the shape of the status reply. */
        CRC("payload+CRC"),

        /** Upstream's shape: a 1-byte framing header over the CRC-terminated payload. */
        FRAMED_CRC("frame(payload+CRC)"),

        /** Bare, as the clock and GLB writes take it. */
        BARE("payload"),

        /** Framing header, no CRC. */
        FRAMED("frame(payload)");

        fun wrap(payload: ByteArray): ByteArray = when (this) {
            CRC        -> YpsoCrc.appendCrc(payload)
            FRAMED_CRC -> YpsoFraming.chunkPayload(YpsoCrc.appendCrc(payload)).first()
            BARE       -> payload
            FRAMED     -> YpsoFraming.chunkPayload(payload).first()
        }
    }

    /** Envelope the pump has accepted for a given command characteristic, once one is known. */
    private val learnedEnvelopes = mutableMapOf<UUID, CommandEnvelope>()

    /**
     * Writes a command payload to a **write-only** characteristic, discovering the envelope.
     *
     * `CHAR_BOLUS_START_STOP` has properties `0x08` — write only — so unlike the clock there is
     * nothing to read the envelope from, and the pump does not advertise the attribute's length.
     * A 13-byte bare write was refused with `GATT_INVALID_ATTRIBUTE_LENGTH` (13), so the candidates
     * are tried in order until one stops being refused, and the winner is remembered per
     * characteristic so later commands go straight out.
     *
     * **Why trying candidates is safe here.** Every candidate is a *complete, correct* encoding of
     * the same command, differing only in its envelope — never a truncated or padded guess. A
     * wrong length is rejected by the ATT layer and never reaches the pump's application, so a
     * refused attempt cannot deliver anything. And because the attribute is fixed-length (that is
     * what status 13 tells us), at most one candidate can be accepted.
     *
     * **What this does not protect against** is a length that is right while the *field layout*
     * inside is wrong — the pump would then act on a command it misread. Nothing at this level can
     * see that, which is why the caller checks the total the pump echoes back and cancels on a
     * disagreement.
     */
    private fun writeCommandPayload(
        char: BluetoothGattCharacteristic,
        payload: ByteArray,
        what: String,
        callback: (Boolean) -> Unit
    ) {
        learnedEnvelopes[char.uuid]?.let { known ->
            val bytes = known.wrap(payload)
            log("$what: writing ${bytes.size}B to ${char.uuid} as ${known.label}: ${bytes.toHexString()}")
            writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                .with { _, _ -> callback(true) }
                .fail { _, status ->
                    // The envelope worked before, so this is a real failure rather than a wrong
                    // guess. Forget it so the next attempt rediscovers instead of repeating.
                    log("$what: write failed (GATT status=$status) using the previously accepted ${known.label}; forgetting it")
                    learnedEnvelopes.remove(char.uuid)
                    callback(false)
                }
                .enqueue()
            return
        }

        log("$what: ${char.uuid} is write-only (properties=0x%02X), discovering the envelope".format(char.properties))
        tryEnvelopes(char, payload, what, CommandEnvelope.entries.toList(), callback)
    }

    private fun tryEnvelopes(
        char: BluetoothGattCharacteristic,
        payload: ByteArray,
        what: String,
        remaining: List<CommandEnvelope>,
        callback: (Boolean) -> Unit
    ) {
        val envelope = remaining.firstOrNull()
        if (envelope == null) {
            log("$what: every envelope was refused — the ${payload.size}-byte payload layout is wrong, not just its wrapper")
            callback(false)
            return
        }

        val bytes = envelope.wrap(payload)
        log("$what: trying ${envelope.label} = ${bytes.size}B: ${bytes.toHexString()}")
        writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            .with { _, _ ->
                log("$what: ACCEPTED as ${envelope.label} (${bytes.size}B) — remembering it for ${char.uuid}")
                learnedEnvelopes[char.uuid] = envelope
                callback(true)
            }
            .fail { _, status ->
                if (status == GATT_INVALID_ATTRIBUTE_LENGTH) {
                    // Wrong length: refused by the ATT layer, nothing reached the pump. Next.
                    log("$what: ${envelope.label} refused as the wrong length (status=13)")
                    tryEnvelopes(char, payload, what, remaining.drop(1), callback)
                } else {
                    log("$what: ${envelope.label} failed with GATT status=$status — not a length problem, stopping")
                    callback(false)
                }
            }
            .enqueue()
    }

    /**
     * Lee el estado del sistema de la bomba desde [YpsoPumpUuids.CHAR_SYSTEM_STATUS].
     *
     * El payload contiene: modo de entrega (1 byte), insulina restante en centi-unidades
     * (4 bytes LE), nivel de batería en porcentaje (1 byte).
     *
     * @param callback Llamado con un [SystemStatusData] si la lectura fue correcta,
     *                 o `null` en caso de error.
     */
    fun getSystemStatus(callback: (SystemStatusData?) -> Unit) {
        readExtended(YpsoPumpUuids.CHAR_SYSTEM_STATUS) { data ->
            if (data == null) {
                callback(null)
                return@readExtended
            }

            if (!YpsoCrc.isValid(data)) {
                callback(null)
                return@readExtended
            }

            val status = SystemStatusData.parse(data.copyOfRange(0, data.size - 2))
            if (status == null) {
                log("getSystemStatus: payload too short (${data.size - 2}B)")
                callback(null)
                return@readExtended
            }

            log(
                "Status: ${status.deliveryModeName}, " +
                    "Insulin: ${if (status.reservoirKnown) "${status.insulinRemaining}U" else "unknown (no cartridge)"}, " +
                    "Battery: ${status.batteryBars}/${SystemStatusData.BATTERY_BARS_MAX} bars " +
                    "(${status.batteryPercent}%), Rate: ${status.currentBasalRate}U/h" +
                    if (status.isTbrRunning) {
                        ", TBR ${status.tbrPercent}% ${status.tbrRemainingMinutes}min left"
                    } else ""
            )
            emitData(DataEvent.StatusNotification(data))
            callback(status)
        }
    }

    /**
     * Construye y envía un comando de inicio de bolo a la bomba.
     *
     * El payload de 13 bytes tiene el formato (little-endian):
     * `totalScaled(4B) + durationMinutes(4B) + immediateScaled(4B) + bolusType(1B)`.
     * - `totalScaled` = round(totalUnits * 100), acotado entre 1 y 2500.
     * - `immediateScaled` = round(immediateUnits * 100), acotado entre 0 y totalScaled.
     * - `bolusType` = 1 para bolo estándar (durationMinutes == 0), 2 para extendido/multionda.
     *
     * @param totalUnits Cantidad total de insulina en unidades.
     * @param durationMinutes Duración en minutos para bolo extendido o multionda; 0 para bolo estándar.
     * @param immediateUnits Fracción inmediata para bolo multionda; 0 para bolo estándar o extendido.
     * @param callback Llamado con `true` si el comando fue aceptado.
     */
    fun startBolus(totalUnits: Float, durationMinutes: Int = 0, immediateUnits: Float = 0f, callback: (Boolean) -> Unit) {
        val totalScaled = Math.round(totalUnits * 100).coerceIn(1, 2500)
        val immediateScaled = Math.round(immediateUnits * 100).coerceIn(0, totalScaled)
        val bolusType: Byte = if (durationMinutes == 0) 1 else 2

        val payload = buildBolusPayload(totalScaled, durationMinutes, immediateScaled, bolusType)

        log(
            "Starting bolus: ${"%.2f".format(totalUnits)}U (scaled=$totalScaled), " +
                "duration=${durationMinutes}min, type=$bolusType, layout=$BOLUS_PAYLOAD_LAYOUT"
        )
        sendBolusCommand(payload, callback)
    }

    /**
     * Builds the 13-byte bolus payload.
     *
     * **The length of 13 is confirmed** — the pump accepted a 15-byte write (13 + CRC) where a
     * 13-byte bare write had been refused with `GATT_INVALID_ATTRIBUTE_LENGTH`. What was *not*
     * right is the arrangement inside it: with upstream's layout the pump accepted the write and
     * then did nothing at all — `CHAR_BOLUS_STATUS` kept reporting `status=0 injected=0 total=0`
     * and the reservoir did not move. A command it cannot validate is one it discards silently.
     *
     * [BolusPayloadLayout.GLB_PAIRS] is the current hypothesis, and it is not a guess about the
     * length but about where this firmware puts its integrity checks. Everything safety-critical
     * this driver has seen written carries its own bitwise complement beside it: settings indices
     * and values (§4b), history indices, and the TBR command, which sends
     * `percent, ~percent, minutes, ~minutes`. 13 bytes happens to be exactly
     * `total(4) + ~total(4) + duration(2) + ~duration(2) + type(1)`, so upstream's layout was
     * sending zeros where the pump wanted `~total`.
     *
     * Kept switchable because this is the one thing here that hardware still has to settle, and
     * changing it should be a one-line edit rather than a rewrite. **Do not add automatic
     * layout-trying**: unlike the envelope, a wrong layout that the pump *accepts* can deliver a
     * dose, so each candidate needs its own saline test with the echoed-total check armed.
     */
    private fun buildBolusPayload(
        totalScaled: Int,
        durationMinutes: Int,
        immediateScaled: Int,
        bolusType: Byte
    ): ByteArray = ByteBuffer.allocate(BOLUS_PAYLOAD_BYTES).order(ByteOrder.LITTLE_ENDIAN).run {
        when (BOLUS_PAYLOAD_LAYOUT) {
            BolusPayloadLayout.GLB_PAIRS  -> {
                putInt(totalScaled)
                putInt(totalScaled.inv())
                putShort(durationMinutes.toShort())
                putShort(durationMinutes.inv().toShort())
                put(bolusType)
            }

            BolusPayloadLayout.UPSTREAM   -> {
                putInt(totalScaled)
                putInt(durationMinutes)
                putInt(immediateScaled)
                put(bolusType)
            }
        }
        array()
    }

    /**
     * Envía un comando de cancelación de bolo a la bomba.
     *
     * Construye un payload de 13 bytes con todos los campos a cero excepto el tipo de bolo,
     * que indica qué bolo cancelar: "fast" (tipo 1), "extended" o "combined" (tipo 2).
     *
     * @param kind Tipo de bolo a cancelar: "fast", "extended" o "combined".
     * @param callback Llamado con `true` si el comando fue aceptado.
     */
    fun cancelBolus(kind: String = "fast", callback: (Boolean) -> Unit) {
        val typeMap = mapOf("fast" to 1, "extended" to 2, "combined" to 2)
        val bolusType = typeMap[kind] ?: 1
        val payload = ByteArray(13).also { it[12] = bolusType.toByte() }
        sendBolusCommand(payload, callback)
    }

    // ==================== SYSTEM CLOCK ====================

    /**
     * A short fixed-length message as the pump itself laid it out.
     *
     * [hasCrc] is the point of the whole type: it is observed on the pump's own reply rather than
     * assumed, so a write to the same characteristic can be wrapped exactly the way the pump
     * wraps its answers there.
     */
    private class ShortMessage(val payload: ByteArray, val hasCrc: Boolean, val raw: ByteArray)

    /**
     * Reads a short fixed-length characteristic, reporting its payload and whether this firmware
     * terminates it with a CRC.
     *
     * V05.02.03 uses fixed-length attributes for these short messages: a write of any other
     * length is refused at the ATT layer with `GATT_INVALID_ATTRIBUTE_LENGTH` (13), which is
     * exactly what the framing header did to every GLB write (see [writeGlb]). Reading first
     * costs one round trip and replaces a guess about the envelope with an observation of it.
     *
     * Returns null — logging the raw bytes — when the reply is neither [payloadBytes] bare nor
     * [payloadBytes] followed by a valid CRC. There is no third shape this code knows how to
     * write, and guessing at one is how you set a pump's clock to garbage.
     */
    private fun readShortMessage(
        char: BluetoothGattCharacteristic,
        payloadBytes: Int,
        callback: (ShortMessage?) -> Unit
    ) {
        readCharacteristic(char).with { _, data ->
            val raw = data.value
            if (raw == null) {
                log("readShortMessage(${char.uuid}): empty read")
                callback(null)
                return@with
            }
            log("readShortMessage(${char.uuid}): ${raw.size}B = ${raw.toHexString()}")
            when {
                raw.size == payloadBytes                              ->
                    callback(ShortMessage(raw, hasCrc = false, raw = raw))

                raw.size == payloadBytes + 2 && YpsoCrc.isValid(raw)  ->
                    callback(ShortMessage(raw.copyOfRange(0, payloadBytes), hasCrc = true, raw = raw))

                else                                                  -> {
                    log(
                        "readShortMessage(${char.uuid}): unrecognised shape — expected ${payloadBytes}B bare " +
                            "or ${payloadBytes + 2}B ending in a valid CRC, got ${raw.size}B ${raw.toHexString()}"
                    )
                    callback(null)
                }
            }
        }.fail { _, status ->
            log("readShortMessage(${char.uuid}) failed (GATT status=$status)")
            callback(null)
        }.enqueue()
    }

    /**
     * Reads the pump's own date and time.
     *
     * Read-only and harmless, and it does three jobs at once: it surfaces the pump's clock for
     * comparison against the phone, it proves the field layout this code assumes for those two
     * characteristics, and it is the read-back that confirms [syncTime] actually took effect.
     *
     * Returns null if either characteristic will not read, or if the decoded fields could not be
     * a real date — see [PumpClockData.isPlausible].
     */
    fun readClock(callback: (PumpClockData?) -> Unit) {
        val dateChar = findCharacteristic(YpsoPumpUuids.CHAR_SYSTEM_DATE)
        val timeChar = findCharacteristic(YpsoPumpUuids.CHAR_SYSTEM_TIME)
        if (dateChar == null || timeChar == null) {
            log("readClock: system date/time characteristic not found")
            callback(null)
            return
        }

        readShortMessage(dateChar, DATE_PAYLOAD_BYTES) { dateMsg ->
            if (dateMsg == null) {
                callback(null)
                return@readShortMessage
            }
            readShortMessage(timeChar, TIME_PAYLOAD_BYTES) { timeMsg ->
                if (timeMsg == null) {
                    callback(null)
                    return@readShortMessage
                }

                val date = ByteBuffer.wrap(dateMsg.payload).order(ByteOrder.LITTLE_ENDIAN)
                val clock = PumpClockData(
                    year = date.short.toInt() and 0xFFFF,
                    month = date.get().toInt() and 0xFF,
                    day = date.get().toInt() and 0xFF,
                    hour = timeMsg.payload[0].toInt() and 0xFF,
                    minute = timeMsg.payload[1].toInt() and 0xFF,
                    second = timeMsg.payload[2].toInt() and 0xFF,
                    dateWire = dateMsg.raw.toHexString(),
                    timeWire = timeMsg.raw.toHexString()
                )

                if (!clock.isPlausible) {
                    log(
                        "readClock: implausible reading ${clock.formatted()} from date=${clock.dateWire} " +
                            "time=${clock.timeWire} — these characteristics do not carry the layout this code " +
                            "assumes, so the clock will not be written"
                    )
                    callback(null)
                    return@readShortMessage
                }

                log(
                    "Pump clock: ${clock.formatted()} (date wire ${clock.dateWire}" +
                        "${if (dateMsg.hasCrc) " +CRC" else " bare"}, time wire ${clock.timeWire}" +
                        "${if (timeMsg.hasCrc) " +CRC" else " bare"})"
                )
                callback(clock)
            }
        }
    }

    /**
     * Sets the pump's clock from the phone's, with no framing header.
     *
     * Upstream sent both payloads CRC-terminated *and* framed through
     * [writeFramesSequentially]; §4b of PORTING_NOTES showed this firmware refuses framed short
     * writes outright. Rather than swap one assumption for another, this reads each
     * characteristic first and matches whatever envelope came back — see [readShortMessage].
     * If either read does not produce a shape this code can write, nothing is written at all.
     *
     * **`true` here means only that the bytes went out.** This pump does not NAK a bad write
     * (PORTING_NOTES §3), so the caller must confirm the clock by reading it back; only
     * `YpsoPumpManagerImpl.syncDateTime` may call a clock change successful.
     */
    fun syncTime(callback: (Boolean) -> Unit) {
        val dateChar = findCharacteristic(YpsoPumpUuids.CHAR_SYSTEM_DATE)
        val timeChar = findCharacteristic(YpsoPumpUuids.CHAR_SYSTEM_TIME)
        if (dateChar == null || timeChar == null) {
            log("syncTime: system date/time characteristic not found")
            callback(false)
            return
        }

        readShortMessage(dateChar, DATE_PAYLOAD_BYTES) { dateShape ->
            if (dateShape == null) {
                log("syncTime aborted: the date characteristic did not read back in a shape this code can write")
                callback(false)
                return@readShortMessage
            }
            readShortMessage(timeChar, TIME_PAYLOAD_BYTES) { timeShape ->
                if (timeShape == null) {
                    log("syncTime aborted: the time characteristic did not read back in a shape this code can write")
                    callback(false)
                    return@readShortMessage
                }

                // One snapshot for both payloads, so a write that straddles midnight cannot pair
                // tomorrow's date with today's time.
                val now = Calendar.getInstance()
                val datePayload = ByteBuffer.allocate(DATE_PAYLOAD_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putShort(now.get(Calendar.YEAR).toShort())
                    .put((now.get(Calendar.MONTH) + 1).toByte())
                    .put(now.get(Calendar.DAY_OF_MONTH).toByte())
                    .array()
                val timePayload = byteArrayOf(
                    now.get(Calendar.HOUR_OF_DAY).toByte(),
                    now.get(Calendar.MINUTE).toByte(),
                    now.get(Calendar.SECOND).toByte()
                )

                writeShortMessage(dateChar, datePayload, dateShape.hasCrc) { dateWritten ->
                    if (!dateWritten) {
                        callback(false)
                        return@writeShortMessage
                    }
                    writeShortMessage(timeChar, timePayload, timeShape.hasCrc, callback)
                }
            }
        }
    }

    /** Writes a short message in the envelope [readShortMessage] observed, with no framing header. */
    private fun writeShortMessage(
        char: BluetoothGattCharacteristic,
        payload: ByteArray,
        withCrc: Boolean,
        callback: (Boolean) -> Unit
    ) {
        val bytes = if (withCrc) YpsoCrc.appendCrc(payload) else payload
        log("Writing ${bytes.size}B to ${char.uuid}: ${bytes.toHexString()}")
        writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            .with { _, _ -> callback(true) }
            .fail { _, status ->
                log("Short write to ${char.uuid} failed (GATT status=$status)")
                callback(false)
            }
            .enqueue()
    }

    /**
     * Lee el estado detallado del bolo desde [YpsoPumpUuids.CHAR_BOLUS_STATUS].
     *
     * El payload de 42 bytes sigue el formato `decode_bolus_status()` del SDK Python:
     * - Bloque rápido (fast, 13 bytes): status(1B) + sequence(4B) + injected(4B, /100) + total(4B, /100)
     * - Bloque lento (slow, 29 bytes): status(1B) + sequence(4B) + injected(4B) + total(4B)
     *   + fast_part_injected(4B) + fast_part_total(4B) + actual_duration(4B) + total_duration(4B)
     *
     * Todas las cantidades de insulina están en centi-unidades (se dividen por 100 para obtener U).
     *
     * @param callback Llamado con un [BolusStatusData] completo, o `null` si la operación falló
     *                 o el payload tiene menos de 13 bytes.
     */
    fun getBolusStatus(callback: (BolusStatusData?) -> Unit) {
        readExtended(YpsoPumpUuids.CHAR_BOLUS_STATUS) { data ->
            if (data == null) {
                callback(null)
                return@readExtended
            }
            if (!YpsoCrc.isValid(data)) {
                callback(null)
                return@readExtended
            }

            val payload = data.copyOfRange(0, data.size - 2)
            if (payload.size < 13) {
                callback(null)
                return@readExtended
            }

            // Layout matches Python decode_bolus_status():
            // Fast: status(1B) + sequence(4B) + injected(4B,/100) + total(4B,/100) = 13B
            // Slow: status(1B) + sequence(4B) + injected(4B) + total(4B) + fast_part_injected(4B)
            //       + fast_part_total(4B) + actual_duration(4B) + total_duration(4B) = 29B
            // Total: 42 bytes
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val fastStatus = buf.get().toInt() and 0xFF
            val fastSequence = buf.int.toLong() and 0xFFFFFFFFL
            val fastInjected = buf.int / 100f
            val fastTotal = buf.int / 100f

            var slowStatus = 0; var slowSequence = 0L
            var slowInjected = 0f; var slowTotal = 0f
            var slowFastPartInjected = 0f; var slowFastPartTotal = 0f
            var actualDuration = 0; var totalDuration = 0

            if (payload.size >= 14) {
                slowStatus = buf.get().toInt() and 0xFF
                if (slowStatus != 0 && payload.size >= 42) {
                    slowSequence = buf.int.toLong() and 0xFFFFFFFFL
                    slowInjected = buf.int / 100f
                    slowTotal = buf.int / 100f
                    slowFastPartInjected = buf.int / 100f
                    slowFastPartTotal = buf.int / 100f
                    actualDuration = buf.int
                    totalDuration = buf.int
                }
            }

            callback(BolusStatusData(
                fastStatus = fastStatus,
                fastSequence = fastSequence,
                fastInjected = fastInjected,
                fastTotal = fastTotal,
                slowStatus = slowStatus,
                slowSequence = slowSequence,
                slowInjected = slowInjected,
                slowTotal = slowTotal,
                slowFastPartInjected = slowFastPartInjected,
                slowFastPartTotal = slowFastPartTotal,
                actualDuration = actualDuration,
                totalDuration = totalDuration
            ))
        }
    }

    // ==================== TBR ====================

    /**
     * Inicia una tasa basal temporal (TBR) en la bomba.
     *
     * Construye un payload GLB de 16 bytes en little-endian con el formato:
     * `percent(4B) + ~percent(4B) + durationMinutes(4B) + ~durationMinutes(4B)`,
     * donde `~` es el complemento bit a bit del valor (verificación de integridad del protocolo).
     * El porcentaje es un valor absoluto (p. ej. 25 para el 25%, NO 2500).
     *
     * @param percent Porcentaje de la tasa basal temporal (p. ej. 50 para 50%, 150 para 150%).
     * @param durationMinutes Duración de la TBR en minutos.
     * @param callback Llamado con `true` si el comando fue aceptado.
     */
    fun startTbr(percent: Int, durationMinutes: Int, callback: (Boolean) -> Unit) {
        val char = charTbrStartStop
        if (char == null) {
            log("startTbr: CHAR_TBR_START_STOP not found")
            callback(false)
            return
        }

        val payload = tbrPayload(percent, durationMinutes)
        log("Starting TBR: $percent% for ${durationMinutes}min")
        writeCommandPayload(char, payload, "TBR", callback)
    }

    /**
     * The 16-byte TBR payload: two GLB pairs, each value beside its own bitwise complement.
     *
     * Note that upstream sends this **without** a CRC while it sends the bolus payload **with** one
     * — so the two command characteristics were already known to differ, and the envelope here is
     * discovered rather than copied from the bolus (§4f). The percentage is an absolute figure:
     * 25 means 25%, not 2500.
     */
    private fun tbrPayload(percent: Int, durationMinutes: Int): ByteArray =
        ByteBuffer.allocate(TBR_PAYLOAD_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(percent)
            .putInt(percent.inv())
            .putInt(durationMinutes)
            .putInt(durationMinutes.inv())
            .array()

    /**
     * Cancela la tasa basal temporal (TBR) activa y restablece la tasa basal normal.
     *
     * Envía un payload GLB con percent=100 y duration=0, lo que equivale a volver al 100%
     * de la tasa basal programada (es decir, cancelar la TBR).
     *
     * @param callback Llamado con `true` si el comando fue aceptado.
     */
    fun cancelTbr(callback: (Boolean) -> Unit) {
        val char = charTbrStartStop
        if (char == null) {
            log("cancelTbr: CHAR_TBR_START_STOP not found")
            callback(false)
            return
        }

        // 100% for 0 minutes is this pump's "no temporary basal" — the same value byte 10 of the
        // system status reads when none is running, which is what makes the cancel confirmable.
        log("Cancelling TBR (100% for 0min)")
        writeCommandPayload(char, tbrPayload(TBR_PERCENT_NONE, 0), "TBR cancel", callback)
    }

    // ==================== SETTINGS ====================

    /**
     * Lee el valor de un ajuste de la bomba por su índice.
     *
     * El protocolo requiere dos pasos: primero escribe el índice (codificado en formato GLB)
     * en [YpsoPumpUuids.CHAR_SETTING_ID] y luego lee el valor resultante de
     * [YpsoPumpUuids.CHAR_SETTING_VALUE]. El valor devuelto es un entero GLB.
     * El valor -1 (0xFFFFFFFF) indica una ranura sin programar.
     *
     * @param index Índice del ajuste a leer (ver [SettingsIndex]).
     * @param callback Llamado con el valor entero del ajuste, o `null` si la operación falló.
     */
    fun readSetting(index: Int, callback: (Int?) -> Unit) {
        val idChar = charSettingId
        val valChar = charSettingValue
        if (idChar == null || valChar == null) {
            callback(null)
            return
        }

        writeGlb(idChar, index) { success ->
            if (!success) {
                callback(null)
                return@writeGlb
            }
            readGlb(valChar) { value ->
                log("readSetting($index): value=$value")
                callback(value)
            }
        }
    }

    /**
     * Reads a bare 8-byte GLB safe variable from [char].
     *
     * Settings values come back as the GLB pair on its own — no CRC and no framing header.
     * Routing them through [readExtended] destroyed them: with no CRC to validate, that method
     * falls back to treating the low nibble of the first *data* byte as a frame count, so a
     * value of 120 (`0x78`) was read as "frame 1 of 8" and the follow-up reads killed it. Read
     * these directly; the GLB's own complement check is the integrity guard here.
     */
    private fun readGlb(char: BluetoothGattCharacteristic, callback: (Int?) -> Unit) {
        readCharacteristic(char).with { _, data ->
            val raw = data.value
            if (raw == null || raw.size < 8) {
                log("readGlb(${char.uuid}): short read (${raw?.size ?: 0}B)")
                callback(null)
                return@with
            }
            val value = YpsoGlb.findInPayload(raw)
            if (value == null) log("readGlb(${char.uuid}): no valid GLB in ${raw.toHexString()}")
            callback(value)
        }.fail { _, status ->
            log("readGlb(${char.uuid}) failed (GATT status=$status)")
            callback(null)
        }.enqueue()
    }

    /**
     * Escribe un valor en un ajuste de la bomba por su índice.
     *
     * Escribe primero el índice en [YpsoPumpUuids.CHAR_SETTING_ID] y luego el valor en
     * [YpsoPumpUuids.CHAR_SETTING_VALUE]. Ambos se codifican en formato GLB
     * y se envían en tramas.
     *
     * @param index Índice del ajuste a escribir (ver [SettingsIndex]).
     * @param value Valor entero a escribir.
     * @param callback Llamado con `true` si ambas escrituras tuvieron éxito.
     */
    fun writeSetting(index: Int, value: Int, callback: (Boolean) -> Unit) {
        val idChar = charSettingId
        val valChar = charSettingValue
        if (idChar == null || valChar == null) {
            callback(false)
            return
        }

        writeGlb(idChar, index) { idSuccess ->
            if (!idSuccess) {
                callback(false)
                return@writeGlb
            }
            writeGlb(valChar, value, callback)
        }
    }

    /**
     * Writes a GLB safe variable to [char] as the bare 8 bytes, with no framing header.
     *
     * The pump rejects a framed 9-byte write of an 8-byte GLB with
     * GATT_INVALID_ATTRIBUTE_LENGTH (13) — which is what broke every basal profile read. That
     * matches what the read path showed: firmware V05.02.03 does not use the 1-byte framing
     * header for these short messages, in either direction.
     */
    private fun writeGlb(char: BluetoothGattCharacteristic, value: Int, callback: (Boolean) -> Unit) {
        writeCharacteristic(char, YpsoGlb.encode(value), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            .with { _, _ -> callback(true) }
            .fail { _, status ->
                log("GLB write to ${char.uuid} failed (GATT status=$status)")
                callback(false)
            }
            .enqueue()
    }

    // ==================== BASAL PROFILES ====================

    /**
     * Lee el perfil basal completo de un programa (A o B) de la bomba.
     *
     * Lee 24 ajustes consecutivos (uno por hora del día) a partir del índice de inicio
     * correspondiente al programa ([SettingsIndex.PROGRAM_A_START] o [SettingsIndex.PROGRAM_B_START]).
     * Cada valor está en centi-unidades/hora (se divide por 100 para obtener U/h).
     * Las ranuras sin programar (valor -1) se devuelven como 0.0f.
     *
     * @param program Identificador del programa: 'A' o 'B'.
     * @param callback Llamado con una lista de 24 tasas en U/h, o `null` si alguna lectura falló.
     */
    fun readBasalProfile(program: Char, callback: (List<Double>?) -> Unit) {
        val startIndex = when (program) {
            'A' -> SettingsIndex.PROGRAM_A_START
            'B' -> SettingsIndex.PROGRAM_B_START
            else -> {
                callback(null)
                return
            }
        }

        val rates = mutableListOf<Double>()
        readBasalRateSequential(startIndex, startIndex + 23, rates, callback)
    }

    private fun readBasalRateSequential(
        currentIndex: Int,
        endIndex: Int,
        rates: MutableList<Double>,
        callback: (List<Double>?) -> Unit
    ) {
        if (currentIndex > endIndex) {
            log("Basal profile read complete: ${rates.size} rates, values=${rates.map { "%.2f".format(it) }}")
            callback(rates)
            return
        }

        readSetting(currentIndex) { value ->
            if (value != null) {
                if (value == -1) {  // 0xFFFFFFFF = unprogrammed slot
                    rates.add(0.0)
                } else {
                    rates.add(value / 100.0)
                }
                readBasalRateSequential(currentIndex + 1, endIndex, rates, callback)
            } else {
                log("Basal profile read failed at index $currentIndex (slot ${currentIndex - rates.size})")
                callback(null)
            }
        }
    }

    fun readActiveProgram(callback: (Char?) -> Unit) {
        readSetting(SettingsIndex.ACTIVE_PROGRAM) { value ->
            when (value) {
                SettingsIndex.PROGRAM_A_VALUE -> callback('A')
                SettingsIndex.PROGRAM_B_VALUE -> callback('B')
                else -> callback(null)
            }
        }
    }

    // ==================== HISTORY ====================

    /** History counts are GLB safe variables too — same bare 8 bytes as a setting value. */
    fun readHistoryCount(countUuid: UUID, callback: (Int?) -> Unit) {
        val char = findCharacteristic(countUuid)
        if (char == null) {
            callback(null)
            return
        }
        readGlb(char) { count ->
            log("readHistoryCount($countUuid): $count")
            callback(count)
        }
    }

    private fun readHistoryEntry(
        indexChar: BluetoothGattCharacteristic,
        valueUuid: UUID,
        entryIndex: Int,
        callback: (HistoryEntry?) -> Unit
    ) {
        writeGlb(indexChar, entryIndex) { success ->
            if (!success) {
                callback(null)
                return@writeGlb
            }

            readExtended(valueUuid) { data ->
                if (data == null) {
                    callback(null)
                    return@readExtended
                }

                val payload = if (YpsoCrc.isValid(data)) {
                    data.copyOfRange(0, data.size - 2)
                } else {
                    data
                }

                if (payload.size >= 17) {
                    try {
                        callback(HistoryEntry.parse(payload))
                    } catch (e: Exception) {
                        callback(null)
                    }
                } else {
                    callback(null)
                }
            }
        }
    }

    /**
     * Lee un conjunto de entradas de historial de la bomba de forma genérica.
     *
     * Lee primero el contador total de entradas desde [countUuid], luego itera secuencialmente
     * desde el índice más antiguo relevante (según [maxEntries]) hasta el más reciente,
     * escribiendo cada índice en [indexChar] y leyendo el valor desde [valueUuid].
     * Las entradas se devuelven en orden cronológico inverso (más reciente primero).
     *
     * @param countUuid UUID de la característica que contiene el número total de entradas.
     * @param indexChar Característica en la que se escribe el índice de la entrada deseada.
     * @param valueUuid UUID de la característica desde la que se lee el valor de cada entrada.
     * @param maxEntries Número máximo de entradas a leer (0 para leer todas).
     * @param callback Llamado con la lista de [HistoryEntry] leídos (puede estar vacía).
     */
    fun readHistory(
        countUuid: UUID,
        indexChar: BluetoothGattCharacteristic?,
        valueUuid: UUID,
        maxEntries: Int = 50,
        callback: (List<HistoryEntry>) -> Unit
    ) {
        if (indexChar == null) {
            callback(emptyList())
            return
        }

        readHistoryCount(countUuid) { count ->
            if (count == null || count == 0) {
                callback(emptyList())
                return@readHistoryCount
            }

            // Index 0 is the MOST RECENT entry, not the oldest. Verified on hardware: across a
            // 50-entry read the pump's own sequence number rose strictly as the index fell, and
            // so did the timestamps. Upstream read `count - maxEntries` .. `count - 1`, assuming
            // the opposite, and so returned the *oldest* slots in the log — on the test pump
            // that meant entries from four months earlier, with the day's activity missing
            // entirely. Read from 0 upwards to get the newest.
            val lastIdx = (if (maxEntries > 0) minOf(maxEntries, count) else count) - 1
            val entries = mutableListOf<HistoryEntry>()
            readHistoryEntriesSequential(indexChar, valueUuid, 0, lastIdx, entries, callback)
        }
    }

    private fun readHistoryEntriesSequential(
        indexChar: BluetoothGattCharacteristic,
        valueUuid: UUID,
        currentIdx: Int,
        lastIdx: Int,
        entries: MutableList<HistoryEntry>,
        callback: (List<HistoryEntry>) -> Unit
    ) {
        if (currentIdx > lastIdx) {
            callback(entries.reversed())
            return
        }

        readHistoryEntry(indexChar, valueUuid, currentIdx) { entry ->
            if (entry != null) entries.add(entry)
            readHistoryEntriesSequential(indexChar, valueUuid, currentIdx + 1, lastIdx, entries, callback)
        }
    }

    fun readHistoryEventsCount(callback: (Int?) -> Unit) {
        readHistoryCount(YpsoPumpUuids.Events.COUNT, callback)
    }

    fun readHistoryAlertsCount(callback: (Int?) -> Unit) {
        readHistoryCount(YpsoPumpUuids.Alerts.COUNT, callback)
    }

    /**
     * Lee las entradas más recientes del historial de eventos de la bomba.
     *
     * Usa las características del grupo [YpsoPumpUuids.Events] (COUNT, INDEX, VALUE).
     *
     * @param maxEntries Número máximo de eventos a leer.
     * @param callback Llamado con la lista de [HistoryEntry] de eventos.
     */
    fun readHistoryEvents(maxEntries: Int = 50, callback: (List<HistoryEntry>) -> Unit) {
        readHistory(YpsoPumpUuids.Events.COUNT, charHistoryEventsIndex, YpsoPumpUuids.Events.VALUE, maxEntries, callback)
    }

    /**
     * Lee las entradas más recientes del historial de alertas de la bomba.
     *
     * Usa las características del grupo [YpsoPumpUuids.Alerts] (COUNT, INDEX, VALUE).
     *
     * @param maxEntries Número máximo de alertas a leer.
     * @param callback Llamado con la lista de [HistoryEntry] de alertas.
     */
    fun readHistoryAlerts(maxEntries: Int = 50, callback: (List<HistoryEntry>) -> Unit) {
        readHistory(YpsoPumpUuids.Alerts.COUNT, charHistoryAlertsIndex, YpsoPumpUuids.Alerts.VALUE, maxEntries, callback)
    }

    fun readHistorySystem(maxEntries: Int = 50, callback: (List<HistoryEntry>) -> Unit) {
        readHistory(YpsoPumpUuids.System.COUNT, charHistorySystemIndex, YpsoPumpUuids.System.VALUE, maxEntries, callback)
    }

    // ==================== SECURITY STATUS ====================

    fun readSecurityStatus(callback: (ByteArray?) -> Unit) {
        readExtended(YpsoPumpUuids.CHAR_SEC_STATUS, callback)
    }

    // ==================== HELPERS ====================

    private fun findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? {
        discoveredServices.forEach { service ->
            service.getCharacteristic(uuid)?.let { return it }
        }
        return null
    }

    private fun formatVersion(bytes: ByteArray): String {
        return bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        _logEvents.tryEmit(LogEvent(System.currentTimeMillis(), message))
    }

    private fun emitData(event: DataEvent) {
        _dataReceived.tryEmit(event)
    }

    private fun ByteArray.toHexString(): String =
        joinToString(" ") { "%02X".format(it) }

    override fun close() {
        _isConnected.value = false
        _isAuthenticated.value = false
        super.close()
    }
}

/**
 * Evento de log interno emitido por [YpsoBleManager] para cada mensaje de depuración.
 *
 * Se acumula en el flujo [YpsoBleManager.logEvents] con repetición de los últimos 100 mensajes,
 * lo que permite a los observadores recibir el historial reciente al suscribirse.
 *
 * @property timestamp Marca de tiempo en milisegundos (System.currentTimeMillis()).
 * @property message Texto del mensaje de log.
 */
internal data class LogEvent(val timestamp: Long, val message: String)

/**
 * Evento de datos internos emitido por [YpsoBleManager] cuando se recibe información
 * relevante de la bomba (notificaciones BLE, respuestas de autenticación, estado del sistema).
 *
 * Se propaga a través del flujo [YpsoBleManager.dataReceived].
 */
internal sealed class DataEvent {
    /** Respuesta recibida durante el proceso de autenticación. */
    data class AuthResponse(val data: ByteArray) : DataEvent()

    /** Notificación de estado del sistema recibida desde la característica de estado. */
    data class StatusNotification(val data: ByteArray) : DataEvent()

    /**
     * Notificación BLE genérica recibida desde cualquier característica con NOTIFY/INDICATE.
     *
     * @property characteristic Nombre descriptivo de la característica (p. ej. "BolusNotification").
     * @property data Bytes crudos recibidos en la notificación.
     */
    data class ProBluetoothNotification(val characteristic: String, val data: ByteArray) : DataEvent()
}
