package app.aaps.pump.ypso.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.ui.compose.dialogs.OkCancelDialog
import app.aaps.core.ui.compose.dialogs.OkDialog
import app.aaps.core.ui.compose.pump.BleScanStep
import app.aaps.core.ui.compose.pump.KeepScreenOnEffect
import app.aaps.pump.ypso.R

/**
 * Pairing, on the shared scan step.
 *
 * The confirmation in the middle is not a formality - see [YpsoPairWizardViewModel].
 */
@Composable
fun YpsoPairWizardScreen(
    viewModel: YpsoPairWizardViewModel,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    when (state.step) {
        YpsoPairStep.SCAN    ->
            BleScanStep(
                devices = state.devices,
                onSelectDevice = viewModel::onDeviceSelected,
                onStartScan = viewModel::startScan,
                onStopScan = viewModel::stopScan,
                onCancel = onCancel,
                title = stringResource(R.string.ypso_pairing_title)
            )

        YpsoPairStep.CONFIRM -> {
            // Still drawing the list underneath, so cancelling returns to it.
            BleScanStep(
                devices = state.devices,
                onSelectDevice = viewModel::onDeviceSelected,
                onStartScan = viewModel::startScan,
                onStopScan = viewModel::stopScan,
                onCancel = onCancel,
                title = stringResource(R.string.ypso_pairing_title)
            )
            state.selected?.let { device ->
                OkCancelDialog(
                    title = stringResource(R.string.ypso_confirm_pair_title),
                    message = stringResource(R.string.ypso_confirm_pair_message, device.name, device.address),
                    onConfirm = viewModel::onPairConfirmed,
                    onDismiss = viewModel::onConfirmCancelled
                )
            }
        }

        YpsoPairStep.BONDING -> {
            KeepScreenOnEffect()
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(R.string.ypso_bonding_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 24.dp)
                )
                Text(
                    text = stringResource(R.string.ypso_bonding_message),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        YpsoPairStep.ERROR   ->
            OkDialog(
                title = stringResource(R.string.ypso_pairing_title),
                message = state.errorMessage ?: stringResource(R.string.ypso_bonding_failed),
                onDismiss = viewModel::onBondCancelled
            )
    }
}
