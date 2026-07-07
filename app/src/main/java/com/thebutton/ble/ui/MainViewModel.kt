package com.thebutton.ble.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.thebutton.ble.ble.BleClient
import com.thebutton.ble.ble.BleUiState
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val bleClient = BleClient(application)

    val uiState: StateFlow<BleUiState> = bleClient.uiState

    fun startConnection() {
        bleClient.startConnection()
    }

    fun onPermissionsDenied() {
        bleClient.showPermissionsDenied()
    }

    fun onBluetoothDisabled() {
        bleClient.showBluetoothRequired()
    }

    fun retryConnection() {
        bleClient.retryConnection()
    }

    fun toggleLed() {
        bleClient.toggleLed()
    }

    override fun onCleared() {
        bleClient.release()
        super.onCleared()
    }
}
