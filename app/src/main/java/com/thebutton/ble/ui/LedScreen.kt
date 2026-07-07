package com.thebutton.ble.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
                .padding(horizontal = 32.dp, vertical = 24.dp)
                .widthIn(max = 400.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            InfoRow(label = "Статус", value = uiState.statusText)

            Spacer(modifier = Modifier.height(20.dp))

            InfoRow(label = "Светодиод", value = ledStateText(uiState.ledOn))

            uiState.deviceName?.let { name ->
                Spacer(modifier = Modifier.height(12.dp))
                InfoRow(label = "Устройство", value = name)
            }

            Spacer(modifier = Modifier.height(36.dp))

            Button(
                onClick = onToggleLed,
                enabled = uiState.isToggleEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = toggleButtonText(uiState.ledOn))
            }

            if (uiState.showRetryButton) {
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onRetryConnection,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "Повторить подключение")
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}

private fun ledStateText(ledOn: Boolean?): String = when (ledOn) {
    true -> "Включён"
    false -> "Выключен"
    null -> "Неизвестно"
}

private fun toggleButtonText(ledOn: Boolean?): String = when (ledOn) {
    true -> "Выключить"
    false -> "Включить"
    null -> "Включить"
}
