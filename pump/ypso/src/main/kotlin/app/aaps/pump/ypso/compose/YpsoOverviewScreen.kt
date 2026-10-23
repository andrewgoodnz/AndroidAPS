package app.aaps.pump.ypso.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.ui.compose.pump.PumpOverviewScreen

@Composable
fun YpsoOverviewScreen(
    viewModel: YpsoOverviewViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    PumpOverviewScreen(
        state = uiState,
        modifier = modifier
    )
}
