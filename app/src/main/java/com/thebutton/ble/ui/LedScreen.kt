package com.thebutton.ble.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thebutton.ble.ble.BleUiState

@Composable
fun LedScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LedScreenContent(
        uiState = uiState,
        onToggleLed = viewModel::toggleLed,
        onRetryConnection = viewModel::retryConnection,
        modifier = modifier,
    )
}

@Composable
fun LedScreenContent(
    uiState: BleUiState,
    onToggleLed: () -> Unit,
    onRetryConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Статус: ${uiState.statusText}",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = ledStateText(uiState.ledOn),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onToggleLed,
                enabled = uiState.isToggleEnabled,
            ) {
                Text(text = toggleButtonText(uiState.ledOn))
            }

            if (uiState.showRetryButton) {
                Spacer(modifier = Modifier.height(16.dp))

                Button(onClick = onRetryConnection) {
                    Text(text = "Повторить подключение")
                }
            }
        }
    }
}

private fun ledStateText(ledOn: Boolean?): String = when (ledOn) {
    true -> "Светодиод: включён"
    false -> "Светодиод: выключен"
    null -> "Светодиод: неизвестно"
}

private fun toggleButtonText(ledOn: Boolean?): String = when (ledOn) {
    true -> "Выключить"
    false -> "Включить"
    null -> "Включить"
}
