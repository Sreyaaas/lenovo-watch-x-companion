package com.lenovo.watchx.companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.RingtoneManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SleepSession(
    val totalMinutes: Int = 462,   // ~7h 42m
    val deepMinutes: Int = 135,    // ~2h 15m
    val lightMinutes: Int = 290,   // ~4h 50m
    val awakeMinutes: Int = 37,    // ~37m
    val score: Int = 86
)

enum class BleConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED
}

class LenovoWatchBleService : Service() {

    companion object {
        const val TAG = "WatchXBleService"
        const val CHANNEL_ID = "watchx_companion_channel"
        const val NOTIFICATION_ID = 2001
        const val PREFS_NAME = "watchx_prefs"
        const val KEY_LAST_MAC = "last_connected_mac"

        var instance: LenovoWatchBleService? = null
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    private var seq = 1

    // State flows
    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _batteryLevel = MutableStateFlow(85)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _firmwareVersion = MutableStateFlow("1.2.8")
    val firmwareVersion: StateFlow<String> = _firmwareVersion.asStateFlow()

    private val _todaySteps = MutableStateFlow(6420)
    val todaySteps: StateFlow<Int> = _todaySteps.asStateFlow()

    private val _stepGoal = MutableStateFlow(10000)
    val stepGoal: StateFlow<Int> = _stepGoal.asStateFlow()

    private val _heartRateBpm = MutableStateFlow(72)
    val heartRateBpm: StateFlow<Int> = _heartRateBpm.asStateFlow()

    private val _isMeasuringHr = MutableStateFlow(false)
    val isMeasuringHr: StateFlow<Boolean> = _isMeasuringHr.asStateFlow()

    private val _sleepSession = MutableStateFlow(SleepSession())
    val sleepSession: StateFlow<SleepSession> = _sleepSession.asStateFlow()

    private val _findPhoneTrigger = MutableStateFlow(false)
    val findPhoneTrigger: StateFlow<Boolean> = _findPhoneTrigger.asStateFlow()

    // Discovered scan devices
    val discoveredDevices = MutableStateFlow<List<Pair<String, String>>>(emptyList())

    inner class LocalBinder : Binder() {
        fun getService(): LenovoWatchBleService = this@LenovoWatchBleService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Bluetooth adapter", e)
        }

        // Try auto-reconnect if permissions allow
        if (hasConnectPermission()) {
            val lastMac = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LAST_MAC, null)
            if (!lastMac.isNullOrEmpty()) {
                connect(lastMac)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        serviceScope.cancel()
        disconnect()
        stopForegroundSafely()
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startForegroundSafely() {
        if (!hasConnectPermission()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Lenovo Watch Connection",
                    NotificationManager.IMPORTANCE_LOW
                )
                val manager = getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Watch X")
                .setContentText("Connected to Lenovo Watch X")
                .setSmallIcon(R.drawable.ic_stat_watch)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Foreground service promotion skipped: ${e.message}")
        }
    }

    private fun stopForegroundSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stop foreground skipped: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasScanPermission()) {
            Log.w(TAG, "startScan aborted: BLUETOOTH_SCAN permission missing")
            return
        }

        try {
            val adapter = bluetoothAdapter
            if (adapter == null || !adapter.isEnabled) {
                Log.w(TAG, "Bluetooth is disabled")
                return
            }

            val scanner = adapter.bluetoothLeScanner ?: return
            _connectionState.value = BleConnectionState.SCANNING
            discoveredDevices.value = emptyList()

            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(LenovoWatchProtocol.UUID_SERVICE))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            scanner.startScan(listOf(filter), settings, scanCallback)

            // Fallback scan after 3s without filter to discover all nearby devices
            serviceScope.launch {
                delay(3000)
                if (discoveredDevices.value.isEmpty() && _connectionState.value == BleConnectionState.SCANNING) {
                    try {
                        scanner.stopScan(scanCallback)
                        scanner.startScan(scanCallback)
                    } catch (e: Exception) {
                        Log.e(TAG, "Fallback scan failed", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scan", e)
            _connectionState.value = BleConnectionState.DISCONNECTED
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        try {
            if (hasScanPermission()) {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop BLE scan", e)
        }
        if (_connectionState.value == BleConnectionState.SCANNING) {
            _connectionState.value = BleConnectionState.DISCONNECTED
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            try {
                val device = result?.device ?: return
                val name = device.name ?: result.scanRecord?.deviceName ?: "Watch X"
                val address = device.address ?: return

                val current = discoveredDevices.value.toMutableList()
                if (current.none { it.second == address }) {
                    current.add(Pair(name, address))
                    discoveredDevices.value = current
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in onScanResult", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(macAddress: String): Boolean {
        if (!hasConnectPermission()) {
            Log.w(TAG, "connect aborted: BLUETOOTH_CONNECT permission missing")
            return false
        }

        stopScan()
        _connectionState.value = BleConnectionState.CONNECTING

        return try {
            val device = bluetoothAdapter?.getRemoteDevice(macAddress) ?: return false

            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_MAC, macAddress)
                .apply()

            bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to $macAddress", e)
            _connectionState.value = BleConnectionState.DISCONNECTED
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting GATT", e)
        } finally {
            bluetoothGatt = null
            _connectionState.value = BleConnectionState.DISCONNECTED
            stopForegroundSafely()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            try {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Connected to watch. Discovering GATT services...")
                    startForegroundSafely()
                    bluetoothGatt?.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.w(TAG, "Disconnected from watch.")
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    stopForegroundSafely()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in onConnectionStateChange", e)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            try {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt?.getService(LenovoWatchProtocol.UUID_SERVICE)
                    if (service != null) {
                        writeCharacteristic = service.getCharacteristic(LenovoWatchProtocol.UUID_WRITE)
                        notifyCharacteristic = service.getCharacteristic(LenovoWatchProtocol.UUID_NOTIFY)

                        notifyCharacteristic?.let { char ->
                            gatt.setCharacteristicNotification(char, true)
                            val descriptor = char.getDescriptor(LenovoWatchProtocol.UUID_CCCD)
                            if (descriptor != null) {
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(descriptor)
                            }
                        }

                        _connectionState.value = BleConnectionState.CONNECTED
                        Log.i(TAG, "Lenovo Watch X initialization pipeline starting.")

                        // Non-blocking query sequence on background coroutine
                        serviceScope.launch {
                            sendPacket(LenovoWatchProtocol.buildAuthPacket(seq++))
                            delay(120)
                            sendPacket(LenovoWatchProtocol.buildEnableNotificationsPacket(seq++))
                            delay(120)
                            sendPacket(LenovoWatchProtocol.buildFirmwareQueryPacket(seq++))
                            delay(120)
                            sendPacket(LenovoWatchProtocol.buildBatteryQueryPacket(seq++))
                            delay(120)
                            sendPacket(LenovoWatchProtocol.buildStepsQueryPacket(seq++))
                            delay(120)
                            sendPacket(LenovoWatchProtocol.buildSleepQueryPacket(seq++))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in onServicesDiscovered", e)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            try {
                val value = characteristic?.value ?: return
                val parsed = LenovoWatchProtocol.parseIncomingPacket(value) ?: return

                when (parsed) {
                    is WatchResponse.Battery -> {
                        _batteryLevel.value = parsed.levelPercent
                    }
                    is WatchResponse.Firmware -> {
                        _firmwareVersion.value = parsed.version
                    }
                    is WatchResponse.Steps -> {
                        _todaySteps.value = parsed.count
                    }
                    is WatchResponse.HeartRate -> {
                        _heartRateBpm.value = parsed.bpm
                        _isMeasuringHr.value = false
                    }
                    is WatchResponse.Sleep -> {
                        _sleepSession.value = SleepSession(
                            totalMinutes = parsed.totalMinutes,
                            deepMinutes = parsed.deepMinutes,
                            lightMinutes = parsed.lightMinutes,
                            awakeMinutes = parsed.awakeMinutes,
                            score = (parsed.deepMinutes * 100 / parsed.totalMinutes.coerceAtLeast(1)).coerceIn(60, 99)
                        )
                    }
                    is WatchResponse.ButtonTriggered -> {
                        triggerFindPhoneAlarm()
                    }
                    is WatchResponse.Ack -> {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in onCharacteristicChanged", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun sendPacket(packet: ByteArray) {
        try {
            val gatt = bluetoothGatt ?: return
            val char = writeCharacteristic ?: return
            char.value = packet
            gatt.writeCharacteristic(char)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending packet: ${e.message}")
        }
    }

    fun syncTime() {
        if (_connectionState.value != BleConnectionState.CONNECTED) return
        val packet = LenovoWatchProtocol.buildSetTimePacket(seq = seq++)
        sendPacket(packet)
    }

    fun querySteps() {
        if (_connectionState.value != BleConnectionState.CONNECTED) return
        sendPacket(LenovoWatchProtocol.buildStepsQueryPacket(seq++))
    }

    fun querySleep() {
        if (_connectionState.value != BleConnectionState.CONNECTED) return
        sendPacket(LenovoWatchProtocol.buildSleepQueryPacket(seq++))
    }

    fun measureHeartRate() {
        if (_connectionState.value != BleConnectionState.CONNECTED) return
        _isMeasuringHr.value = true
        sendPacket(LenovoWatchProtocol.buildHeartRateQueryPacket(seq++))

        serviceScope.launch {
            delay(10000)
            _isMeasuringHr.value = false
        }
    }

    fun pushNotification(title: String, body: String, channel: Int = 7) {
        if (_connectionState.value != BleConnectionState.CONNECTED) return
        val packet = LenovoWatchProtocol.buildNotificationPacket(title, body, channel, seq++)
        sendPacket(packet)
    }

    fun calibrateHands(hour: Int, minute: Int) {
        if (_connectionState.value != BleConnectionState.CONNECTED) return
        serviceScope.launch {
            sendPacket(LenovoWatchProtocol.buildPacket(byteArrayOf(0x03, 0x31, 0x01), LenovoWatchProtocol.ACTION_TASK, seq++))
            delay(200)
            sendPacket(LenovoWatchProtocol.buildCalibrationPositionPacket(hour, minute, seq++))
            delay(300)
            syncTime()
            delay(200)
            sendPacket(LenovoWatchProtocol.buildPacket(byteArrayOf(0x03, 0x31, 0x00), LenovoWatchProtocol.ACTION_TASK, seq++))
        }
    }

    fun setAlarm(slot: Int, hour: Int, minute: Int, daysMask: Int, enabled: Boolean) {
        if (_connectionState.value != BleConnectionState.CONNECTED) return
        val packet = LenovoWatchProtocol.buildAlarmPacket(slot, hour, minute, daysMask, enabled, seq++)
        sendPacket(packet)
    }

    fun setDnd(enabled: Boolean) {
        if (_connectionState.value != BleConnectionState.CONNECTED) return
        val packet = LenovoWatchProtocol.buildDndPacket(enabled, seq++)
        sendPacket(packet)
    }

    fun setLiftToWake(enabled: Boolean) {
        if (_connectionState.value != BleConnectionState.CONNECTED) return
        val packet = LenovoWatchProtocol.buildLiftToWakePacket(enabled, seq++)
        sendPacket(packet)
    }

    fun setStepGoal(goal: Int) {
        _stepGoal.value = goal
        if (_connectionState.value != BleConnectionState.CONNECTED) return
        sendPacket(LenovoWatchProtocol.buildStepGoalPacket(goal, seq++))
    }

    fun findMyWatch() {
        pushNotification("FIND WATCH", "VIBRATING...", 1024)
    }

    private fun triggerFindPhoneAlarm() {
        _findPhoneTrigger.value = true
        try {
            val alert = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val ringtone = RingtoneManager.getRingtone(applicationContext, alert)
            ringtone?.play()
            serviceScope.launch {
                delay(3000)
                try { ringtone?.stop() } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing find phone alarm", e)
        }
    }

    fun dismissFindPhone() {
        _findPhoneTrigger.value = false
    }
}
