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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    private var connectedAddress: String? = null
    private var connectedDeviceName: String? = null
    private var isScanning = false
    private var isConnecting = false
    private var useLegacyScan = false
    private var pendingServiceDiscovery = false
    private var bondReceiverRegistered = false

    private val mergedAdvBytes = mutableMapOf<String, ByteArray>()
    private val handler = Handler(Looper.getMainLooper())

    private val _uiState = MutableStateFlow(BleUiState())
    val uiState: StateFlow<BleUiState> = _uiState.asStateFlow()

    private val bondStateReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return

            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                ?: return
            if (device.address != connectedAddress) return

            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> {
                    handler.removeCallbacks(bondTimeoutRunnable)
                    if (pendingServiceDiscovery) {
                        pendingServiceDiscovery = false
                        gatt?.discoverServices()
                    }
                }

                BluetoothDevice.BOND_BONDING -> {
                    _uiState.update { it.copy(statusText = STATUS_PAIRING) }
                    scheduleBondTimeout()
                }

                BluetoothDevice.BOND_NONE -> Unit
            }
        }
    }

    init {
        registerBondReceiver()
    }

    private val scanTimeoutRunnable = Runnable {
        if (!isScanning) return@Runnable
        stopScan()
        if (!useLegacyScan) {
            useLegacyScan = true
            startScan()
        } else {
            showError(STATUS_DEVICE_NOT_FOUND)
        }
    }

    private val bondTimeoutRunnable = Runnable {
        if (!pendingServiceDiscovery) return@Runnable
        pendingServiceDiscovery = false
        gatt?.let { startServiceDiscovery(it) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::handleScanResult)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "scan failed: $errorCode")
            isScanning = false
            if (!useLegacyScan) {
                useLegacyScan = true
                startScan()
            } else {
                showError(STATUS_DEVICE_NOT_FOUND)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedAddress = gatt.device.address
                    ensureBondedThenDiscover(gatt)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    closeGatt()
                    isConnecting = false
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
                showError(STATUS_CONNECTION_ERROR)
                return
            }

            val characteristic = findCharacteristic(gatt, LED_CHARACTERISTIC_UUID)
            if (characteristic == null) {
                showError(STATUS_CHARACTERISTIC_NOT_FOUND)
                return
            }

            ledCharacteristic = characteristic
            connectedAddress = gatt.device.address
            _uiState.update {
                it.copy(
                    statusText = STATUS_CONNECTED,
                    deviceName = connectedDeviceName ?: gatt.device.name,
                )
            }
            readLedState(gatt)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            if (characteristic.uuid == LED_CHARACTERISTIC_UUID) {
                updateLedState(value.firstOrNull())
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid != LED_CHARACTERISTIC_UUID) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                readLedState(gatt)
            } else {
                showError(STATUS_CONNECTION_ERROR)
            }
        }
    }

    fun startConnection() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            showError(STATUS_BLUETOOTH_REQUIRED)
            return
        }

        isConnecting = false
        useLegacyScan = false
        mergedAdvBytes.clear()
        handler.removeCallbacks(bondTimeoutRunnable)

        _uiState.update {
            it.copy(
                statusText = STATUS_SEARCHING,
                deviceName = null,
                ledOn = null,
                isToggleEnabled = false,
                showRetryButton = false,
            )
        }
        startScan()
    }

    fun showPermissionsDenied() {
        stopScan()
        showError(STATUS_PERMISSIONS_DENIED)
    }

    fun showBluetoothRequired() {
        stopScan()
        showError(STATUS_BLUETOOTH_REQUIRED)
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

        val newValue = byteArrayOf(if (currentlyOn) LED_ASCII_OFF else LED_ASCII_ON)
        val result = currentGatt.writeCharacteristic(
            characteristic,
            newValue,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )
        if (result != BluetoothStatusCodes.SUCCESS) {
            Log.e(TAG, "write failed: $result")
        }
    }

    fun disconnect() {
        stopScan()
        pendingServiceDiscovery = false
        handler.removeCallbacks(bondTimeoutRunnable)
        closeGatt()
        isConnecting = false
        _uiState.update {
            it.copy(
                statusText = STATUS_DISCONNECTED,
                deviceName = null,
                ledOn = null,
                isToggleEnabled = false,
                showRetryButton = true,
            )
        }
    }

    fun release() {
        disconnect()
        unregisterBondReceiver()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (isScanning) return

        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            showError(STATUS_CONNECTION_ERROR)
            return
        }

        isScanning = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setLegacy(useLegacyScan)
            .build()

        scanner.startScan(null, settings, scanCallback)
        handler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!isScanning) return
        handler.removeCallbacks(scanTimeoutRunnable)
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        isScanning = false
    }

    private fun handleScanResult(result: ScanResult) {
        val address = result.device.address
        val mergedBytes = mergeAdvertisementBytes(address, result.scanRecord?.bytes)
        val deviceName = resolveDeviceName(result, mergedBytes)

        if (!isTargetDevice(deviceName, mergedBytes, result)) return
        if (isConnecting) return

        isConnecting = true
        stopScan()
        connectedDeviceName = deviceName
        connect(result.device)
    }

    private fun mergeAdvertisementBytes(address: String, newBytes: ByteArray?): ByteArray? {
        if (newBytes == null || newBytes.isEmpty()) return mergedAdvBytes[address]

        val existing = mergedAdvBytes[address]
        if (existing == null) {
            return newBytes.copyOf().also { mergedAdvBytes[address] = it }
        }
        if (newBytes.contentEquals(existing)) return existing

        val chunks = linkedSetOf<String>()
        fun collect(bytes: ByteArray) {
            var index = 0
            while (index < bytes.size) {
                val length = bytes[index].toInt() and 0xFF
                if (length == 0) break
                if (index + length >= bytes.size) break
                val chunk = bytes.copyOfRange(index, index + length + 1)
                chunks.add(chunk.joinToString("") { byte -> "%02x".format(byte) })
                index += length + 1
            }
        }

        collect(existing)
        collect(newBytes)
        return chunks
            .flatMap { hex -> hex.chunked(2).map { it.toInt(16).toByte() } }
            .toByteArray()
            .also { mergedAdvBytes[address] = it }
    }

    private fun resolveDeviceName(result: ScanResult, mergedBytes: ByteArray?): String? {
        result.device.name?.let { return it }
        result.scanRecord?.deviceName?.let { return it }
        return parseAdvertisedName(mergedBytes ?: result.scanRecord?.bytes)
    }

    private fun parseAdvertisedName(bytes: ByteArray?): String? {
        val data = bytes ?: return null
        var index = 0
        while (index < data.size) {
            val length = data[index].toInt() and 0xFF
            if (length == 0) break
            if (index + length >= data.size) break

            val type = data[index + 1].toInt() and 0xFF
            if (type == AD_TYPE_SHORT_LOCAL_NAME || type == AD_TYPE_COMPLETE_LOCAL_NAME) {
                return String(data, index + 2, length - 1, Charsets.UTF_8)
            }
            index += length + 1
        }
        return null
    }

    private fun isTargetDevice(
        deviceName: String?,
        mergedBytes: ByteArray?,
        result: ScanResult,
    ): Boolean {
        if (deviceName != null && isBumbleName(deviceName)) return true

        val rawBytes = mergedBytes ?: result.scanRecord?.bytes ?: return false
        return rawBytes.containsAscii(BUMBLE_MARKER)
    }

    private fun isBumbleName(name: String): Boolean {
        val normalized = name.replace(" ", "").replace("-", "").uppercase()
        return normalized.startsWith("BUMBLE") && normalized.contains("FFFFFFFF")
    }

    private fun ByteArray.containsAscii(text: String): Boolean {
        val pattern = text.encodeToByteArray()
        if (pattern.isEmpty() || size < pattern.size) return false
        for (start in 0..size - pattern.size) {
            if (pattern.indices.all { index -> this[start + index] == pattern[index] }) {
                return true
            }
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun ensureBondedThenDiscover(gatt: BluetoothGatt) {
        when (gatt.device.bondState) {
            BluetoothDevice.BOND_BONDED -> startServiceDiscovery(gatt)

            BluetoothDevice.BOND_BONDING -> {
                pendingServiceDiscovery = true
                _uiState.update { it.copy(statusText = STATUS_PAIRING) }
                scheduleBondTimeout()
            }

            BluetoothDevice.BOND_NONE -> {
                pendingServiceDiscovery = true
                _uiState.update { it.copy(statusText = STATUS_PAIRING) }
                scheduleBondTimeout()
                if (!gatt.device.createBond()) {
                    pendingServiceDiscovery = false
                    handler.removeCallbacks(bondTimeoutRunnable)
                    startServiceDiscovery(gatt)
                }
            }
        }
    }

    private fun scheduleBondTimeout() {
        handler.removeCallbacks(bondTimeoutRunnable)
        handler.postDelayed(bondTimeoutRunnable, BOND_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun startServiceDiscovery(gatt: BluetoothGatt) {
        handler.removeCallbacks(bondTimeoutRunnable)
        pendingServiceDiscovery = false
        _uiState.update { it.copy(statusText = STATUS_CONNECTING) }
        gatt.discoverServices()
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        _uiState.update { it.copy(statusText = STATUS_CONNECTING) }
        closeGatt()
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun readLedState(gatt: BluetoothGatt) {
        val characteristic = ledCharacteristic ?: return
        gatt.readCharacteristic(characteristic)
    }

    private fun findCharacteristic(
        gatt: BluetoothGatt,
        uuid: UUID,
    ): BluetoothGattCharacteristic? {
        return gatt.services
            .flatMap { it.characteristics }
            .firstOrNull { it.uuid == uuid }
    }

    private fun updateLedState(value: Byte?) {
        val ledOn = when (value) {
            LED_ASCII_OFF, 0.toByte() -> false
            LED_ASCII_ON, 1.toByte() -> true
            else -> return
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
        isConnecting = false
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
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        ledCharacteristic = null
        connectedAddress = null
        connectedDeviceName = null
    }

    private fun registerBondReceiver() {
        if (bondReceiverRegistered) return
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(bondStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(bondStateReceiver, filter)
        }
        bondReceiverRegistered = true
    }

    private fun unregisterBondReceiver() {
        if (!bondReceiverRegistered) return
        appContext.unregisterReceiver(bondStateReceiver)
        bondReceiverRegistered = false
    }

    companion object {
        private const val TAG = "BleClient"
        private const val BUMBLE_MARKER = "BUMBLE"

        private val LED_CHARACTERISTIC_UUID: UUID =
            UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")

        private const val LED_ASCII_OFF: Byte = '0'.code.toByte()
        private const val LED_ASCII_ON: Byte = '1'.code.toByte()

        private const val AD_TYPE_SHORT_LOCAL_NAME = 0x08
        private const val AD_TYPE_COMPLETE_LOCAL_NAME = 0x09

        const val STATUS_INITIALIZING = "Инициализация..."
        const val STATUS_SEARCHING = "Поиск устройства..."
        const val STATUS_CONNECTING = "Подключение..."
        const val STATUS_PAIRING = "Сопряжение..."
        const val STATUS_CONNECTED = "Подключено"
        const val STATUS_LED_ON = "Светодиод включён"
        const val STATUS_LED_OFF = "Светодиод выключен"
        const val STATUS_CONNECTION_ERROR = "Ошибка подключения"
        const val STATUS_CHARACTERISTIC_NOT_FOUND = "Characteristic не найдена"
        const val STATUS_DEVICE_NOT_FOUND = "Устройство не найдено"
        const val STATUS_PERMISSIONS_DENIED = "Нет разрешений Bluetooth"
        const val STATUS_BLUETOOTH_REQUIRED = "Включите Bluetooth"
        const val STATUS_DISCONNECTED = "Отключено"

        private const val SCAN_TIMEOUT_MS = 30_000L
        private const val BOND_TIMEOUT_MS = 8_000L
    }
}

data class BleUiState(
    val statusText: String = BleClient.STATUS_INITIALIZING,
    val deviceName: String? = null,
    val ledOn: Boolean? = null,
    val isToggleEnabled: Boolean = false,
    val showRetryButton: Boolean = false,
)
