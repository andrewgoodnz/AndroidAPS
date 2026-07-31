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
import com.pump.common.OperatingMode
import com.pump.common.PumpConnectionState
import com.pump.common.PumpEvent
import com.pump.common.PumpHistoryEvent
import com.pump.common.PumpManager
import com.pump.common.PumpStatus
import com.ypsopump.sdk.internal.ble.DataEvent
import com.ypsopump.sdk.internal.ble.YpsoBleManager
import com.ypsopump.sdk.internal.connection.ReconnectManager
import com.ypsopump.sdk.internal.data.BolusStatusData
import com.ypsopump.sdk.internal.data.HistoryEntry
import com.ypsopump.sdk.internal.data.PumpClockData
import com.ypsopump.sdk.internal.data.SystemStatusData
import com.ypsopump.sdk.internal.parsing.PumpEventProcessor
import com.ypsopump.sdk.internal.parsing.YpsoHistoryMapper
import com.ypsopump.sdk.internal.protocol.BolusNotificationStatus
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

        /**
         * Grace period after the expected delivery time before going to the pump's log. The pump
         * writes the entry when delivery *ends*, so reading immediately can beat it there.
         */
        private const val BOLUS_LOG_SETTLE_MS = 3_000L

        /** How many of the newest event-log entries to search for the command just issued. */
        private const val HISTORY_SEARCH_ENTRIES = 5

        /**
         * A deeper window for temporary basals, because a TBR's entry is not appended when the TBR
         * *ends* — it is the one written when the TBR started, possibly hours earlier, and anything
         * the pump logged meanwhile sits on top of it. A 9-hour TBR was found at index 3; one more
         * event and a 5-entry window would have missed it entirely. Each entry costs ~195 ms.
         */
        private const val TBR_SEARCH_ENTRIES = 12

        /** Re-reads of the log before giving up on finding the entry. */
        private const val BOLUS_HISTORY_ATTEMPTS = 3

        private const val BOLUS_HISTORY_RETRY_MS = 2_000L

        /**
         * Cap on how many alert entries one check will fetch. A pump that has been offline for a
         * long time can come back with a large jump in the count, and reading hundreds of entries
         * would stall the status poll for minutes; the newest few are what matter.
         */
        private const val MAX_NEW_ALERTS_PER_CHECK = 5

        /** Breathing room between writing a temporary basal and reading byte 10 back. */
        private const val TBR_SETTLE_MS = 700L

        /**
         * The settings indices between the active-program selector (1) and basal program A (14).
         * Never read by anything; see [probeUnknownSettings].
         */
        private val UNKNOWN_SETTINGS_RANGE = 2..13

        /** Hourly slots in a basal program. */
        private const val BASAL_SLOTS = 24

        /**
         * Tolerance for the basal read-back diff, half of the pump's finest 0.01 U/h step. Compared
         * in `Double` deliberately — §4d — because a widened `Float` would report a mismatch on a
         * correct profile for ever.
         */
        private const val BASAL_RATE_EPSILON = 0.005

        /** Re-reads while waiting for the pump to rewrite the TBR entry from type 9 to type 10. */
        private const val TBR_END_LOOKUP_ATTEMPTS = 3

        private const val TBR_END_LOOKUP_RETRY_MS = 1_500L

        /**
         * Every type a single temporary basal's entry can be in. It is **one** entry that changes
         * type as the TBR runs and stops — see [findTbrEndRecord].
         */
        private val TBR_ENTRY_TYPES = setOf(
            EventType.BASAL_PROFILE_TEMP_RUNNING,
            EventType.BASAL_PROFILE_TEMP,
            EventType.BASAL_PROFILE_TEMP_ABORT
        )

        /**
         * Event types a standard bolus can produce. `2` settles the outcome and carries the amount
         * — including for a bolus stopped from AAPS, which this pump logs as type 2 with the
         * partial rather than as type 29.
         *
         * ⚠ **`19` is listed so it can be recognised and waited out, not accepted.** It is the
         * bolus still in progress and its `v1` is the amount *requested*; see [findBolusRecord],
         * which will not return it as an answer while any attempt remains. It is in this set
         * because the alternative — filtering it out — makes an in-progress bolus look like no
         * entry at all, and "no entry" is reported as an unknown amount rather than as a partial.
         */
        private val STANDARD_BOLUS_TYPES = setOf(
            EventType.BOLUS_IMMEDIATE,
            EventType.BOLUS_IMMEDIATE_ABORT,
            EventType.BOLUS_IMMEDIATE_RUNNING
        )

        fun create(context: Context): YpsoPumpManagerImpl = YpsoPumpManagerImpl(context.applicationContext)
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private val bleManager = YpsoBleManager(context)

    /**
     * Reconnects a link that dropped on its own, and **only** one that dropped on its own.
     *
     * Armed for the duration of a bolus and nothing else. That is the window where a lost link
     * genuinely costs something: the pump goes on delivering without the phone, and the amount it
     * actually gave is sitting in its event log waiting to be read (§4g). Everywhere else AAPS drives
     * the connection from its command queue and disconnects deliberately whenever the queue empties,
     * many times an hour — reconnecting after that would fight the queue and hold the link open for
     * ever.
     */
    private val reconnectManager = ReconnectManager(
        scope = scope,
        onReconnect = {
            runCatching { pairedMac?.let { establishSession(it) } }
                .onFailure { log("Reconnect attempt failed: ${it.message}") }
        },
        onStatusUpdate = { log(it) }
    )
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

    /**
     * Matches what `PumpType.YPSOPUMP.tbrSettings` declares, so AAPS is not offered rates this
     * driver refuses out of hand.
     *
     * **The pump is the arbiter, not this range.** Upstream had 0–200% and AAPS declares 500%, and
     * nothing in the sources settles which is right — so a narrow guess here refused 250% *before it
     * reached the pump*, which meant the one experiment that would answer the question could never
     * run. Guarding against the pump's limits by guessing them is not a guard; it is a different
     * guess.
     *
     * Asking is safe because every set is confirmed by reading byte 10 back: if the pump clamps 250%
     * to 200%, the read-back disagrees with the request and the result is an honest failure naming
     * both figures. If it honours 250%, byte 10 says so. Either way hardware answers, and a wrong
     * rate cannot be reported as a right one.
     */
    override val tbrPercentRange: IntRange = 0..500

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
            reconnectManager.onDisconnect()
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
                reconnectManager.onConnected()
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
        // AAPS asked for this one, so a dropped link is not unexpected until something arms it again.
        reconnectManager.disable()
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

        checkForNewAlerts()

        _pumpStatus.value!!
    }

    // ==================== ALARMS ====================

    /**
     * Alerts the pump has raised since the last status read.
     *
     * An occlusion means insulin is not reaching the patient, and during phase 2 one stopped the
     * pump without AAPS learning anything at all — the alert sat in the pump's log, and the log is
     * only read on request. This turns the pump's alert log into something AAPS is told about.
     */
    private val _newAlerts = MutableSharedFlow<PumpHistoryEvent.Alert>(replay = 0, extraBufferCapacity = 8)
    val newAlerts: SharedFlow<PumpHistoryEvent.Alert> = _newAlerts

    /**
     * Entries in the pump's alert log as of the last check; -1 means "not yet known".
     *
     * A flow rather than a plain field so the caller can persist **every** change, not just the
     * ones that raised an alarm. That is what makes an alert arriving while AAPS is closed get
     * announced at the next connection: the stored count is the high-water mark, and the gap
     * between it and the pump's count is the set of alerts nobody has been told about yet. Persist
     * only on alarms and the baseline silently absorbs them instead.
     *
     * The caller seeds it from storage, so the very first check on a fresh install establishes a
     * baseline rather than firing for every occlusion in the pump's two-year history.
     */
    private val _alertCount = MutableStateFlow(-1)
    val alertCount: StateFlow<Int> = _alertCount

    val lastKnownAlertCount: Int get() = _alertCount.value

    fun seedAlertBaseline(count: Int) {
        _alertCount.value = count
    }

    /**
     * Notices new alerts cheaply: one GLB read of the alert *count* per status poll, and entries
     * are only fetched when that count has actually moved. Reading the entries every time would
     * roughly double the cost of a status read for something that changes very rarely.
     */
    private suspend fun checkForNewAlerts() {
        val count = suspendReadAlertsCount() ?: return
        val previous = lastKnownAlertCount
        _alertCount.value = count

        if (previous < 0) {
            log("Alert log has $count entries; taking that as the baseline")
            return
        }
        if (count <= previous) return

        val fresh = (count - previous).coerceAtMost(MAX_NEW_ALERTS_PER_CHECK)
        log("Pump alert log grew from $previous to $count — reading the $fresh newest")

        suspendReadHistoryAlerts(fresh)
            .sortedBy { it.sequence }
            .forEach { entry ->
                val alert = PumpHistoryEvent.Alert(
                    entry.entryType,
                    entry.entryTypeName,
                    entry.timestamp * 1000L,
                    entry.sequence
                )
                log("PUMP ALERT: ${entry.entryTypeName} (type ${entry.entryType}, seq ${entry.sequence})")
                _newAlerts.tryEmit(alert)
                emitEvent(PumpEvent.Occlusion(entry.entryTypeName).takeIf { entry.entryType == EventType.A_OCCLUSION }
                    ?: PumpEvent.DeliveryStopped(entry.entryTypeName))
            }
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
        val rawDump: List<String>,
        /**
         * Every sequence number this read covered, **including entries that mapped to nothing**.
         *
         * A watermark cannot be taken from [events] alone. Clock changes, daily totals and
         * unrecognised types are deliberately dropped by the mapper, so the highest *mapped* sequence
         * can sit well below the highest sequence that actually exists — and a watermark set from it
         * would re-read the same entries for ever. The lowest value here is equally load-bearing: if
         * it is above the caller's watermark, entries were missed between the two and the caller has
         * a gap it needs to know about rather than silently step over.
         */
        val sequencesRead: List<Long>
    ) {

        val newestSequence: Long? get() = sequencesRead.maxOrNull()
        val oldestSequence: Long? get() = sequencesRead.minOrNull()

        /**
         * The events a caller holding [watermark] has not seen, oldest first.
         *
         * Pure, and separated from the service so the rule that decides **what becomes a treatment**
         * can be tested. Ordered by sequence rather than timestamp deliberately: a clock change makes
         * timestamps go backwards (§5), and sequence is the only ordering this pump guarantees.
         *
         * [alreadyRecorded] are sequences the driver put into `PumpSync` itself, at the phone's
         * timestamp. Re-syncing one would upsert on the same `pumpId` and move the treatment to the
         * pump's time, which is worse evidence — so they are excluded rather than merely deduplicated.
         */
        fun freshEvents(watermark: Long, alreadyRecorded: Set<Long> = emptySet()): List<PumpHistoryEvent> =
            events
                .filter { it.position > watermark && it.position !in alreadyRecorded }
                .sortedBy { it.position }

        /**
         * How many entries existed between [watermark] and the oldest entry actually read, and were
         * therefore never seen. Zero when the read reaches back far enough to be contiguous.
         *
         * A non-zero answer means treatments are missing, not that the read was merely short: the
         * pump keeps one sequence counter across both logs, so any gap in it is a gap in what AAPS
         * knows. A single basal profile write appends 24 entries, so a small read window can open one
         * quickly.
         */
        fun missedSince(watermark: Long): Long {
            val oldest = oldestSequence ?: return 0
            return (oldest - watermark - 1).coerceAtLeast(0)
        }
    }

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
            },
            sequencesRead = all.map { it.sequence }
        )
    }

    /**
     * Reads the settings indices nobody has ever looked at, and logs what they hold.
     *
     * `SettingsIndex` knows three things: index 1 is the active program, 14–37 are basal program A
     * and 38–61 are program B. **Indices 2–13 have never been read** — not by this driver and not by
     * the SDK it came from — so twelve of the pump's settings are simply unmapped.
     *
     * That matters more than it sounds. `PumpType.YPSOPUMP` hard-codes a 0.1 U bolus increment, but
     * the pump logs a `5 Bolus Step Changed` event, so the increment is configurable on the pump and
     * AAPS is rounding to a grid it has assumed. If a user sets 0.05 or 1.0, AAPS rounds wrong. The
     * increment is plausibly one of these twelve, and so are the bolus and basal caps behind event
     * types 33 and 34.
     *
     * Read-only and diagnostic: it writes an index and reads a value, which is the same round trip
     * every basal profile read already performs thousands of times. Nothing is written.
     */
    suspend fun probeUnknownSettings(): Map<Int, Int?> = withConnection {
        (UNKNOWN_SETTINGS_RANGE).associateWith { index ->
            val value = suspendCancellableCoroutine<Int?> { cont ->
                bleManager.readSetting(index) { cont.resume(it) }
            }
            log("Setting probe: index $index = ${value ?: "unreadable"}")
            value
        }
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
     *
     * **The outcome comes from the pump's own event log, not from its bolus status.** The status
     * characteristic is useful for progress while delivery runs, but the pump *clears* it the moment
     * delivery ends — so all-zeros means "nothing running now" and says nothing about whether a
     * bolus just completed. Reading that as "nothing was delivered" is what reported a successful
     * 0.5 U bolus as a failure on 2026-07-30, and under-reporting a delivered dose is the direction
     * that corrupts IOB. The log entry (type 2 completed, type 29 aborted, `v1` in centi-units) is
     * written by the pump about itself and does not evaporate.
     *
     * **The pump also echoes back the total it understood, and that is checked** while delivery
     * runs. A disagreement means the payload was misparsed and the wrong dose is on its way, so it
     * is cancelled immediately. That catches a wrong payload *layout*, which a length check cannot.
     */
    /**
     * A delivery as the pump's own event log describes it.
     *
     * [pumpSequence] is the identity the bolus is stored under, and it is deliberately the same
     * sequence number `readHistory` puts in `PumpHistoryEvent.position`. That is what lets a bolus
     * recorded at delivery time and the same bolus seen later by a history sync be recognised as
     * one event rather than two — the entanglement §8 warned about, resolved by both paths naming
     * the event the way the pump names it.
     *
     * Null when the log did not confirm the bolus, in which case nothing may be recorded: an
     * invented identity is worse than a missing record, because it cannot be reconciled later.
     */
    data class ConfirmedBolus(
        val requestedUnits: Double,
        val deliveredUnits: Double,
        val pumpSequence: Long?,
        val timestampMillis: Long?,
        val complete: Boolean,
        val errorMessage: String?
    )

    override suspend fun deliverStandardBolus(amount: Double): BolusDeliveryResult =
        deliverBolusConfirmed(amount).let {
            BolusDeliveryResult(
                success = it.complete,
                errorMessage = it.errorMessage,
                deliveredUnits = it.deliveredUnits.toFloat(),
                requestedUnits = amount.toFloat()
            )
        }

    /**
     * Delivers a standard bolus and reports it as the pump's log describes it.
     *
     * Preferred over [deliverStandardBolus] inside this driver: it keeps the amount in `Double`
     * (the pump's figure is exact centi-units, and squeezing it through a `Float` turns 1.82 into
     * 1.8200000524520874) and it carries the sequence number needed to record the bolus.
     */
    suspend fun deliverBolusConfirmed(amount: Double): ConfirmedBolus = withConnection {
        if (amount <= 0.0) {
            return@withConnection bolusFailure(amount, "Bolus amount must be greater than zero")
        }
        if (amount > maxBolusAmount) {
            return@withConnection bolusFailure(amount, "Bolus of $amount U exceeds the pump maximum of $maxBolusAmount U")
        }

        cancelRequested = false
        _bolusProgress.value = BolusProgress(0.0, amount, finished = false, cancelled = false)

        // Baseline the event log first. This is what lets the bolus we are about to start be told
        // apart from every bolus already in the log, and without it a stale entry could be read
        // back as confirmation of this one. If it cannot be read, nothing is sent: an
        // unconfirmable delivery is not one this driver makes (§0).
        val baselineSequence = newestEventSequence()
        if (baselineSequence == null) {
            return@withConnection bolusFailure(
                amount,
                "The pump's event log could not be read, so a bolus could not be confirmed — nothing was sent"
            )
        }
        log("Bolus $amount U requested. Newest event sequence before sending: $baselineSequence")

        // From here until the outcome is known, a dropped link is worth chasing: the pump will finish
        // the bolus without us and the amount it gave is in its event log. Disarmed in the `finally`
        // below so an idle disconnect never triggers a reconnect.
        reconnectManager.enable()

        if (!suspendStartBolus(amount)) {
            _bolusProgress.value = BolusProgress(0.0, amount, finished = true, cancelled = false)
            return@withConnection bolusFailure(amount, "The pump refused the bolus command")
        }

        val expectedMs = expectedDeliveryMs(amount)
        val timeoutMs = bolusTimeoutMs(amount)
        val startedAt = System.currentTimeMillis()
        log("Bolus sent. Expecting ~${expectedMs / 1000}s at $bolusSpeedUnitsPerMinute U/min, giving up after ${timeoutMs / 1000}s")

        var progressSeen = 0.0
        var sawDelivering = false
        var mismatch: String? = null

        // Follow the pump's bolus status for *progress only*. It is not the outcome: the pump
        // clears these fields the moment delivery finishes, so all-zeros means "nothing running
        // now" and says nothing about whether a bolus just ran. Reading that as "nothing was
        // delivered" is exactly the mistake that reported a successful 0.5 U bolus as a failure.
        var firstPoll = true
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            if (firstPoll) firstPoll = false else delay(BOLUS_POLL_INTERVAL_MS)
            val status = suspendGetBolusStatus()

            if (status != null) {
                // The pump echoes the total it understood. A disagreement means the payload was
                // not parsed the way this code laid it out, so stop it rather than let it run.
                if (status.fastTotal > 0f && abs(status.fastTotal - amount) > BOLUS_AMOUNT_EPSILON) {
                    mismatch = "pump reports a bolus total of ${status.fastTotal} U but $amount U was requested"
                    log("BOLUS MISMATCH: $mismatch — cancelling immediately")
                    suspendCancelBolus()
                    break
                }

                if (status.fastStatus == BolusNotificationStatus.DELIVERING) {
                    sawDelivering = true
                    progressSeen = maxOf(progressSeen, status.fastInjected.toDouble())
                    _bolusProgress.value = BolusProgress(progressSeen, amount, finished = false, cancelled = cancelRequested)
                } else if (sawDelivering && BolusNotificationStatus.hasFinished(status.fastStatus)) {
                    log("Pump bolus status reached ${BolusNotificationStatus.name(status.fastStatus)}")
                    break
                } else if (sawDelivering) {
                    // ⚠ Not the same thing as finishing. Cancelling on the pump produced status 2,
                    // which is in no table we have, and the delivery had not settled. Breaking here
                    // decided the outcome before the pump had finished revising its own log, and the
                    // entry read at that instant still carried the requested amount.
                    log("Pump bolus status left DELIVERING for ${BolusNotificationStatus.name(status.fastStatus)} — not terminal, still waiting")
                }
            }

            // A bolus short enough to finish between polls never shows as DELIVERING at all, so
            // elapsed time has to be able to end the wait too.
            if (System.currentTimeMillis() - startedAt >= expectedMs + BOLUS_LOG_SETTLE_MS) {
                log("Expected delivery time has passed; going to the pump's log for the outcome")
                break
            }
        }

        // The authoritative record: what the pump wrote into its own event log.
        val record = findBolusRecord(baselineSequence)
        reconnectManager.disable()
        val outcome = bolusOutcome(amount, record, progressSeen, mismatch)
        _bolusProgress.value = BolusProgress(
            outcome.deliveredUnits,
            amount,
            finished = true,
            cancelled = outcome.errorMessage != null
        )
        outcome
    }

    /**
     * Turns the pump's own history entry into the result AAPS is told about.
     *
     * `success` requires a completed-bolus entry whose amount matches the request. An aborted entry
     * carries the partial amount and reports failure while still passing that amount up, because
     * the caller needs both halves of that to get IOB right.
     */
    private fun bolusOutcome(
        amount: Double,
        record: HistoryEntry?,
        progressSeen: Double,
        mismatch: String?
    ): ConfirmedBolus {
        if (record == null) {
            // Nothing in the log. It may still have been delivered and simply not logged yet, so
            // this must not be reported as "nothing happened" — the honest answer is that the
            // amount is unknown and the pump has to be looked at.
            val floor = if (progressSeen > 0.0) " The pump reported at least $progressSeen U before the log was read." else ""
            log("Bolus NOT CONFIRMED: no new bolus entry in the pump's event log.$floor")
            return ConfirmedBolus(
                requestedUnits = amount,
                deliveredUnits = progressSeen,
                pumpSequence = null,
                timestampMillis = null,
                complete = false,
                errorMessage = "Not confirmed: the pump's log shows no bolus for this request. " +
                    "Check the pump before bolusing again.$floor"
            )
        }

        val delivered = record.value1 / 100.0
        val aborted = record.entryType == EventType.BOLUS_IMMEDIATE_ABORT
        // The entry never settled out of its running form, so `delivered` is the amount requested
        // echoed back, not a measurement. It cannot be called complete at any value.
        val stillRunning = record.entryType == EventType.BOLUS_IMMEDIATE_RUNNING
        val complete = !aborted && !stillRunning && mismatch == null && abs(delivered - amount) <= BOLUS_AMOUNT_EPSILON

        val error = when {
            mismatch != null -> "$mismatch (the pump logged $delivered U)"
            stillRunning     -> "Not confirmed: the pump's entry still reads 'running', so $delivered U is the " +
                "amount requested and not the amount given. Check the pump."
            // ⚠ Verified on hardware: a bolus stopped from AAPS is logged as type **2**
            // (Bolus Immediate) carrying the partial amount — *not* type 29. So a cancellation
            // cannot be recognised from the entry type, and [cancelRequested] is what distinguishes
            // "the user stopped it" from "the pump stopped short on its own". Both are failures,
            // but only one of them warrants looking at the pump.
            cancelRequested  -> "Bolus stopped after $delivered U of $amount U"
            aborted          -> "Bolus aborted by the pump after $delivered U of $amount U"
            !complete        -> "Pump delivered only $delivered U of the $amount U requested — check the pump"
            else             -> null
        }

        log(
            "Bolus confirmed from the pump's log: seq=${record.sequence} " +
                "type=${record.entryType}(${record.entryTypeName}) v1=${record.value1} → $delivered U" +
                (error?.let { " — $it" } ?: "")
        )

        return ConfirmedBolus(
            requestedUnits = amount,
            deliveredUnits = delivered,
            pumpSequence = record.sequence,
            timestampMillis = record.timestamp * 1000L,
            complete = complete,
            errorMessage = error
        )
    }

    /**
     * Looks for a standard-bolus entry newer than [afterSequence], retrying until it has **settled**.
     *
     * ⚠ **A type-19 entry is the bolus still running, and its `v1` is the amount that was asked
     * for.** Like the temporary basal (§4h), the pump keeps one entry per bolus and rewrites it in
     * place: type 19 `Bolus Immediate Running` while delivering, type 2 `Bolus Immediate` once it
     * stops, with `v1` rewritten from the request to what was actually given.
     *
     * Accepting the type-19 form therefore reads our own request back and calls it a confirmation.
     * A 4 U bolus cancelled on the pump after about 2.5 U logged `seq=160492 type=19 v1=400`, and the
     * driver reported 4.0 U delivered — over-counting IOB by 1.5 U, silently, with every check it
     * had passing. That is the precise failure the "confirm from an effect, never from the command"
     * rule exists to prevent, defeated by an entry that echoes the command.
     *
     * So a type-19 match is treated as *not yet answered* and the read is repeated until the entry
     * settles into a terminal type. If it never settles, the entry is returned anyway — with the
     * caller told it is provisional — because a bolus whose amount is unknown must not be reported
     * as nothing at all.
     */
    private suspend fun findBolusRecord(afterSequence: Long): HistoryEntry? {
        var provisional: HistoryEntry? = null
        repeat(BOLUS_HISTORY_ATTEMPTS) { attempt ->
            val newest = suspendReadHistoryEvents(HISTORY_SEARCH_ENTRIES)
                .filter { it.sequence > afterSequence && it.entryType in STANDARD_BOLUS_TYPES }
                .maxByOrNull { it.sequence }

            if (newest != null && newest.entryType != EventType.BOLUS_IMMEDIATE_RUNNING) return newest
            if (newest != null) {
                provisional = newest
                log(
                    "Bolus entry seq=${newest.sequence} still reads type 19 (running) with v1=${newest.value1}, " +
                        "which is the amount requested rather than the amount given; re-reading"
                )
            }
            if (attempt < BOLUS_HISTORY_ATTEMPTS - 1) {
                if (newest == null) log("No bolus entry above sequence $afterSequence yet; re-reading the log")
                delay(BOLUS_HISTORY_RETRY_MS)
            }
        }
        return provisional
    }

    /** Newest sequence number in the event log, or null if the log could not be read. */
    private suspend fun newestEventSequence(): Long? =
        suspendReadHistoryEvents(1).maxOfOrNull { it.sequence }

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

    private fun bolusFailure(amount: Double, reason: String): ConfirmedBolus {
        log("Bolus refused: $reason")
        return ConfirmedBolus(
            requestedUnits = amount,
            deliveredUnits = 0.0,
            pumpSequence = null,
            timestampMillis = null,
            complete = false,
            errorMessage = reason
        )
    }

    /** How long the pump should take to deliver [amount] at the configured speed. */
    private fun expectedDeliveryMs(amount: Double): Long =
        ((amount / (bolusSpeedUnitsPerMinute / 60.0)) * 1000).toLong()

    /**
     * How long to wait for the pump to finish, from the configured bolus speed plus slack for the
     * command round trip and the pump's own start-up.
     */
    private fun bolusTimeoutMs(amount: Double): Long =
        expectedDeliveryMs(amount) + (BOLUS_TIMEOUT_SLACK_SECONDS * 1000).toLong()

    // ==================== TEMPORARY BASAL ====================

    /**
     * A temporary basal as the pump reports it after the fact.
     *
     * [pumpSequence] comes from the type-9 history entry and is the same identity history sync
     * uses, for the reason set out on [ConfirmedBolus]. Null means the entry was not found, in
     * which case the TBR may still be running — [confirmed] is what says whether it is.
     */
    data class ConfirmedTbr(
        val percentage: Int,
        val durationMinutes: Int,
        val pumpSequence: Long?,
        val timestampMillis: Long?,
        val confirmed: Boolean,
        val errorMessage: String?
    )

    override suspend fun setTbr(percentage: Int, durationMinutes: Int): Boolean =
        setTbrConfirmed(percentage, durationMinutes).confirmed

    /**
     * Sets a temporary basal and confirms it from **byte 10 of the system status**.
     *
     * Unlike the bolus status (§4f), byte 10 is *not* cleared while the temporary basal runs — it
     * reads the live percentage, and 100 when none is active. That makes a direct read-back the
     * right confirmation here rather than the history log, and it is the field already verified on
     * hardware: a 130% temporary basal read back as 130 with the effective rate scaled to match.
     *
     * Refuses rather than clamps a percentage the pump cannot do. Clamping would leave AAPS
     * believing it had set a rate the pump is not running, which is a silent difference in
     * delivered insulin; a visible failure is the lesser harm.
     */
    suspend fun setTbrConfirmed(percentage: Int, durationMinutes: Int): ConfirmedTbr = withConnection {
        if (percentage !in tbrPercentRange) {
            return@withConnection tbrFailure(
                percentage, durationMinutes,
                "$percentage% is outside the range this pump accepts (${tbrPercentRange.first}–${tbrPercentRange.last}%)"
            )
        }
        if (durationMinutes < 0) {
            return@withConnection tbrFailure(percentage, durationMinutes, "Duration cannot be negative")
        }

        // 100% is how this pump expresses "none", so a request for it is a cancellation however it
        // arrived. Treating it as a set would leave AAPS thinking a temporary basal was running.
        if (percentage == SystemStatusData.TBR_PERCENT_NONE) {
            log("setTbr($percentage%) is this pump's 'no TBR' value — cancelling instead")
            val cancelled = cancelTbrConfirmed()
            return@withConnection cancelled.copy(durationMinutes = durationMinutes)
        }

        val baselineSequence = newestEventSequence()

        if (!suspendStartTbr(percentage, durationMinutes)) {
            return@withConnection tbrFailure(percentage, durationMinutes, "The pump refused the temporary basal command")
        }
        delay(TBR_SETTLE_MS)

        val status = suspendGetSystemStatus()
        if (status == null) {
            return@withConnection tbrFailure(
                percentage, durationMinutes,
                "The status did not read back after the write — whether a temporary basal is running is UNKNOWN, check the pump"
            )
        }
        updateStatusFromSystem(status)

        if (status.tbrPercent != percentage) {
            return@withConnection tbrFailure(
                percentage, durationMinutes,
                "Pump reports ${status.tbrPercent}% after being asked for $percentage%"
            )
        }

        val record = baselineSequence?.let { findTbrRecord(it) }
        log(
            "TBR confirmed by read-back: ${status.tbrPercent}% with ${status.tbrRemainingMinutes}min left" +
                (record?.let { ", logged as seq=${it.sequence} v1=${it.value1} v2=${it.value2}" }
                    ?: ", but no type-9 entry found so it cannot be recorded")
        )

        ConfirmedTbr(
            percentage = status.tbrPercent,
            durationMinutes = if (status.tbrRemainingMinutes > 0) status.tbrRemainingMinutes else durationMinutes,
            pumpSequence = record?.sequence,
            timestampMillis = record?.let { it.timestamp * 1000L },
            confirmed = true,
            errorMessage = null
        )
    }

    override suspend fun cancelTbr(): Boolean = cancelTbrConfirmed().confirmed

    /**
     * Cancels the running temporary basal, confirmed by byte 10 returning to 100.
     *
     * Reports success when the pump already had none running: the caller's intent — no temporary
     * basal — is satisfied, and failing would make AAPS retry something already true.
     */
    suspend fun cancelTbrConfirmed(expectedSequence: Long? = null): ConfirmedTbr = withConnection {
        if (!suspendCancelTbr()) {
            return@withConnection tbrFailure(
                SystemStatusData.TBR_PERCENT_NONE, 0, "The pump refused the temporary basal cancel"
            )
        }
        delay(TBR_SETTLE_MS)

        val status = suspendGetSystemStatus()
        if (status == null) {
            return@withConnection tbrFailure(
                SystemStatusData.TBR_PERCENT_NONE, 0,
                "The status did not read back after the cancel — whether a temporary basal is still running is UNKNOWN, check the pump"
            )
        }
        updateStatusFromSystem(status)

        if (status.isTbrRunning) {
            return@withConnection tbrFailure(
                SystemStatusData.TBR_PERCENT_NONE, 0,
                "Pump still reports ${status.tbrPercent}% after the cancel"
            )
        }

        val record = findTbrEndRecord(expectedSequence)
        log(
            "TBR cancel confirmed by read-back: byte 10 is back to ${status.tbrPercent}" +
                (record?.let { ", logged as seq=${it.sequence} type=${it.entryType}" }
                    ?: ", but no end entry found so the stop cannot be recorded")
        )

        ConfirmedTbr(
            percentage = SystemStatusData.TBR_PERCENT_NONE,
            durationMinutes = 0,
            pumpSequence = record?.sequence,
            timestampMillis = record?.let { it.timestamp * 1000L },
            confirmed = true,
            errorMessage = null
        )
    }

    private fun tbrFailure(percentage: Int, durationMinutes: Int, reason: String): ConfirmedTbr {
        log("TBR refused/failed: $reason")
        return ConfirmedTbr(
            percentage = percentage,
            durationMinutes = durationMinutes,
            pumpSequence = null,
            timestampMillis = null,
            confirmed = false,
            errorMessage = reason
        )
    }

    /** Finds the `9 Basal Profile Temp Running` entry the pump wrote for the TBR just set. */
    private suspend fun findTbrRecord(afterSequence: Long): HistoryEntry? =
        findNewestEntry(afterSequence, setOf(EventType.BASAL_PROFILE_TEMP_RUNNING))

    /**
     * Finds the entry identifying the temporary basal that has just ended.
     *
     * ⚠ **This pump rewrites the entry in place — it does not append an end record.** The type-9
     * "Temp Running" entry *becomes* a type-10 "Temp" entry when the temporary basal stops, with
     * `v2` rewritten from the requested duration to the duration it actually ran. Verified on
     * hardware: `seq=160371` read as `type=9 v1=150 v2=30` while running and as
     * `type=10 v1=150 v2=2` after a cancel two minutes later.
     *
     * So a temporary basal has **one** sequence number covering both its start and its end, and
     * searching for an entry *newer* than the start finds nothing.
     *
     * ⚠ **There is no usable sequence lower bound here at all**, which cost a second round to learn.
     * An earlier version filtered on `sequence >= newestEventSequence()`, reasoning that a running
     * TBR's entry is the newest one. That holds when the cancel follows the set by a minute; it
     * fails as soon as the pump logs anything else in between. A 0% TBR cancelled after nine hours
     * had three newer entries — two basal-slot changes and a daily total — so its own entry,
     * `seq=160374`, sat *below* the baseline and was filtered out. The cancel was confirmed, the
     * entry was sitting there at index 3, and the driver still reported "no end entry found" and
     * closed the AAPS record under a timestamp id.
     *
     * So [expectedSequence] — the id AAPS already holds for the temporary basal it believes is
     * running — is the identity, and this only has to go and find it. Without one (an app restart
     * loses it) the newest TBR-typed entry in the window is the best guess available; attributing
     * the stop to a stale sequence is a far smaller problem than not recording it.
     *
     * Returns whatever TBR entry it finds, even one still reading type 9. Whether the pump stopped
     * is decided by **byte 10**, not by this; the log is only being asked for the identity.
     */
    private suspend fun findTbrEndRecord(expectedSequence: Long?): HistoryEntry? {
        var latest: HistoryEntry? = null
        repeat(TBR_END_LOOKUP_ATTEMPTS) { attempt ->
            val entries = suspendReadHistoryEvents(TBR_SEARCH_ENTRIES)
            latest = entries
                .filter { it.entryType in TBR_ENTRY_TYPES }
                .filter { expectedSequence == null || it.sequence == expectedSequence }
                .maxByOrNull { it.sequence }
                ?: latest

            // Type 9 means the pump has not rewritten the entry yet; the rewrite can lag the cancel.
            if (latest != null && latest?.entryType != EventType.BASAL_PROFILE_TEMP_RUNNING) {
                return latest
            }
            if (attempt < TBR_END_LOOKUP_ATTEMPTS - 1) delay(TBR_END_LOOKUP_RETRY_MS)
        }
        return latest
    }

    private suspend fun findNewestEntry(afterSequence: Long, types: Set<Int>): HistoryEntry? =
        suspendReadHistoryEvents(HISTORY_SEARCH_ENTRIES)
            .filter { it.sequence > afterSequence && it.entryType in types }
            .maxByOrNull { it.sequence }

    // ==================== BASAL PROFILE WRITE ====================

    /**
     * Overwrites the pump's active basal program with 24 hourly rates, then reads all 24 back.
     *
     * **The most destructive write in the driver**, so it does three things before claiming success:
     *
     * 1. **Reads and logs the program it is about to replace.** If a write goes wrong halfway there
     *    has to be a record of what was there, and the pump offers no undo. The log line is the only
     *    copy.
     * 2. Writes the 24 slots, stopping at the first failure — a half-written program is the worst
     *    outcome, so it is reported rather than papered over.
     * 3. **Reads all 24 back and diffs them.** Success means the pump's program matches what was
     *    asked for, slot by slot; anything else names the slots that differ.
     *
     * Rates must arrive rounded to the pump's step. The caller owns that, because the step varies
     * with the rate and the table lives in AAPS's `PumpType`.
     */
    override suspend fun writeBasalProfile(profile: BasalProfile): Boolean = withConnection {
        val wanted = profile.blocks.map { it.basalRate }
        if (wanted.size != BASAL_SLOTS) {
            log("writeBasalProfile refused: need $BASAL_SLOTS hourly blocks, got ${wanted.size}")
            return@withConnection false
        }

        val program = suspendReadActiveProgram()
        if (program == null) {
            log("writeBasalProfile refused: could not read which program is active, so there is no safe target")
            return@withConnection false
        }

        // The only copy of what is about to be overwritten.
        val existing = suspendReadBasalProfile(program)
        if (existing == null) {
            log("writeBasalProfile refused: could not read the existing program $program, so it could not be recorded before being replaced")
            return@withConnection false
        }
        log("BASAL PROGRAM $program BEFORE OVERWRITE (restore from this if needed): ${existing.map { "%.2f".format(it) }}")
        log("BASAL PROGRAM $program being written:                                  ${wanted.map { "%.2f".format(it) }}")

        if (!suspendWriteBasalProfile(program, wanted)) {
            log("writeBasalProfile: the write failed part-way. Reading back what the pump now holds")
            suspendReadBasalProfile(program)?.let {
                log("BASAL PROGRAM $program AFTER A FAILED WRITE: ${it.map { "%.2f".format(it) }}")
            }
            return@withConnection false
        }

        val readBack = suspendReadBasalProfile(program)
        if (readBack == null) {
            log("writeBasalProfile: the program did not read back, so what the pump now holds is UNKNOWN — read it on the pump")
            return@withConnection false
        }

        val mismatches = wanted.indices.filter { abs(readBack[it] - wanted[it]) > BASAL_RATE_EPSILON }
        if (mismatches.isNotEmpty()) {
            log(
                "writeBasalProfile FAILED the read-back diff at ${mismatches.size} of $BASAL_SLOTS slots: " +
                    mismatches.joinToString { "h$it wanted ${wanted[it]} got ${readBack[it]}" }
            )
            return@withConnection false
        }

        log("Basal program $program written and confirmed: all $BASAL_SLOTS slots read back as asked")
        true
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

    private suspend fun suspendReadHistoryEvents(maxEntries: Int): List<HistoryEntry> =
        suspendCancellableCoroutine { cont -> bleManager.readHistoryEvents(maxEntries) { cont.resume(it) } }

    private suspend fun suspendReadHistoryAlerts(maxEntries: Int): List<HistoryEntry> =
        suspendCancellableCoroutine { cont -> bleManager.readHistoryAlerts(maxEntries) { cont.resume(it) } }

    private suspend fun suspendReadAlertsCount(): Int? =
        suspendCancellableCoroutine { cont -> bleManager.readHistoryAlertsCount { cont.resume(it) } }

    private suspend fun suspendStartTbr(percentage: Int, durationMinutes: Int): Boolean =
        suspendCancellableCoroutine { cont -> bleManager.startTbr(percentage, durationMinutes) { cont.resume(it) } }

    private suspend fun suspendCancelTbr(): Boolean =
        suspendCancellableCoroutine { cont -> bleManager.cancelTbr { cont.resume(it) } }

    private suspend fun suspendReadActiveProgram(): Char? =
        suspendCancellableCoroutine { cont -> bleManager.readActiveProgram { cont.resume(it) } }

    private suspend fun suspendReadBasalProfile(program: Char): List<Double>? =
        suspendCancellableCoroutine { cont -> bleManager.readBasalProfile(program) { cont.resume(it) } }

    private suspend fun suspendWriteBasalProfile(program: Char, rates: List<Double>): Boolean =
        suspendCancellableCoroutine { cont -> bleManager.writeBasalProfile(program, rates) { cont.resume(it) } }

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
            // ⚠ Deliberately three states, not two. `isDelivering` collapses "confirmed stopped" and
            // "never seen this mode" into the same `false`, which is right for gating the loop — the
            // safe answer to "don't know" is "don't act" — but wrong for anything that *writes a
            // record*. Recording a therapy event on the strength of a byte nobody has identified is
            // the kind of claim this driver does not make, so callers that persist something must
            // require STOPPED and treat UNKNOWN as "ask again later".
            operatingMode = when (DeliveryMode.isDeliveringOrNull(status.deliveryMode)) {
                true  -> OperatingMode.STARTED
                false -> OperatingMode.STOPPED
                null  -> OperatingMode.UNKNOWN
            },
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
            ?.let { _pumpStatus.value = (_pumpStatus.value ?: PumpStatus()).copy(serialNumber = it.cleanBleString()) }
        suspendCancellableCoroutine<String?> { cont -> bleManager.readManufacturer { cont.resume(it) } }
            ?.let { _pumpStatus.value = (_pumpStatus.value ?: PumpStatus()).copy(manufacturer = it.cleanBleString()) }
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

    /**
     * Strips the NUL terminator the pump leaves on its string characteristics.
     *
     * `getStringValue` hands back the terminator as part of the value, so the serial arrived as
     * `10074292`. Cosmetic on screen, but this string becomes `pumpSerial` on every
     * `PumpSync` call — and a serial that differs by an invisible byte would split records between
     * two apparent pumps.
     */
    private fun String.cleanBleString(): String = trim { it.code <= 0x20 }

    private fun log(message: String) {
        Log.d(TAG, message)
        _logs.tryEmit(message)
    }

    private fun emitEvent(event: PumpEvent) {
        scope.launch { _pumpEvents.emit(event) }
    }
}
