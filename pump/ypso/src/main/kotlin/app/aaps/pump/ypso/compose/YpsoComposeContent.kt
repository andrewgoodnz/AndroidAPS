package app.aaps.pump.ypso.compose

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.protection.ProtectionResult
import app.aaps.core.interfaces.pump.BlePreCheck
import app.aaps.core.ui.compose.ComposablePluginContent
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.core.ui.compose.dialogs.OkCancelDialog
import app.aaps.core.ui.compose.dialogs.OkDialog
import app.aaps.core.ui.compose.metroViewModel
import app.aaps.core.ui.compose.pump.BlePreCheckHost
import app.aaps.pump.ypso.R
import app.aaps.core.ui.R as CoreUiR

/**
 * The YPSO tab. Replaces the old `YpsoOverviewFragment`, which 4.0.0 has no place for: the XML
 * fragment path is gone and a plugin now supplies Compose content instead.
 */
class YpsoComposeContent(
    private val pluginName: String,
    private val protectionCheck: ProtectionCheck,
    private val blePreCheck: BlePreCheck
) : ComposablePluginContent {

    @Composable
    override fun Render(
        setToolbarConfig: (ToolbarConfig) -> Unit,
        onNavigateBack: () -> Unit,
        onSettings: (() -> Unit)?
    ) {
        val viewModel: YpsoOverviewViewModel = metroViewModel()
        val pairViewModel: YpsoPairWizardViewModel = metroViewModel()

        var showPairWizard by remember { mutableStateOf(false) }
        var showConfirmSetClock by remember { mutableStateOf(false) }
        var dialogTitle by remember { mutableStateOf("") }
        var dialogMessage by remember { mutableStateOf("") }
        var showDialog by remember { mutableStateOf(false) }

        val navIcon: @Composable () -> Unit = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(CoreUiR.string.back))
            }
        }
        val settingsAction: @Composable RowScope.() -> Unit = {
            onSettings?.let { action ->
                IconButton(onClick = action) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(CoreUiR.string.settings))
                }
            }
        }

        LaunchedEffect(Unit) {
            setToolbarConfig(
                ToolbarConfig(
                    title = pluginName,
                    navigationIcon = navIcon,
                    actions = settingsAction
                )
            )
        }

        LaunchedEffect(viewModel) {
            viewModel.events.collect { event ->
                when (event) {
                    is YpsoOverviewEvent.ConfirmSetClock -> showConfirmSetClock = true

                    // Pairing bonds to a pump, so it sits behind the same protection as any other
                    // device management action.
                    is YpsoOverviewEvent.StartPairing    ->
                        if (!showPairWizard) {
                            protectionCheck.requestProtection(ProtectionCheck.Protection.PREFERENCES) { result ->
                                if (result == ProtectionResult.GRANTED) showPairWizard = true
                            }
                        }

                    is YpsoOverviewEvent.ShowDialog      -> {
                        dialogTitle = event.title
                        dialogMessage = event.message
                        showDialog = true
                    }
                }
            }
        }

        if (showConfirmSetClock) {
            OkCancelDialog(
                title = stringResource(R.string.ypso_set_clock_confirm_title),
                message = stringResource(R.string.ypso_set_clock_confirm_message),
                onConfirm = {
                    showConfirmSetClock = false
                    viewModel.performSetClock()
                },
                onDismiss = { showConfirmSetClock = false }
            )
        }

        if (showDialog) {
            OkDialog(
                title = dialogTitle,
                message = dialogMessage,
                onDismiss = { showDialog = false }
            )
        }

        if (showPairWizard) {
            // Scanning needs Bluetooth and the location/scan permissions, so the wizard only opens
            // once the pre-check has passed; a failure closes it rather than showing an empty list.
            var bleReady by remember { mutableStateOf(false) }
            BlePreCheckHost(
                blePreCheck = blePreCheck,
                onReady = { bleReady = true },
                onFailed = { showPairWizard = false }
            )

            if (bleReady) {
                LaunchedEffect(pairViewModel) {
                    pairViewModel.events.collect { event ->
                        when (event) {
                            is YpsoPairEvent.Finish -> showPairWizard = false
                        }
                    }
                }
                YpsoPairWizardScreen(
                    viewModel = pairViewModel,
                    onCancel = {
                        pairViewModel.stopScan()
                        showPairWizard = false
                    }
                )
            }
        } else {
            YpsoOverviewScreen(viewModel = viewModel)
        }
    }
}
