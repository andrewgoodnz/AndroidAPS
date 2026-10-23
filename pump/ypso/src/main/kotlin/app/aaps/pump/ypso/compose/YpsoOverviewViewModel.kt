package app.aaps.pump.ypso.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.pump.ActionCategory
import app.aaps.core.ui.compose.pump.PumpAction
import app.aaps.core.ui.compose.pump.PumpCommunicationStatus
import app.aaps.core.ui.compose.pump.PumpInfoRow
import app.aaps.core.ui.compose.pump.PumpOverviewUiState
import app.aaps.core.ui.compose.pump.StatusBanner
import app.aaps.core.ui.compose.pump.tickerFlow
import app.aaps.pump.ypso.R
import app.aaps.pump.ypso.YpsoPlugin
import app.aaps.pump.ypso.YpsoPump
import app.aaps.pump.ypso.events.EventYpsoPumpDataChanged
import app.aaps.pump.ypso.ui.YpsoLoadHistoryCommand
import app.aaps.pump.ypso.ui.YpsoSyncTimeCommand
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs

/** One-shot things the screen has to do, which state alone cannot express. */
sealed class YpsoOverviewEvent {

    /** Ask before writing the clock. This is the one control on this tab that writes to the pump. */
    data object ConfirmSetClock : YpsoOverviewEvent()

    /** Open the pairing screen. Gated by the protection check and the BLE pre-check before it opens. */
    data object StartPairing : YpsoOverviewEvent()
    data class ShowDialog(val title: String, val message: String) : YpsoOverviewEvent()
}

/**
 * Pump status screen state.
 *
 * Its real job is the firmware parity check: every value shown here is meant to be compared
 * against the same value on the pump's own display. The history dump is deliberately raw for the
 * same reason, and the pump-clock row is deliberately a skew rather than a timestamp - both
 * clocks keep running, so the offset stays true while a captured reading does not.
 *
 * The delivery-mode row shows the driver's *reading* of byte 0. The raw byte and the whole status
 * payload go to the log instead (`[ypso-state]`), because the states that would identify the 254
 * unmapped values are the ones where nobody is looking at a screen - see `PORTING_NOTES` 8.3.
 *
 * [YpsoPump] holds plain properties rather than flows, so the state is rebuilt whenever the driver
 * says something changed ([EventYpsoPumpDataChanged], [EventPumpStatusChanged]) and once a minute
 * besides, so the "last connection" and temp-basal countdown rows stay honest while nothing moves.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Stable
@Inject
class YpsoOverviewViewModel(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val rxBus: RxBus,
    private val dateUtil: DateUtil,
    private val commandQueue: CommandQueue,
    private val ypsoPump: YpsoPump,
    private val ypsoPlugin: YpsoPlugin,
    private val fabricPrivacy: FabricPrivacy
) : ViewModel() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val communicationStatus = PumpCommunicationStatus(rxBus, commandQueue, rh, scope)
    private val disposable = CompositeDisposable()

    /** Bumped whenever the driver reports a change, which is what makes the state recompute. */
    private val pumpDataChanged = MutableStateFlow(0L)

    private val _events = MutableSharedFlow<YpsoOverviewEvent>(extraBufferCapacity = 5)
    val events: SharedFlow<YpsoOverviewEvent> = _events

    init {
        rxBus.toFlow(EventYpsoPumpDataChanged::class)
            .collectResilient(scope, aapsLogger, LTag.PUMP) { pumpDataChanged.value = dateUtil.now() }
        rxBus.toFlow(EventPumpStatusChanged::class)
            .collectResilient(scope, aapsLogger, LTag.PUMP) { pumpDataChanged.value = dateUtil.now() }
    }

    val uiState: StateFlow<PumpOverviewUiState> = combine(
        pumpDataChanged,
        communicationStatus.refreshTrigger,
        communicationStatus.queueStatusFlow,
        tickerFlow(60_000L)
    ) { _, _, _, _ -> buildState() }
        .stateIn(scope, SharingStarted.Eagerly, buildState())

    private fun buildState(): PumpOverviewUiState {
        val connected = ypsoPlugin.isConnected()
        val connecting = ypsoPlugin.isConnecting()
        return PumpOverviewUiState(
            statusBanner = StatusBanner(
                text = rh.gs(
                    when {
                        connected  -> R.string.ypso_connected
                        connecting -> R.string.ypso_connecting
                        else       -> R.string.ypso_disconnected
                    }
                ),
                level = when {
                    connected  -> StatusLevel.NORMAL
                    connecting -> StatusLevel.UNSPECIFIED
                    else       -> StatusLevel.WARNING
                }
            ),
            infoRows = infoRows(),
            primaryActions = listOf(
                PumpAction(
                    label = rh.gs(R.string.ypso_refresh),
                    icon = Icons.Filled.Refresh,
                    onClick = ::onRefresh
                ),
                PumpAction(
                    label = rh.gs(R.string.ypso_read_history),
                    icon = Icons.Filled.History,
                    onClick = ::onReadHistory
                ),
                PumpAction(
                    label = rh.gs(R.string.ypso_set_clock),
                    icon = Icons.Filled.Schedule,
                    onClick = ::onSetClockRequested
                )
            ),
            managementActions = listOf(
                PumpAction(
                    label = rh.gs(R.string.ypso_pairing_title),
                    icon = Icons.Filled.Bluetooth,
                    category = ActionCategory.MANAGEMENT,
                    onClick = ::onPairRequested
                )
            ),
            queueStatus = communicationStatus.queueStatus()
        )
    }

    private fun infoRows(): List<PumpInfoRow> {
        val connectedOnce = ypsoPump.lastConnection > 0
        return listOf(
            PumpInfoRow(rh.gs(R.string.ypso_serial_label), ypsoPump.serialNumber.ifBlank { PLACEHOLDER }),
            PumpInfoRow(rh.gs(R.string.ypso_firmware_label), ypsoPump.firmwareVersion.ifBlank { PLACEHOLDER }),
            PumpInfoRow(
                rh.gs(R.string.ypso_mac_label),
                ypsoPump.pumpMac.ifBlank { rh.gs(R.string.ypso_not_paired) }
            ),
            PumpInfoRow(
                rh.gs(R.string.ypso_last_connection_label),
                if (connectedOnce) dateUtil.minAgo(rh, ypsoPump.lastConnection) else PLACEHOLDER
            ),
            PumpInfoRow(
                rh.gs(R.string.ypso_reservoir_label),
                if (connectedOnce) rh.gs(R.string.ypso_reservoir_value, ypsoPump.reservoir) else PLACEHOLDER
            ),
            PumpInfoRow(
                rh.gs(R.string.ypso_battery_label),
                if (ypsoPump.batteryPercent > 0) rh.gs(R.string.ypso_battery_value, ypsoPump.batteryPercent) else PLACEHOLDER
            ),
            PumpInfoRow(rh.gs(R.string.ypso_delivery_mode_label), ypsoPump.deliveryModeName.ifBlank { PLACEHOLDER }),
            PumpInfoRow(
                rh.gs(R.string.ypso_basal_rate_label),
                if (ypsoPump.basalProfileRates.isNotEmpty())
                    rh.gs(R.string.ypso_basal_rate_value, ypsoPump.baseBasalRate)
                else PLACEHOLDER
            ),
            PumpInfoRow(
                rh.gs(R.string.ypso_active_profile_label),
                when (ypsoPump.activeProfileId) {
                    0    -> PROFILE_A
                    1    -> PROFILE_B
                    else -> PLACEHOLDER
                }
            ),
            PumpInfoRow(rh.gs(R.string.ypso_temp_basal_label), tempBasalText()),
            PumpInfoRow(rh.gs(R.string.ypso_clock_label), clockSkewText())
        )
    }

    private fun tempBasalText(): String =
        ypsoPump.activeTbr
            ?.let { rh.gs(R.string.ypso_tbr_value, it.percentage, it.remainingMinutes) }
            ?: if (ypsoPump.isTbrRunning) rh.gs(R.string.ypso_tbr_running_no_detail) else PLACEHOLDER

    private fun clockSkewText(): String =
        ypsoPump.pumpClockSkewMs?.let { skew ->
            val magnitude = durationText(abs(skew))
            when {
                abs(skew) <= CLOCK_IN_SYNC_MS -> rh.gs(R.string.ypso_clock_in_sync, magnitude)
                skew > 0                      -> rh.gs(R.string.ypso_clock_ahead, magnitude)
                else                          -> rh.gs(R.string.ypso_clock_behind, magnitude)
            }
        } ?: PLACEHOLDER

    /** Coarse, signed-magnitude rendering - the number that matters here is the order of it. */
    private fun durationText(millis: Long): String {
        val seconds = millis / 1000
        return when {
            seconds < 60   -> rh.gs(R.string.ypso_duration_seconds, seconds)
            seconds < 3600 -> rh.gs(R.string.ypso_duration_minutes, seconds / 60)
            else           -> rh.gs(R.string.ypso_duration_hours_minutes, seconds / 3600, (seconds % 3600) / 60)
        }
    }

    private fun onRefresh() {
        viewModelScope.launch {
            commandQueue.readStatus(rh.gs(R.string.ypso_requested_by_user))
        }
    }

    /**
     * Runs on the command queue so it shares the normal connect/disconnect cycle rather than
     * opening a second BLE session from the UI.
     */
    private fun onReadHistory() {
        viewModelScope.launch {
            commandQueue.customCommand(YpsoLoadHistoryCommand())
            val dump = ypsoPump.lastHistoryDump
            val text = if (dump.isEmpty()) rh.gs(R.string.ypso_no_history) else dump.joinToString("\n")
            aapsLogger.debug(LTag.PUMP, "History dump shown: ${dump.size} entries")
            _events.emit(YpsoOverviewEvent.ShowDialog(rh.gs(R.string.ypso_history_title), text))
        }
    }

    private fun onSetClockRequested() {
        viewModelScope.launch { _events.emit(YpsoOverviewEvent.ConfirmSetClock) }
    }

    private fun onPairRequested() {
        viewModelScope.launch { _events.emit(YpsoOverviewEvent.StartPairing) }
    }

    /**
     * Reports the outcome from [YpsoPump.lastClockWriteConfirmed] rather than from the command
     * result, which says only that the command ran. A dropped command therefore reads as
     * "not confirmed", which is the truthful answer. The pump accepts bad writes silently, so what
     * is reported is what reading the clock back said.
     */
    fun performSetClock() {
        viewModelScope.launch {
            commandQueue.customCommand(YpsoSyncTimeCommand())
            val confirmed = ypsoPump.lastClockWriteConfirmed == true
            val message = rh.gs(if (confirmed) R.string.ypso_clock_set_ok else R.string.ypso_clock_set_failed)
            aapsLogger.info(LTag.PUMP, "Clock write result shown: confirmed=$confirmed")
            _events.emit(YpsoOverviewEvent.ShowDialog(rh.gs(R.string.ypso_clock_result_title), message))
        }
    }

    override fun onCleared() {
        disposable.clear()
        scope.cancel()
        super.onCleared()
    }

    companion object {

        private const val PLACEHOLDER = "---"
        private const val PROFILE_A = "A"
        private const val PROFILE_B = "B"

        /**
         * Skew below which this screen calls the two clocks agreed.
         *
         * Deliberately the same figure as `YpsoPumpManagerImpl.CLOCK_MATCH_TOLERANCE_MS`, which
         * is what decides whether a clock write is reported as confirmed. They cannot share a
         * constant across the module boundary, so keep them in step by hand: if this one is
         * looser, the row can read "in sync" for a write the driver called a failure.
         */
        private const val CLOCK_IN_SYNC_MS = 90_000L
    }
}
