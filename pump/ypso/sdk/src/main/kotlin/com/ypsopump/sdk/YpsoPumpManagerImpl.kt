package com.ypsopump.sdk

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.pump.common.ActiveTbr
import com.pump.common.BasalBlock
import com.pump.common.BasalProfile
import com.pump.common.BolusDeliveryResult
import com.pump.common.BolusType
import com.pump.common.PumpConnectionState
import com.pump.common.PumpEvent
import com.pump.common.PumpHistoryEvent
import com.pump.common.PumpManager
import com.pump.common.PumpStatus
import com.ypsopump.sdk.internal.ble.DataEvent
import com.ypsopump.sdk.internal.ble.YpsoBleManager
import com.ypsopump.sdk.internal.data.HistoryEntry
import com.ypsopump.sdk.internal.data.SystemStatusData
import com.ypsopump.sdk.internal.parsing.PumpEventProcessor
import com.ypsopump.sdk.internal.protocol.DeliveryMode
import com.ypsopump.sdk.internal.protocol.EventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * Plaintext [PumpManager] implementation for the YpsoPump, derived from vicktor's SDK.
 *
 * **Differences from upstream, both deliberate — see PORTING_NOTES.md:**
 *
 * 1. **No encryption, no key exchange.** Upstream layers XChaCha20-Poly1305 over the BLE
 *    payloads and renews the shared key through the Proregia relay. Neither the `crypto/`
 *    nor the `keyexchange/` package was vendored, so there is no code path that can encrypt,
 *    derive, or fetch a key. The target is an unlocked research pump that talks in the clear.
 *
 * 2. **Held connection instead of connect-on-demand.** Upstream opens and closes a BLE link
 *    around every single command. AAPS already brackets pump work between `connect()` and
 *    `disconnect()` via its command queue, so this implementation connects once, authenticates,
 *    and keeps the link until AAPS asks for it to be dropped. [withConnection] guarantees a
 *    live authenticated session for each read.
 *
 * Authentication is automatic and secret-free: [YpsoBleManager.authenticate] computes
 * MD5(6 MAC bytes ‖ 10-byte salt) and writes it to `CHAR_AUTH_PASSWORD`. Nothing is stored
 * and nothing is asked of the user.
 *
 * Every delivery entry point ([deliverStandardBolus], [setTbr], [writeBasalProfile], …) fails
 * without touching the pump. Wiring them up is session 3's job, on saline.
 */
class YpsoPumpManagerImpl private constructor(
    private val context: Context
) : PumpManager {

    companion object {

        private const val TAG = "YpsoPumpManagerImpl"
        private const val NOT_IMPLEMENTED = "Delivery is not implemented in this build"

        /** Pump needs a moment after the auth write before it answers reads. */
        private const val POST_AUTH_SETTLE_MS = 300L

        fun create(context: Context): YpsoPumpManagerImpl = YpsoPumpManagerImpl(context.applicationContext)
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private val bleManager = YpsoBleManager(context)
    private var bluetoothAdapter: BluetoothAdapter? = null

    private val eventProcessor = PumpEventProcessor()
    private val pumpMutex = Mutex()

    private var pairedMac: String? = null
    private var pairedName: String? = null

    private val _connectionState = MutableStateFlow<PumpConnectionState>(PumpConnectionState.Disconnected)
    override val connectionState: StateFlow<PumpConnectionState> = _connectionState

    private val _pumpStatus = MutableStateFlow<PumpStatus?>(null)
    override val pumpStatus: StateFlow<PumpStatus?> = _pumpStatus

    private val _pumpEvents = MutableSharedFlow<PumpEvent>(replay = 10)
    override val pumpEvents: SharedFlow<PumpEvent> = _pumpEvents

    private val _logs = MutableSharedFlow<String>(replay = 100)

    /** Driver log lines, forwarded by the AAPS service into `AAPSLogger`. */
    val logs: SharedFlow<String> = _logs

    private val _activeProfileId = MutableStateFlow<Int?>(null)
    val activeProfileId: StateFlow<Int?> = _activeProfileId

    /**
     * Whether the pump reports a temporary basal running.
     *
     * The system status does carry the TBR detail after all — percentage in bytes 10–13 and
     * remaining minutes in 14–17 — so [PumpStatus.activeTbr] is populated from the same read.
     * This flag is just the convenient boolean form of `tbrPercent != 100`.
     */
    private val _isTbrActive = MutableStateFlow(false)
    val isTbrActive: StateFlow<Boolean> = _isTbrActive

    override val isPaired: Boolean get() = pairedMac != null
    override val pairedAddress: String? get() = pairedMac

    override val maxBasalProfiles: Int = 2
    override val maxBolusAmount: Double = 25.0
    override val tbrPercentRange: IntRange = 0..200

    /**
     * Selects the salt mixed into the MD5 auth password. `false` (default) uses the salt from
     * vicktor's SDK; `true` uses the one from Andy Rozman's 2022 driver. They differ, and only
     * hardware can say which this pump's firmware expects.
     */
    var useLegacyAuthSalt: Boolean = false
        set(value) {
            field = value
            bleManager.authSalt = if (value) YpsoBleManager.AUTH_SALT_LEGACY else YpsoBleManager.AUTH_SALT_SDK
        }

    init {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = btManager?.adapter

        bleManager.onDisconnectCallback = { reason ->
            log("BLE disconnected (reason=$reason)")
            if (_connectionState.value is PumpConnectionState.Ready) {
                _connectionState.value = PumpConnectionState.Disconnected
                emitEvent(PumpEvent.ConnectionLost("BLE disconnect $reason"))
            }
        }

        scope.launch {
            bleManager.dataReceived.collect { event -> handleDataEvent(event) }
        }
    }

    /**
     * Records which pump this driver talks to. The MAC comes from the AAPS pairing screen,
     * which is the single source of truth; the SDK keeps no device store of its own.
     */
    fun setPairedDevice(mac: String?, name: String?) {
        if (mac != pairedMac) {
            _pumpStatus.value = null
            eventProcessor.reset()
        }
        pairedMac = mac?.takeIf { it.isNotBlank() }
        pairedName = name?.takeIf { it.isNotBlank() }
        log("Paired device set to ${pairedMac ?: "(none)"} ${pairedName ?: ""}")
    }

    // ==================== CONNECTION ====================

    val isConnected: Boolean get() = bleManager.isConnected.value && bleManager.isAuthenticated.value

    /**
     * Connects to the paired MAC, authenticates, and reads enough to prove the session works.
     *
     * On success the link is left open and [connectionState] becomes
     * [PumpConnectionState.Ready]; AAPS closes it again through [disconnect].
     */
    override suspend fun connect() {
        // Each of these used to return silently, which made a refused connect invisible: the
        // caller logged "connect from: …" and then nothing at all appeared. Say why.
        val mac = pairedMac
        if (mac == null) {
            log("connect refused: no paired MAC set on the SDK yet")
            _connectionState.value = PumpConnectionState.NotPaired
            return
        }
        if (!hasBluetoothPermissions()) {
            log("connect refused: missing BLUETOOTH_SCAN/BLUETOOTH_CONNECT permission")
            _connectionState.value = PumpConnectionState.Error("Missing Bluetooth permissions")
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            log("connect refused: Bluetooth is off")
            _connectionState.value = PumpConnectionState.Error("Bluetooth is disabled")
            return
        }

        pumpMutex.withLock {
            if (isConnected) {
                _connectionState.value = PumpConnectionState.Ready
                return
            }
            try {
                establishSession(mac)
            } catch (e: Exception) {
                log("Connect failed: ${e.message}")
                _connectionState.value = PumpConnectionState.Error(e.message ?: "Connect failed")
                suspendDisconnect()
            }
        }
    }

    override suspend fun disconnect() {
        pumpMutex.withLock {
            log("Disconnecting")
            suspendDisconnect()
            _connectionState.value = PumpConnectionState.Disconnected
        }
    }

    /** Bond confirmation is handled by the Android system dialog during pairing. */
    override suspend fun confirmPairing() = Unit

    override suspend fun rejectPairing() = disconnect()

    /** Forgets the pump. The Android bond itself is removed from system Bluetooth settings. */
    override suspend fun unpair() {
        disconnect()
        setPairedDevice(null, null)
        _connectionState.value = PumpConnectionState.NotPaired
    }

    @SuppressLint("MissingPermission")
    private suspend fun establishSession(mac: String) {
        _connectionState.value = PumpConnectionState.Connecting
        log("Connecting to $mac")

        // Connecting to an unbonded pump fails with an opaque GATT status. Say what is actually
        // wrong instead. Bonding needs the user to confirm a code on the pump, so it belongs in
        // the pairing screen, not here on the command queue's thread.
        val bondState = bluetoothAdapter?.getRemoteDevice(mac)?.bondState
        if (bondState != BluetoothDevice.BOND_BONDED) {
            throw IllegalStateException(
                "Pump $mac is not bonded (bond state $bondState) — pair it in the YpsoPump settings first"
            )
        }

        if (!connectDirect(mac)) throw IllegalStateException("BLE connect failed")

        _connectionState.value = PumpConnectionState.Initializing
        if (!suspendAuth()) throw IllegalStateException("Authentication failed")
        log("Authenticated (plaintext, no key exchange)")
        delay(POST_AUTH_SETTLE_MS)

        readPumpInfo()

        // First plaintext read doubles as proof that auth was accepted: an unauthenticated
        // session answers reads with an error rather than data.
        val status = suspendGetSystemStatus()
            ?: throw IllegalStateException("System status read failed after auth")
        updateStatusFromSystem(status)

        _connectionState.value = PumpConnectionState.Ready
        emitEvent(PumpEvent.ConnectionRestored(pairedName ?: "YpsoPump"))
        log("Session ready")
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectDirect(mac: String): Boolean {
        val device: BluetoothDevice = bluetoothAdapter?.getRemoteDevice(mac) ?: return false
        return suspendCancellableCoroutine { cont ->
            bleManager.connect(device)
                .useAutoConnect(false)
                .retry(3, 200)
                .done { cont.resume(true) }
                .fail { _, status ->
                    log("BLE connect failed with status $status")
                    cont.resume(false)
                }
                .enqueue()
        }
    }

    private suspend fun suspendDisconnect() {
        if (!bleManager.isConnected.value) return
        suspendCancellableCoroutine { cont ->
            bleManager.disconnect()
                .done { cont.resume(Unit) }
                .fail { _, _ -> cont.resume(Unit) }
                .enqueue()
        }
        delay(POST_AUTH_SETTLE_MS)
    }

    /**
     * Runs [block] against a live, authenticated session, reconnecting first if the link
     * dropped since the last command. Serialised so two AAPS commands never interleave on
     * the same GATT connection.
     */
    private suspend fun <T> withConnection(block: suspend () -> T): T = pumpMutex.withLock {
        val mac = pairedMac ?: throw IllegalStateException("No paired pump")
        if (!isConnected) establishSession(mac)
        block()
    }

    // ==================== STATUS ====================

    override suspend fun getFullStatus(): PumpStatus = withConnection {
        val status = suspendGetSystemStatus() ?: throw IllegalStateException("System status read failed")
        updateStatusFromSystem(status)
        eventProcessor.processSystemStatus(status).forEach { emitEvent(it) }

        val program = suspendCancellableCoroutine<Char?> { cont ->
            bleManager.readActiveProgram { cont.resume(it) }
        }
        val profileId = when (program) {
            'A'  -> 0
            'B'  -> 1
            else -> null
        }
        _activeProfileId.value = profileId
        _pumpStatus.value = (_pumpStatus.value ?: PumpStatus()).copy(activeProfileId = profileId)
        _pumpStatus.value!!
    }

    // ==================== BASAL PROFILES ====================

    override suspend fun readBasalProfile(profileId: Int): BasalProfile? = withConnection {
        val program = if (profileId == 0) 'A' else 'B'
        val rates = suspendCancellableCoroutine<List<Double>?> { cont ->
            bleManager.readBasalProfile(program) { cont.resume(it) }
        } ?: return@withConnection null

        BasalProfile(
            profileId = profileId,
            name = "Profile $program",
            blocks = rates.map { rate -> BasalBlock(durationMinutes = 60, basalRate = rate) }
        )
    }

    override suspend fun getActiveProfileId(): Int? = withConnection {
        val program = suspendCancellableCoroutine<Char?> { cont ->
            bleManager.readActiveProgram { cont.resume(it) }
        }
        when (program) {
            'A'  -> 0
            'B'  -> 1
            else -> null
        }.also { _activeProfileId.value = it }
    }

    // ==================== HISTORY ====================

    /**
     * Result of one history read: the mapped events, plus the raw entries as text.
     *
     * Both come from a single pass over the pump's logs. Reading history is expensive — every
     * entry costs a write of its index followed by a read of its value — so the parity dump and
     * the mapped events must not be two separate reads.
     */
    data class HistoryReadResult(
        val events: List<PumpHistoryEvent>,
        val rawDump: List<String>
    )

    /**
     * Reads the pump's event and alert logs, returning both the mapped events and a raw dump.
     *
     * The dump includes each entry's slot index *and* its sequence number, which is what settles
     * whether the slot index is a stable identifier or a reused ring-buffer position — the
     * question that decides whether [PumpHistoryEvent.position] is safe to use as a PumpSync
     * `pumpId`.
     *
     * @param maxEntries how many of the most recent entries to fetch from each log.
     */
    suspend fun readHistoryWithDump(maxEntries: Int): HistoryReadResult = withConnection {
        val events = suspendCancellableCoroutine<List<HistoryEntry>> { cont ->
            bleManager.readHistoryEvents(maxEntries) { cont.resume(it) }
        }
        val alerts = suspendCancellableCoroutine<List<HistoryEntry>> { cont ->
            bleManager.readHistoryAlerts(maxEntries) { cont.resume(it) }
        }
        log("History: ${events.size} event(s), ${alerts.size} alert(s)")

        val all = events + alerts
        HistoryReadResult(
            events = all.mapNotNull { toHistoryEvent(it) }.sortedBy { it.timestamp },
            rawDump = all.map { e ->
                "idx=${e.index} seq=${e.sequence} ts=${e.timestamp} type=${e.entryType}(${e.entryTypeName}) " +
                    "v1=${e.value1} v2=${e.value2} v3=${e.value3}"
            }
        )
    }

    /**
     * @param sincePosition ignored — the pump is addressed by slot, so the caller filters what it
     *   has already seen. Kept because it is part of the [PumpManager] contract.
     */
    override suspend fun readHistory(sincePosition: Long, maxEvents: Int): List<PumpHistoryEvent> =
        readHistoryWithDump(maxEvents).events

    private fun toHistoryEvent(entry: HistoryEntry): PumpHistoryEvent? {
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

    // ==================== DELIVERY — NOT IMPLEMENTED ====================
    //
    // Nothing below touches the pump. These exist only to satisfy the PumpManager contract;
    // this build reads, it never delivers. Session 3 wires them up against saline.

    override suspend fun deliverStandardBolus(amount: Double): BolusDeliveryResult =
        refuseDelivery("deliverStandardBolus($amount)")

    override suspend fun deliverExtendedBolus(amount: Double, durationMinutes: Int): BolusDeliveryResult =
        refuseDelivery("deliverExtendedBolus($amount, $durationMinutes)")

    override suspend fun deliverMultiwaveBolus(
        immediateAmount: Double,
        extendedAmount: Double,
        durationMinutes: Int
    ): BolusDeliveryResult = refuseDelivery("deliverMultiwaveBolus($immediateAmount, $extendedAmount, $durationMinutes)")

    override suspend fun cancelBolus(bolusId: Int?): Boolean {
        log("REFUSED cancelBolus — $NOT_IMPLEMENTED")
        return false
    }

    override suspend fun setTbr(percentage: Int, durationMinutes: Int): Boolean {
        log("REFUSED setTbr($percentage%, ${durationMinutes}min) — $NOT_IMPLEMENTED")
        return false
    }

    override suspend fun cancelTbr(): Boolean {
        log("REFUSED cancelTbr — $NOT_IMPLEMENTED")
        return false
    }

    override suspend fun syncDateTime(): Boolean {
        log("REFUSED syncDateTime — $NOT_IMPLEMENTED")
        return false
    }

    override suspend fun writeBasalProfile(profile: BasalProfile): Boolean {
        log("REFUSED writeBasalProfile(${profile.name}) — $NOT_IMPLEMENTED")
        return false
    }

    override suspend fun setActiveProfile(profileId: Int): Boolean {
        log("REFUSED setActiveProfile($profileId) — $NOT_IMPLEMENTED")
        return false
    }

    private fun refuseDelivery(what: String): BolusDeliveryResult {
        log("REFUSED $what — $NOT_IMPLEMENTED")
        return BolusDeliveryResult(success = false, errorMessage = NOT_IMPLEMENTED, deliveredUnits = 0f)
    }

    /** Upstream used this for the relay URL and shared key; both are gone. */
    override fun configure(key: String, value: Any) {
        log("configure($key) ignored — this build has no encryption settings")
    }

    // ==================== LIFECYCLE ====================

    fun close() {
        job.cancel()
        bleManager.close()
    }

    // ==================== SUSPEND HELPERS ====================

    private suspend fun suspendAuth(): Boolean =
        suspendCancellableCoroutine { cont -> bleManager.authenticate { cont.resume(it) } }

    private suspend fun suspendGetSystemStatus(): SystemStatusData? =
        suspendCancellableCoroutine { cont -> bleManager.getSystemStatus { cont.resume(it) } }

    // ==================== INTERNAL ====================

    private fun updateStatusFromSystem(status: SystemStatusData) {
        _isTbrActive.value = status.isTbrRunning

        // Only assert delivery where it has been confirmed against the pump's own display.
        // An unverified mode reads as "not delivering" rather than "delivering", because
        // wrongly telling AAPS that basal is running is the error that actually hurts.
        _pumpStatus.value = (_pumpStatus.value ?: PumpStatus()).copy(
            batteryPercent = status.batteryPercent,
            reservoirUnits = status.insulinRemaining.toDouble(),
            deliveryModeName = status.deliveryModeName,
            isDelivering = DeliveryMode.isDeliveringOrNull(status.deliveryMode) == true,
            activeBasalRate = status.currentBasalRate,
            activeTbr = if (status.isTbrRunning) {
                ActiveTbr(
                    percentage = status.tbrPercent,
                    remainingMinutes = status.tbrRemainingMinutes
                )
            } else null
        )
    }

    private suspend fun readPumpInfo() {
        suspendCancellableCoroutine<String?> { cont -> bleManager.readDeviceName { cont.resume(it) } }
            ?.let { _pumpStatus.value = (_pumpStatus.value ?: PumpStatus()).copy(serialNumber = it) }
        suspendCancellableCoroutine<String?> { cont -> bleManager.readManufacturer { cont.resume(it) } }
            ?.let { _pumpStatus.value = (_pumpStatus.value ?: PumpStatus()).copy(manufacturer = it) }
        suspendCancellableCoroutine<String?> { cont -> bleManager.readSoftwareRevision { cont.resume(it) } }
            ?.let { _pumpStatus.value = (_pumpStatus.value ?: PumpStatus()).copy(firmwareVersion = it) }
    }

    private fun handleDataEvent(event: DataEvent) {
        if (event is DataEvent.ProBluetoothNotification && event.characteristic == "BolusNotification") {
            eventProcessor.processBolusNotification(event.data).forEach { emitEvent(it) }
        }
    }

    private fun hasBluetoothPermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

    private fun log(message: String) {
        Log.d(TAG, message)
        _logs.tryEmit(message)
    }

    private fun emitEvent(event: PumpEvent) {
        scope.launch { _pumpEvents.emit(event) }
    }
}
