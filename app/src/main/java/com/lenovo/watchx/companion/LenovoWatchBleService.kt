package com.lenovo.watchx.companion

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
import android.media.RingtoneManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar

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
        const val TAG = "KronoBleService"
        const val CHANNEL_ID = "krono_watch_service_channel"
        const val NOTIFICATION_ID = 1001
        const val PREFS_NAME = "krono_watch_prefs"
        const val KEY_LAST_MAC = "last_connected_mac"

        var instance: LenovoWatchBleService? = null
            private set
    }

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
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        startForegroundServiceNotification()

        // Auto-reconnect if last mac exists
        val lastMac = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LAST_MAC, null)
        if (!lastMac.isNullOrEmpty()) {
            connect(lastMac)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        disconnect()
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Krono Hybrid Watch Connection",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Krono Hybrid")
                .setContentText("Local Bluetooth companion active")
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("Krono Hybrid")
                .setContentText("Local Bluetooth companion active")
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .build()
        }

        startForeground(NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        _connectionState.value = BleConnectionState.SCANNING
        discoveredDevices.value = emptyList()

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(LenovoWatchProtocol.UUID_SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, scanCallback)

        // Fallback scan without filter after 4 seconds if nothing found
        android.os.Handler(mainLooper).postDelayed({
            if (discoveredDevices.value.isEmpty()) {
                scanner.stopScan(scanCallback)
                scanner.startScan(scanCallback)
            }
        }, 3000)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        if (_connectionState.value == BleConnectionState.SCANNING) {
            _connectionState.value = BleConnectionState.DISCONNECTED
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val name = device.name ?: result.scanRecord?.deviceName ?: "Lenovo Watch"
            val address = device.address ?: return

            val current = discoveredDevices.value.toMutableList()
            if (current.none { it.second == address }) {
                current.add(Pair(name, address))
                discoveredDevices.value = current
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(macAddress: String): Boolean {
        stopScan()
        _connectionState.value = BleConnectionState.CONNECTING
        val device = bluetoothAdapter?.getRemoteDevice(macAddress) ?: return false

        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_MAC, macAddress)
            .apply()

        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        return true
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _connectionState.value = BleConnectionState.DISCONNECTED
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to watch. Discovering GATT services...")
                bluetoothGatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "Disconnected from watch.")
                _connectionState.value = BleConnectionState.DISCONNECTED
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
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

                    // Handshake and query sequence
                    sendPacket(LenovoWatchProtocol.buildAuthPacket(seq++))
                    Thread.sleep(100)
                    sendPacket(LenovoWatchProtocol.buildEnableNotificationsPacket(seq++))
                    Thread.sleep(100)
                    sendPacket(LenovoWatchProtocol.buildFirmwareQueryPacket(seq++))
                    Thread.sleep(100)
                    sendPacket(LenovoWatchProtocol.buildBatteryQueryPacket(seq++))
                    Thread.sleep(100)
                    sendPacket(LenovoWatchProtocol.buildStepsQueryPacket(seq++))
                    Thread.sleep(100)
                    sendPacket(LenovoWatchProtocol.buildSleepQueryPacket(seq++))
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
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
        }
    }

    @SuppressLint("MissingPermission")
    fun sendPacket(packet: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val char = writeCharacteristic ?: return
        char.value = packet
        gatt.writeCharacteristic(char)
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

        // Reset measurement timeout after 10 seconds if no answer
        android.os.Handler(mainLooper).postDelayed({
            _isMeasuringHr.value = false
        }, 10000)
    }

    fun pushNotification(title: String, body: String, channel: Int = 7) {
        if (_connectionState.value != BleConnectionState.CONNECTED) return
        val packet = LenovoWatchProtocol.buildNotificationPacket(title, body, channel, seq++)
        sendPacket(packet)
    }

    fun calibrateHands(hour: Int, minute: Int) {
        if (_connectionState.value != BleConnectionState.CONNECTED) return
        sendPacket(LenovoWatchProtocol.buildPacket(byteArrayOf(0x03, 0x31, 0x01), LenovoWatchProtocol.ACTION_TASK, seq++))
        Thread.sleep(200)
        sendPacket(LenovoWatchProtocol.buildCalibrationPositionPacket(hour, minute, seq++))
        Thread.sleep(300)
        syncTime()
        Thread.sleep(200)
        sendPacket(LenovoWatchProtocol.buildPacket(byteArrayOf(0x03, 0x31, 0x00), LenovoWatchProtocol.ACTION_TASK, seq++))
    }

    fun setAlarm(slot: Int, hour: Int, minute: Int, daysMask: Int, enabled: Boolean) {
        if (_connectionState.value != BleConnectionState.CONNECTED) return
        val packet = LenovoWatchProtocol.buildAlarmPacket(slot, hour, minute, daysMask, enabled, seq++)
        sendPacket(packet)
    }

    fun setDnd(enabled: Boolean) {
        if (_connectionState.value != BleConnectionState.CONNECTED) return
        sendPacket(LenovoWatchProtocol.buildDndPacket(enabled, seq++))
    }

    fun setLiftToWake(enabled: Boolean) {
        if (_connectionState.value != BleConnectionState.CONNECTED) return
        sendPacket(LenovoWatchProtocol.buildLiftToWakePacket(enabled, seq++))
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
            ringtone.play()
            android.os.Handler(mainLooper).postDelayed({
                ringtone.stop()
            }, 3000)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing find phone alarm", e)
        }
    }

    fun dismissFindPhone() {
        _findPhoneTrigger.value = false
    }
}
