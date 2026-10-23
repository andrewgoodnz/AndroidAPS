package app.aaps.pump.ypso.compose

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.ble.BondState
import app.aaps.core.interfaces.pump.ble.ScannedDevice
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.ypso.R
import app.aaps.pump.ypso.ble.YpsoPairingScanner
import app.aaps.pump.ypso.events.EventYpsoPumpDeviceChanged
import app.aaps.pump.ypso.keys.YpsoIntKey
import app.aaps.pump.ypso.keys.YpsoStringNonKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class YpsoPairStep {

    /** Choosing a pump from the scan list. */
    SCAN,

    /** Naming the pump back to the user before anything touches it. */
    CONFIRM,

    /** Android and the pump are bonding; waiting for the bond to exist. */
    BONDING,

    ERROR
}

data class YpsoPairUiState(
    val step: YpsoPairStep = YpsoPairStep.SCAN,
    val devices: List<ScannedDevice> = emptyList(),
    val selected: ScannedDevice? = null,
    val errorMessage: String? = null
)

sealed class YpsoPairEvent {

    data object Finish : YpsoPairEvent()
}

/**
 * Choosing and bonding a pump.
 *
 * Replaces `YpsoBLEScanActivity`, which 4.0.0 has no place for. Three properties of that screen are
 * load bearing and are kept here:
 *
 * 1. ⚠ **The pump is named back to the user before anything touches it.** More than one YpsoPump can
 *    be in range and one of them may be delivering insulin under another app. Bonding to the wrong
 *    one is not a mistake the user can see afterwards.
 * 2. ⚠ **Nothing is written until the bond exists.** Storing the MAC earlier makes the plugin look
 *    paired, and the command queue immediately starts trying to connect to a pump that is still
 *    mid-bond - on the first hardware run that produced a connect attempt every second for the
 *    whole bonding window.
 * 3. **The bond is polled, not listened for.** `ACTION_BOND_STATE_CHANGED` depends on getting
 *    receiver-export semantics right across API levels, and when it silently does not fire there is
 *    nothing to see: the bond completes, the pump says "paired", and the screen waits for ever.
 *    That is what happened on the first hardware run. Polling reads the same state and cannot miss
 *    it.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Stable
@Inject
class YpsoPairWizardViewModel(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val preferences: Preferences,
    private val rxBus: RxBus,
    private val scanner: YpsoPairingScanner
) : ViewModel() {

    private val step = MutableStateFlow(YpsoPairStep.SCAN)
    private val selected = MutableStateFlow<ScannedDevice?>(null)
    private val error = MutableStateFlow<String?>(null)

    private var bondPoll: Job? = null

    private val _events = MutableSharedFlow<YpsoPairEvent>(extraBufferCapacity = 5)
    val events: SharedFlow<YpsoPairEvent> = _events

    private val _uiState = MutableStateFlow(YpsoPairUiState())
    val uiState: StateFlow<YpsoPairUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(step, selected, error, scanner.devices) { s, sel, err, devices ->
                YpsoPairUiState(step = s, devices = devices, selected = sel, errorMessage = err)
            }.collect { state -> _uiState.value = state }
        }
    }

    fun startScan() {
        error.value = null
        step.value = YpsoPairStep.SCAN
        scanner.startScan(preferences.get(YpsoIntKey.YpsoRssiThreshold))
    }

    fun stopScan() = scanner.stopScan()

    /** Does not bond yet - the user has to name the pump first. See the class comment. */
    fun onDeviceSelected(device: ScannedDevice) {
        selected.value = device
        step.value = YpsoPairStep.CONFIRM
    }

    fun onConfirmCancelled() {
        selected.value = null
        step.value = YpsoPairStep.SCAN
    }

    /**
     * The user has named the pump. Stop scanning, then bond.
     *
     * Bonding needs the user: Android raises a pairing dialog and the pump shows the code to match.
     */
    fun onPairConfirmed() {
        val device = selected.value ?: return
        scanner.stopScan()

        if (scanner.bondState(device.address) == BondState.BONDED) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "${device.address} already bonded")
            commitPairing(device)
            return
        }

        step.value = YpsoPairStep.BONDING
        if (!scanner.createBond(device.address)) {
            aapsLogger.error(LTag.PUMPBTCOMM, "createBond() refused for ${device.address}")
            fail(rh.gs(R.string.ypso_bonding_failed))
            return
        }
        awaitBond(device)
    }

    private fun awaitBond(device: ScannedDevice) {
        bondPoll?.cancel()
        bondPoll = viewModelScope.launch {
            var waited = 0L
            while (waited < BOND_TIMEOUT_MS) {
                when (scanner.bondState(device.address)) {
                    BondState.BONDED  -> {
                        aapsLogger.debug(LTag.PUMPBTCOMM, "Bonded to ${device.address}")
                        commitPairing(device)
                        return@launch
                    }

                    BondState.NONE    -> Unit // still waiting, or the user has not answered yet
                    BondState.BONDING -> Unit
                }
                delay(BOND_POLL_MS)
                waited += BOND_POLL_MS
            }
            aapsLogger.error(LTag.PUMPBTCOMM, "Bonding ${device.address} timed out after ${BOND_TIMEOUT_MS}ms")
            fail(rh.gs(R.string.ypso_bonding_failed))
        }
    }

    fun onBondCancelled() {
        bondPoll?.cancel()
        bondPoll = null
        selected.value = null
        startScan()
    }

    private fun fail(message: String) {
        bondPoll?.cancel()
        bondPoll = null
        error.value = message
        step.value = YpsoPairStep.ERROR
    }

    /**
     * Records the chosen pump and tells the driver about it. Reached only once the bond exists.
     *
     * The serial is taken from the advertised name, which is how the rest of the driver learns it
     * before the first connection - `YpsoPump_10074292` means serial `10074292`.
     */
    private fun commitPairing(device: ScannedDevice) {
        bondPoll?.cancel()
        bondPoll = null

        preferences.put(YpsoStringNonKey.MacAddress, device.address)
        preferences.put(YpsoStringNonKey.DeviceName, device.name)
        preferences.put(
            YpsoStringNonKey.SerialNumber,
            device.name.removePrefix(YpsoPairingScanner.YPSOPUMP_NAME_PREFIX)
        )
        aapsLogger.debug(LTag.PUMPBTCOMM, "Paired to ${device.name} (${device.address})")

        // The service drops any current session and re-reads the paired device on this.
        rxBus.send(EventYpsoPumpDeviceChanged())
        viewModelScope.launch { _events.emit(YpsoPairEvent.Finish) }
    }

    override fun onCleared() {
        bondPoll?.cancel()
        scanner.stopScan()
        super.onCleared()
    }

    companion object {

        /** How often to re-read the bond state while the bonding dialog is up. */
        private const val BOND_POLL_MS = 300L

        /** Bonding needs the user to confirm on both the phone and the pump, so allow time. */
        private const val BOND_TIMEOUT_MS = 120_000L
    }
}
