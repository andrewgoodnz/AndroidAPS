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
import com.pump.common.PumpConnectionState
import com.pump.common.PumpEvent
import com.pump.common.PumpHistoryEvent
import com.pump.common.PumpManager
import com.pump.common.PumpStatus
import com.ypsopump.sdk.internal.ble.DataEvent
import com.ypsopump.sdk.internal.ble.YpsoBleManager
import com.ypsopump.sdk.internal.data.BolusStatusData
import com.ypsopump.sdk.internal.data.HistoryEntry
import com.ypsopump.sdk.internal.data.PumpClockData
import com.ypsopump.sdk.internal.data.SystemStatusData
import com.ypsopump.sdk.internal.parsing.PumpEventProcessor
import com.ypsopump.sdk.internal.parsing.YpsoHistoryMapper
import com.ypsopump.sdk.internal.protocol.BolusNotificationStatus
import com.ypsopump.sdk.internal.protocol.DeliveryMode
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
import kotlin.math.abs

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
 * Every delivery entry point ([deliverStandardBolus], [setTbr], [writeBasalProfile], …) still
 * fails without touching the pump. The one write this build makes is [syncDateTime], which moves
 * no insulin and exists to prove the write path — and which reports success only after reading
 * the pump's clock back.
 */
class YpsoPumpManagerImpl private constructor(
    private val context: Context
) : PumpManager {

    companion object {

        private const val TAG = "YpsoPumpManagerImpl"
        private const val NOT_IMPLEMENTED = "Delivery is not implemented in this build"

        /** Pump needs a moment after the auth write before it answers reads. */
        private const val POST_AUTH_SETTLE_MS = 300L

        /** Breathing room between writing the clock and reading it back. */
        private const val CLOCK_SETTLE_MS = 500L

        /**
         * How far the pump's clock may sit from the phone's and still count as adopted.
         *
         * Generous next to the round trip it has to cover — two reads, two writes and a settle —
         * and far tighter than the offset the saline procedure deliberately dials in, so it
         * separates "the pump took our time" from "the pump ignored us" without being fooled by
         * BLE latency.
         */
        private const val CLOCK_MATCH_TOLERANCE_MS = 90_000L

        /**
         * How often the pump's bolus status is read while a bolus runs. At 12 U/min this bounds how
         * much can go in between the pump echoing a total we did not ask for and the cancel.
         */
        private const val BOLUS_POLL_INTERVAL_MS = 500L

        /**
         * Slack on top of the calculated delivery time, covering the command round trip and the
         * pump's own start-up. Finishing the wait early is the dangerous direction — it would
         * report a bolus as done while the pump is still delivering — so this is generous.
         */
        private const val BOLUS_TIMEOUT_SLACK_SECONDS = 60.0

        /**
         * Tolerance when comparing insulin amounts, half of the pump's 0.01 U resolution. Used
         * both to decide a bolus completed and to catch the pump reporting a total that is not
         * what was asked for.
         */
        private const val BOLUS_AMOUNT_EPSILON = 0.005

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

    /**
     * How far the pump's clock sat from the phone's at the last read, in milliseconds; positive
     * means the pump is ahead. Null until it has been read, and null again whenever a read fails.
     *
     * The skew rather than the reading itself, because both clocks keep running: a pump time
     * captured five minutes ago is misleading on screen, while the offset between the two stays
     * true until something changes it.
     */
    private val _pumpClockSkewMs = MutableStateFlow<Long?>(null)
    val pumpClockSkewMs: StateFlow<Long?> = _pumpClockSkewMs

    /**
     * Progress of the bolus currently running, or the last one to finish.
     *
     * [deliveredUnits] is always the pump's own figure, read from `CHAR_BOLUS_STATUS` — never an
     * estimate from elapsed time, because an estimate would keep advancing after an occlusion or
     * a stall and show insulin that never went in.
     */
    data class BolusProgress(
        val deliveredUnits: Double,
        val requestedUnits: Double,
        val finished: Boolean,
        val cancelled: Boolean
    )

    private val _bolusProgress = MutableStateFlow<BolusProgress?>(null)
    val bolusProgress: StateFlow<BolusProgress?> = _bolusProgress

    /**
     * Bolus speed in U/min, mirrored from the driver preference; sets the completion timeout.
     * Defaults to the 12 U/min the Lotus export had configured.
     */
    var bolusSpeedUnitsPerMinute: Double = 12.0

    /** Set by [cancelBolus] from another thread, read by the delivery loop. */
    @Volatile
    private var cancelRequested: Boolean = false

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

        // Read-only, and the surface the clock canary is judged on: it tells the user how far the
        // pump's clock has drifted before anyone asks for it to be written.
        _pumpClockSkewMs.value = suspendReadClock()?.let { it.epochMillis() - System.currentTimeMillis() }

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
            events = all.mapNotNull { YpsoHistoryMapper.toHistoryEvent(it) }.sortedBy { it.timestamp },
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

    // ==================== SYSTEM CLOCK — THE ONLY WRITE IN THIS BUILD ====================

    /**
     * Sets the pump's clock from the phone's. This is the driver's first write of any kind.
     *
     * It is the canary for the write path precisely because nothing here can move insulin: if the
     * wire format is wrong the pump refuses the write at the ATT layer and the clock is untouched,
     * and if the format is right but the layout is wrong the read-back says so.
     *
     * **Success is decided by reading the clock back, never by the write callback.** This pump
     * does not NAK a bad write — the wrong auth password produced a perfectly successful GATT
     * write and only failed on the next read (PORTING_NOTES §3) — so a write callback tells us
     * only that the ATT layer liked the length. The clock is an effect we can go and look at, so
     * we look.
     *
     * Returns false, having written nothing, if the clock cannot be read first: without a
     * read-back there is no way to confirm the write, and an unconfirmable write is one this
     * driver does not make.
     */
    override suspend fun syncDateTime(): Boolean = withConnection {
        val before = suspendReadClock()
        if (before == null) {
            log("syncDateTime refused: the pump's clock did not read back, so a write could not be confirmed")
            _pumpClockSkewMs.value = null
            return@withConnection false
        }
        val skewBefore = before.epochMillis() - System.currentTimeMillis()
        _pumpClockSkewMs.value = skewBefore
        log("Pump clock before the write: ${before.formatted()} (${skewBefore / 1000}s from the phone)")

        if (!suspendSyncTime()) {
            log("syncDateTime: the clock write did not go out; the pump's clock is unchanged")
            return@withConnection false
        }
        delay(CLOCK_SETTLE_MS)

        val after = suspendReadClock()
        if (after == null) {
            // The write went out and we cannot see what it did. Say so loudly rather than
            // reporting either outcome.
            log("syncDateTime: the clock did not read back after the write — the pump's clock is now UNKNOWN, check it on the pump")
            _pumpClockSkewMs.value = null
            return@withConnection false
        }

        val skewAfter = after.epochMillis() - System.currentTimeMillis()
        _pumpClockSkewMs.value = skewAfter
        val adopted = abs(skewAfter) <= CLOCK_MATCH_TOLERANCE_MS
        if (adopted) {
            log("syncDateTime confirmed by read-back: pump clock is now ${after.formatted()}, ${skewAfter / 1000}s from the phone")
        } else {
            log(
                "syncDateTime FAILED: the write was accepted but the pump clock still reads " +
                    "${after.formatted()}, ${skewAfter / 1000}s from the phone"
            )
        }
        adopted
    }

    // ==================== STANDARD BOLUS ====================

    /**
     * Delivers a standard bolus, reporting only what the pump says it actually delivered.
     *
     * The write result is ignored as evidence, per §0: this pump accepts a bad write silently.
     * Progress and the final amount both come from reading `CHAR_BOLUS_STATUS`, which carries the
     * pump's own injected/total figures.
     *
     * **The pump echoes back the total it understood, and that is checked.** If it does not match
     * what was asked, the payload was misparsed and the pump is about to deliver the wrong dose,
     * so delivery is cancelled immediately rather than allowed to run. That is the single most
     * valuable check here — it catches a wrong payload *layout*, which a length check cannot.
     */
    override suspend fun deliverStandardBolus(amount: Double): BolusDeliveryResult = withConnection {
        if (amount <= 0.0) {
            return@withConnection bolusFailure(amount, "Bolus amount must be greater than zero")
        }
        if (amount > maxBolusAmount) {
            return@withConnection bolusFailure(amount, "Bolus of $amount U exceeds the pump maximum of $maxBolusAmount U")
        }

        cancelRequested = false
        _bolusProgress.value = BolusProgress(0.0, amount, finished = false, cancelled = false)

        val before = suspendGetBolusStatus()
        log(
            "Bolus $amount U requested. Pump bolus status before: status=${before?.fastStatus} " +
                "seq=${before?.fastSequence} injected=${before?.fastInjected} total=${before?.fastTotal}"
        )

        if (!suspendStartBolus(amount)) {
            _bolusProgress.value = BolusProgress(0.0, amount, finished = true, cancelled = false)
            return@withConnection bolusFailure(amount, "The pump refused the bolus command")
        }

        val timeoutMs = bolusTimeoutMs(amount)
        val startedAt = System.currentTimeMillis()
        log("Bolus sent. Waiting up to ${timeoutMs / 1000}s at $bolusSpeedUnitsPerMinute U/min")

        var delivered = 0.0
        var finishedByPump = false
        var cancelled = false
        var mismatch: String? = null

        var firstPoll = true
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            // The first read happens immediately: it carries the total the pump understood, and
            // the sooner a disagreement is seen the less has gone in by the time we cancel.
            if (firstPoll) firstPoll = false else delay(BOLUS_POLL_INTERVAL_MS)
            val status = suspendGetBolusStatus() ?: continue

            // The pump's own idea of what it was asked for. A disagreement here means the 13-byte
            // payload was not parsed the way this code laid it out.
            if (status.fastTotal > 0f && abs(status.fastTotal - amount) > BOLUS_AMOUNT_EPSILON) {
                mismatch = "pump reports a bolus total of ${status.fastTotal} U but $amount U was requested"
                log("BOLUS MISMATCH: $mismatch — cancelling immediately")
                suspendCancelBolus()
                cancelled = true
                delivered = status.fastInjected.toDouble()
                break
            }

            delivered = status.fastInjected.toDouble()
            _bolusProgress.value = BolusProgress(delivered, amount, finished = false, cancelled = cancelRequested)

            if (BolusNotificationStatus.isTerminal(status.fastStatus)) {
                finishedByPump = true
                cancelled = status.fastStatus == BolusNotificationStatus.CANCELLED
                log("Bolus finished: pump status ${BolusNotificationStatus.name(status.fastStatus)}, injected $delivered U")
                break
            }
        }

        // One last read, so the reported amount is the pump's final word rather than the last
        // sample taken mid-delivery.
        suspendGetBolusStatus()?.let { delivered = it.fastInjected.toDouble() }
        _bolusProgress.value = BolusProgress(delivered, amount, finished = true, cancelled = cancelled)

        val complete = finishedByPump && !cancelled && abs(delivered - amount) <= BOLUS_AMOUNT_EPSILON
        val error = when {
            mismatch != null -> mismatch
            cancelled        -> "Bolus cancelled after $delivered U of $amount U"
            !finishedByPump  -> "Timed out waiting for the pump to finish; it reported $delivered U of $amount U"
            !complete        -> "Pump delivered $delivered U of the $amount U requested"
            else             -> null
        }
        error?.let { log("Bolus result: $it") }
            ?: log("Bolus complete and confirmed by the pump: $delivered U")

        BolusDeliveryResult(
            success = complete,
            errorMessage = error,
            deliveredUnits = delivered.toFloat(),
            requestedUnits = amount.toFloat()
        )
    }

    /**
     * Asks the pump to stop the running bolus.
     *
     * **Deliberately does not take the connection lock.** [deliverStandardBolus] holds it for the
     * whole delivery, and a stop request that has to queue behind the thing it is stopping is not
     * a stop request. Nordic's `BleManager` serialises the GATT operations themselves, so issuing
     * the write from another thread is safe.
     *
     * A `true` means the cancel command was sent, not that delivery stopped — how much actually
     * went in comes from the pump, and [deliverStandardBolus] reports it.
     */
    override suspend fun cancelBolus(bolusId: Int?): Boolean {
        cancelRequested = true
        if (!isConnected) {
            log("cancelBolus: not connected, nothing sent")
            return false
        }
        val sent = suspendCancelBolus()
        log("cancelBolus command sent=$sent — the amount delivered comes from the pump, not from this result")
        return sent
    }

    private fun bolusFailure(amount: Double, reason: String): BolusDeliveryResult {
        log("Bolus refused: $reason")
        return BolusDeliveryResult(
            success = false,
            errorMessage = reason,
            deliveredUnits = 0f,
            requestedUnits = amount.toFloat()
        )
    }

    /**
     * How long to wait for the pump to finish, from the configured bolus speed plus slack for the
     * command round trip and the pump's own start-up.
     */
    private fun bolusTimeoutMs(amount: Double): Long {
        val deliverySeconds = amount / (bolusSpeedUnitsPerMinute / 60.0)
        return ((deliverySeconds + BOLUS_TIMEOUT_SLACK_SECONDS) * 1000).toLong()
    }

    // ==================== DELIVERY — NOT IMPLEMENTED ====================
    //
    // Nothing below touches the pump. These exist only to satisfy the PumpManager contract;
    // this build sets the clock, reads, and never delivers. Each of these is its own hardware
    // gate on saline, to be opened one at a time — never as a batch.

    override suspend fun deliverExtendedBolus(amount: Double, durationMinutes: Int): BolusDeliveryResult =
        refuseDelivery("deliverExtendedBolus($amount, $durationMinutes)")

    override suspend fun deliverMultiwaveBolus(
        immediateAmount: Double,
        extendedAmount: Double,
        durationMinutes: Int
    ): BolusDeliveryResult = refuseDelivery("deliverMultiwaveBolus($immediateAmount, $extendedAmount, $durationMinutes)")

    override suspend fun setTbr(percentage: Int, durationMinutes: Int): Boolean {
        log("REFUSED setTbr($percentage%, ${durationMinutes}min) — $NOT_IMPLEMENTED")
        return false
    }

    override suspend fun cancelTbr(): Boolean {
        log("REFUSED cancelTbr — $NOT_IMPLEMENTED")
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

    private suspend fun suspendReadClock(): PumpClockData? =
        suspendCancellableCoroutine { cont -> bleManager.readClock { cont.resume(it) } }

    private suspend fun suspendSyncTime(): Boolean =
        suspendCancellableCoroutine { cont -> bleManager.syncTime { cont.resume(it) } }

    private suspend fun suspendGetBolusStatus(): BolusStatusData? =
        suspendCancellableCoroutine { cont -> bleManager.getBolusStatus { cont.resume(it) } }

    private suspend fun suspendStartBolus(amount: Double): Boolean =
        suspendCancellableCoroutine { cont -> bleManager.startBolus(amount.toFloat()) { cont.resume(it) } }

    private suspend fun suspendCancelBolus(): Boolean =
        suspendCancellableCoroutine { cont -> bleManager.cancelBolus("fast") { cont.resume(it) } }

    // ==================== INTERNAL ====================

    private fun updateStatusFromSystem(status: SystemStatusData) {
        _isTbrActive.value = status.isTbrRunning

        // Only assert delivery where it has been confirmed against the pump's own display.
        // An unverified mode reads as "not delivering" rather than "delivering", because
        // wrongly telling AAPS that basal is running is the error that actually hurts.
        _pumpStatus.value = (_pumpStatus.value ?: PumpStatus()).copy(
            batteryPercent = status.batteryPercent,
            // Null rather than a number when the pump has no cartridge to measure: a reservoir of
            // -0.01 U reads as "empty" to anything acting on it, which is a different claim.
            reservoirUnits = if (status.reservoirKnown) status.insulinRemaining.toDouble() else null,
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
