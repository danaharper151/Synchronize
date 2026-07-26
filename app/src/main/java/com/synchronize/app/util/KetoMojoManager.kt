package com.synchronize.app.util

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.synchronize.app.data.db.entity.KetoneReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import android.annotation.SuppressLint

class KetoMojoManager(private val context: Context) {

    private val TAG = "KetoMojoManager"

    // ── Keto-Mojo BLE UUIDs ───────────────────────────────────
    // GK+ uses standard Bluetooth glucose service profile
    companion object {
        val GLUCOSE_SERVICE_UUID: UUID =
            UUID.fromString("00001808-0000-1000-8000-00805f9b34fb")
        val GLUCOSE_MEASUREMENT_UUID: UUID =
            UUID.fromString("00002a18-0000-1000-8000-00805f9b34fb")
        val GLUCOSE_FEATURE_UUID: UUID =
            UUID.fromString("00002a51-0000-1000-8000-00805f9b34fb")
        val CLIENT_CHARACTERISTIC_CONFIG: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val RECORD_ACCESS_CONTROL_UUID: UUID =
            UUID.fromString("00002a52-0000-1000-8000-00805f9b34fb")

        // Keto-Mojo device name prefix
        const val DEVICE_NAME_PREFIX = "GK+1633"
    }

    // ── State ─────────────────────────────────────────────────

    sealed class BleState {
        object Idle : BleState()
        object Scanning : BleState()
        data class DeviceFound(val device: BluetoothDevice) : BleState()
        object Connecting : BleState()
        object Connected : BleState()
        data class ReadingReceived(val reading: KetoneReading) : BleState()
        data class Error(val message: String) : BleState()
        object Disconnected : BleState()
    }

    private val _state = MutableStateFlow<BleState>(BleState.Idle)
    val state: StateFlow<BleState> = _state

    private val _latestReading = MutableStateFlow<KetoneReading?>(null)
    val latestReading: StateFlow<KetoneReading?> = _latestReading

    // ── BLE components ────────────────────────────────────────

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var connectedDevice: BluetoothDevice? = null

    // ── Scan for Keto-Mojo device ─────────────────────────────
    @SuppressLint("MissingPermission")

    fun startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _state.value = BleState.Error("Bluetooth is not enabled")
            return
        }

        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Filter for Keto-Mojo devices by name prefix
        val filters = listOf(
            ScanFilter.Builder()
                .setDeviceName(DEVICE_NAME_PREFIX)
                .build()
        )

        Log.d(TAG, "Starting BLE scan for Keto-Mojo...")
        _state.value = BleState.Scanning

        bluetoothLeScanner?.startScan(filters, settings, scanCallback)
    }

    @SuppressLint("MissingPermission")

    fun stopScan() {
        bluetoothLeScanner?.stopScan(scanCallback)
        if (_state.value == BleState.Scanning) {
            _state.value = BleState.Idle
        }
        Log.d(TAG, "BLE scan stopped")
    }

    // ── Scan callback ─────────────────────────────────────────
    @SuppressLint("MissingPermission")

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            Log.d(TAG, "Device found: ${device.name} — ${device.address}")
            stopScan()
            _state.value = BleState.DeviceFound(device)
            connectToDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            _state.value = BleState.Error("BLE scan failed (code $errorCode)")
        }
    }

    // ── Connect to device ─────────────────────────────────────
    @SuppressLint("MissingPermission")

    fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to ${device.name}...")
        _state.value = BleState.Connecting
        connectedDevice = device
        bluetoothGatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    // ── GATT callback ─────────────────────────────────────────
    @SuppressLint("MissingPermission")

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to Keto-Mojo — discovering services...")
                    _state.value = BleState.Connected
                    gatt.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from Keto-Mojo")
                    _state.value = BleState.Disconnected
                    bluetoothGatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered — enabling notifications...")
                enableGlucoseNotifications(gatt)
            } else {
                Log.e(TAG, "Service discovery failed: $status")
                _state.value = BleState.Error("Service discovery failed")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == GLUCOSE_MEASUREMENT_UUID) {
                Log.d(TAG, "Glucose measurement received: ${value.toHex()}")
                val reading = parseGlucoseMeasurement(value)
                reading?.let {
                    _latestReading.value = it
                    _state.value = BleState.ReadingReceived(it)
                    Log.d(TAG, "Parsed reading: BHB=${it.betaHydroxybutyrate} mmol/L, " +
                            "Glucose=${it.glucoseMgDl} mg/dL")
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d(TAG, "Descriptor write status: $status")
        }
    }

    // ── Enable glucose notifications ──────────────────────────
    @SuppressLint("MissingPermission")

    private fun enableGlucoseNotifications(gatt: BluetoothGatt) {
        val service = gatt.getService(GLUCOSE_SERVICE_UUID)
        if (service == null) {
            Log.e(TAG, "Glucose service not found")
            _state.value = BleState.Error("Glucose service not found on device")
            return
        }

        val characteristic = service.getCharacteristic(GLUCOSE_MEASUREMENT_UUID)
        if (characteristic == null) {
            Log.e(TAG, "Glucose measurement characteristic not found")
            _state.value = BleState.Error("Glucose characteristic not found")
            return
        }

        // Enable notifications on the characteristic
        gatt.setCharacteristicNotification(characteristic, true)

        // Write to the Client Characteristic Configuration Descriptor
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
        descriptor?.let {
            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(it)
            Log.d(TAG, "Notifications enabled for glucose measurement")
        }
    }

    // ── Parse Keto-Mojo glucose measurement packet ────────────
    // Keto-Mojo GK+ sends ketone as glucose channel in mmol/L * 18
    // and blood glucose separately

    private fun parseGlucoseMeasurement(value: ByteArray): KetoneReading? {
        return try {
            if (value.size < 10) return null

            val flags = value[0].toInt() and 0xFF
            val timeOffsetPresent = (flags and 0x01) != 0
            val glucosePresent    = (flags and 0x02) != 0

            // Extract glucose value (bytes 3-4, little endian, SFLOAT)
            val rawGlucose = ((value[4].toInt() and 0xFF) shl 8) or
                    (value[3].toInt() and 0xFF)
            val glucoseMmol = sfloatToDouble(rawGlucose)
            val glucoseMgDl = (glucoseMmol * 18.0).toInt()

            // For Keto-Mojo GK+, ketone is in the second measurement
            // BHB value is typically in bytes 7-8
            val rawKetone = if (value.size >= 9) {
                ((value[8].toInt() and 0xFF) shl 8) or
                        (value[7].toInt() and 0xFF)
            } else rawGlucose

            val bhbMmol = sfloatToDouble(rawKetone)

            val now = System.currentTimeMillis()
            KetoneReading(
                subjectId            = Constants.SUBJECT_ID,
                deviceTimestamp      = now,
                receiveTimestamp     = now,
                betaHydroxybutyrate  = bhbMmol,
                glucoseMgDl          = glucoseMgDl,
                source               = "BLE",
                readingContext        = null,
                notes                = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse glucose measurement", e)
            null
        }
    }

    // ── SFLOAT decoder (IEEE 11073) ───────────────────────────

    private fun sfloatToDouble(raw: Int): Double {
        val mantissa = raw and 0x0FFF
        val exponent = (raw shr 12).toByte().toInt()
        return mantissa * Math.pow(10.0, exponent.toDouble())
    }

    // ── Disconnect ────────────────────────────────────────────
    @SuppressLint("MissingPermission")

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        connectedDevice = null
        _state.value = BleState.Idle
        Log.d(TAG, "Disconnected and cleaned up")
    }

    // ── Cleanup ───────────────────────────────────────────────

    fun destroy() {
        stopScan()
        disconnect()
    }

    // ── Helper ────────────────────────────────────────────────

    private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }

    fun isBluetoothEnabled() = bluetoothAdapter?.isEnabled == true
}