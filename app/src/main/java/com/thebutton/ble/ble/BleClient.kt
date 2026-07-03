package com.thebutton.ble.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

class BleClient(context: Context) {

    private val appContext = context.applicationContext

    private val bluetoothAdapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private var ledCharacteristic: BluetoothGattCharacteristic? = null
    private var isScanning = false
    private var pendingWriteValue: Byte? = null

    private val _uiState = MutableStateFlow(BleUiState())
    val uiState: StateFlow<BleUiState> = _uiState.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.device.name ?: result.scanRecord?.deviceName
            if (deviceName == TARGET_DEVICE_NAME) {
                Log.d(TAG, "device found: $deviceName (${result.device.address})")
                stopScan()
                connect(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "scan failed: errorCode=$errorCode")
            isScanning = false
            showError(STATUS_CONNECTION_ERROR)
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "connected")
                    _uiState.update { it.copy(statusText = STATUS_CONNECTED) }
                    Log.d(TAG, "discovering services")
                    gatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "disconnected (status=$status)")
                    closeGatt()
                    _uiState.update {
                        it.copy(
                            statusText = STATUS_DISCONNECTED,
                            ledOn = null,
                            isToggleEnabled = false,
                            showRetryButton = true,
                        )
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "services discovery failed: status=$status")
                showError(STATUS_CONNECTION_ERROR)
                return
            }

            Log.d(TAG, "services discovered")
            val characteristic = gatt.services
                .flatMap { it.characteristics }
                .firstOrNull { it.uuid == LED_CHARACTERISTIC_UUID }

            if (characteristic == null) {
                Log.e(TAG, "characteristic not found: $LED_CHARACTERISTIC_UUID")
                showError(STATUS_CHARACTERISTIC_NOT_FOUND)
                return
            }

            ledCharacteristic = characteristic
            Log.d(TAG, "characteristic found: ${characteristic.uuid}")
            _uiState.update { it.copy(statusText = STATUS_CHARACTERISTIC_FOUND) }

            if (!gatt.readCharacteristic(characteristic)) {
                Log.e(TAG, "read failed: readCharacteristic returned false")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (characteristic.uuid != LED_CHARACTERISTIC_UUID) return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "read failed: status=$status")
                return
            }

            val byteValue = value.firstOrNull()
            Log.d(TAG, "read value: $byteValue")
            updateLedState(byteValue)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid != LED_CHARACTERISTIC_UUID) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "write success")
                updateLedState(pendingWriteValue)
            } else {
                Log.e(TAG, "write error: status=$status")
            }
            pendingWriteValue = null
        }
    }

    fun startConnection() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth adapter unavailable or disabled")
            showError(STATUS_CONNECTION_ERROR)
            return
        }

        _uiState.update {
            it.copy(
                statusText = STATUS_SEARCHING,
                ledOn = null,
                isToggleEnabled = false,
                showRetryButton = false,
            )
        }
        startScan()
    }

    fun retryConnection() {
        disconnect()
        startConnection()
    }

    @SuppressLint("MissingPermission")
    fun toggleLed() {
        val characteristic = ledCharacteristic ?: return
        val currentGatt = gatt ?: return
        val currentlyOn = _uiState.value.ledOn ?: return

        val newValue = if (currentlyOn) byteArrayOf(0) else byteArrayOf(1)
        Log.d(TAG, "write value: ${newValue.first()}")

        pendingWriteValue = newValue.first()
        val result = currentGatt.writeCharacteristic(
            characteristic,
            newValue,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )
        if (result != BluetoothStatusCodes.SUCCESS) {
            Log.e(TAG, "write error: writeCharacteristic returned $result")
            pendingWriteValue = null
        }
    }

    fun disconnect() {
        stopScan()
        closeGatt()
        _uiState.update {
            it.copy(
                statusText = STATUS_DISCONNECTED,
                ledOn = null,
                isToggleEnabled = false,
                showRetryButton = true,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (isScanning) return

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BLE scanner unavailable")
            showError(STATUS_CONNECTION_ERROR)
            return
        }

        Log.d(TAG, "start scan")
        isScanning = true

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!isScanning) return

        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        isScanning = false
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        Log.d(TAG, "connecting to ${device.address}")
        _uiState.update { it.copy(statusText = STATUS_CONNECTING) }
        closeGatt()
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun updateLedState(value: Byte?) {
        val ledOn = when (value) {
            1.toByte() -> true
            0.toByte() -> false
            else -> {
                Log.w(TAG, "unexpected read value: $value")
                return
            }
        }

        _uiState.update {
            it.copy(
                statusText = if (ledOn) STATUS_LED_ON else STATUS_LED_OFF,
                ledOn = ledOn,
                isToggleEnabled = true,
                showRetryButton = false,
            )
        }
    }

    private fun showError(status: String) {
        _uiState.update {
            it.copy(
                statusText = status,
                isToggleEnabled = false,
                showRetryButton = true,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        gatt?.let { currentGatt ->
            currentGatt.disconnect()
            currentGatt.close()
        }
        gatt = null
        ledCharacteristic = null
    }

    companion object {
        private const val TAG = "BleClient"
        const val TARGET_DEVICE_NAME = "BUMBLE--FFFFFFFF"

        val LED_CHARACTERISTIC_UUID: UUID =
            UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")

        const val STATUS_SEARCHING = "Поиск устройства..."
        const val STATUS_CONNECTING = "Подключение..."
        const val STATUS_CONNECTED = "Подключено"
        const val STATUS_CHARACTERISTIC_FOUND = "Characteristic найдена"
        const val STATUS_LED_ON = "Светодиод включён"
        const val STATUS_LED_OFF = "Светодиод выключен"
        const val STATUS_CONNECTION_ERROR = "Ошибка подключения"
        const val STATUS_CHARACTERISTIC_NOT_FOUND = "Characteristic не найдена"
        const val STATUS_DISCONNECTED = "Отключено"
    }
}

data class BleUiState(
    val statusText: String = BleClient.STATUS_SEARCHING,
    val ledOn: Boolean? = null,
    val isToggleEnabled: Boolean = false,
    val showRetryButton: Boolean = false,
)
