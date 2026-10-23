package app.aaps.pump.ypso.compose

import app.aaps.core.interfaces.pump.ble.BondState
import app.aaps.core.interfaces.pump.ble.ScannedDevice
import app.aaps.pump.ypso.ble.YpsoPairingScanner
import app.aaps.pump.ypso.keys.YpsoIntKey
import app.aaps.pump.ypso.keys.YpsoStringNonKey
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The pairing screen's safety properties.
 *
 * `YpsoBLEScanActivity` could not be tested — it was an Activity. The logic is in a ViewModel now,
 * and the two properties worth pinning are the ones that cost a hardware run each:
 *
 * - **Nothing is recorded until the bond exists.** Writing the MAC earlier makes the plugin look
 *   paired, and AAPS's command queue starts connecting to a pump that is still mid-bond.
 * - **Choosing a pump in the list is not the same as pairing with it.** Two pumps answer to
 *   `YpsoPump_*` and one of them may be delivering insulin under another app, so the tap only
 *   opens a confirmation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class YpsoPairWizardViewModelTest : TestBaseWithProfile() {

    @Mock lateinit var scanner: YpsoPairingScanner

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: YpsoPairWizardViewModel

    private val research = ScannedDevice(name = "YpsoPump_10074292", address = "EC:2A:F0:01:22:34", rssi = -60)

    @BeforeEach
    fun prepare() {
        Dispatchers.setMain(dispatcher)
        whenever(scanner.devices).thenReturn(MutableStateFlow(listOf(research)))
        whenever(preferences.get(YpsoIntKey.YpsoRssiThreshold)).thenReturn(-99)
        viewModel = YpsoPairWizardViewModel(aapsLogger, rh, preferences, rxBus, scanner)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `choosing a pump only asks for confirmation and records nothing`() = runTest(dispatcher) {
        viewModel.onDeviceSelected(research)

        assertThat(viewModel.uiState.value.step).isEqualTo(YpsoPairStep.CONFIRM)
        assertThat(viewModel.uiState.value.selected).isEqualTo(research)
        verify(preferences, never()).put(YpsoStringNonKey.MacAddress, research.address)
        verify(scanner, never()).createBond(research.address)
    }

    @Test
    fun `an already bonded pump is recorded without bonding again`() = runTest(dispatcher) {
        whenever(scanner.bondState(research.address)).thenReturn(BondState.BONDED)

        viewModel.onDeviceSelected(research)
        viewModel.onPairConfirmed()

        verify(scanner, never()).createBond(research.address)
        verify(preferences).put(YpsoStringNonKey.MacAddress, research.address)
        verify(preferences).put(YpsoStringNonKey.DeviceName, research.name)
        // The serial comes off the advertised name - YpsoPump_10074292 means serial 10074292.
        verify(preferences).put(YpsoStringNonKey.SerialNumber, "10074292")
    }

    @Test
    fun `a pump that is still bonding is not recorded until the bond exists`() = runTest(dispatcher) {
        whenever(scanner.bondState(research.address)).thenReturn(BondState.NONE)
        whenever(scanner.createBond(research.address)).thenReturn(true)

        viewModel.onDeviceSelected(research)
        viewModel.onPairConfirmed()

        // ⚠ The whole point. The bond does not exist yet, so the pump must not be on record.
        assertThat(viewModel.uiState.value.step).isEqualTo(YpsoPairStep.BONDING)
        verify(preferences, never()).put(YpsoStringNonKey.MacAddress, research.address)

        // A bounded window rather than advanceUntilIdle: the poll re-arms itself until it either
        // sees the bond or times out, and idling it would run the whole 120 s ceiling.
        advanceTimeBy(1_000)
        verify(preferences, never()).put(YpsoStringNonKey.MacAddress, research.address)

        // The bond lands, and the next poll is what records it.
        whenever(scanner.bondState(research.address)).thenReturn(BondState.BONDED)
        advanceTimeBy(1_000)
        verify(preferences).put(YpsoStringNonKey.MacAddress, research.address)
    }

    @Test
    fun `a refused bond request reports an error and records nothing`() = runTest(dispatcher) {
        whenever(scanner.bondState(research.address)).thenReturn(BondState.NONE)
        whenever(scanner.createBond(research.address)).thenReturn(false)

        viewModel.onDeviceSelected(research)
        viewModel.onPairConfirmed()

        assertThat(viewModel.uiState.value.step).isEqualTo(YpsoPairStep.ERROR)
        verify(preferences, never()).put(YpsoStringNonKey.MacAddress, research.address)
    }

    @Test
    fun `scanning stops before bonding starts`() = runTest(dispatcher) {
        // Scanning and bonding at once is what the activity avoided; the pump is busy enough.
        whenever(scanner.bondState(research.address)).thenReturn(BondState.BONDED)

        viewModel.onDeviceSelected(research)
        viewModel.onPairConfirmed()

        verify(scanner).stopScan()
    }

    @Test
    fun `the scan uses the configured RSSI floor`() = runTest(dispatcher) {
        whenever(preferences.get(YpsoIntKey.YpsoRssiThreshold)).thenReturn(-70)

        viewModel.startScan()

        verify(scanner).startScan(-70)
    }
}
