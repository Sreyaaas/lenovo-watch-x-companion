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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

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
        const val KEY_24HOUR = "pref_24hour"
        const val KEY_METRIC = "pref_metric"
        const val KEY_ENGLISH = "pref_english"
        const val KEY_SEDENTARY = "pref_sedentary"
        const val KEY_DRINK_WATER = "pref_drink_water"
        const val KEY_DND = "pref_dnd"
        const val KEY_TILT = "pref_tilt"

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

    private val writeChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val writeMutex = Mutex()
    private var writeCompletionDeferred: CompletableDeferred<Boolean>? = null
    private var queueWorkerJob: Job? = null
    private var initializationJob: Job? = null
    private var periodicSyncJob: Job? = null

    // State flows
    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _batteryLevel = MutableStateFlow(0)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _firmwareVersion = MutableStateFlow("")
    val firmwareVersion: StateFlow<String> = _firmwareVersion.asStateFlow()

    private val _todaySteps = MutableStateFlow(0)
    val todaySteps: StateFlow<Int> = _todaySteps.asStateFlow()

    private val _stepGoal = MutableStateFlow(10000)
    val stepGoal: StateFlow<Int> = _stepGoal.asStateFlow()

    private val _heartRateBpm = MutableStateFlow(0)
    val heartRateBpm: StateFlow<Int> = _heartRateBpm.asStateFlow()

    private val _isMeasuringHr = MutableStateFlow(false)
    val isMeasuringHr: StateFlow<Boolean> = _isMeasuringHr.asStateFlow()

    private val _bloodPressure = MutableStateFlow(Pair(120, 80))
    val bloodPressure: StateFlow<Pair<Int, Int>> = _bloodPressure.asStateFlow()

    private val _isMeasuringBp = MutableStateFlow(false)
    val isMeasuringBp: StateFlow<Boolean> = _isMeasuringBp.asStateFlow()

    private val _isCalibratingHands = MutableStateFlow(false)
    val isCalibratingHands: StateFlow<Boolean> = _isCalibratingHands.asStateFlow()

    private val _sleepSession = MutableStateFlow(SleepSession())
    val sleepSession: StateFlow<SleepSession> = _sleepSession.asStateFlow()

    private val _findPhoneTrigger = MutableStateFlow(false)
    val findPhoneTrigger: StateFlow<Boolean> = _findPhoneTrigger.asStateFlow()

    private val _is24Hour = MutableStateFlow(true)
    val is24Hour: StateFlow<Boolean> = _is24Hour.asStateFlow()

    private val _isMetric = MutableStateFlow(true)
    val isMetric: StateFlow<Boolean> = _isMetric.asStateFlow()

    private val _isEnglish = MutableStateFlow(true)
    val isEnglish: StateFlow<Boolean> = _isEnglish.asStateFlow()

    private val _sedentaryEnabled = MutableStateFlow(false)
    val sedentaryEnabled: StateFlow<Boolean> = _sedentaryEnabled.asStateFlow()

    private val _drinkWaterEnabled = MutableStateFlow(false)
    val drinkWaterEnabled: StateFlow<Boolean> = _drinkWaterEnabled.asStateFlow()

    private val _dndEnabled = MutableStateFlow(false)
    val dndEnabled: StateFlow<Boolean> = _dndEnabled.asStateFlow()

    private val _tiltEnabled = MutableStateFlow(true)
    val tiltEnabled: StateFlow<Boolean> = _tiltEnabled.asStateFlow()

    private val _cameraModeActive = MutableStateFlow(false)
    val cameraModeActive: StateFlow<Boolean> = _cameraModeActive.asStateFlow()

    // Discovered scan devices
    val discoveredDevices = MutableStateFlow<List<Pair<String, String>>>(emptyList())

    inner class LocalBinder : Binder() {
        fun getService(): LenovoWatchBleService = this@LenovoWatchBleService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafely()
        if (hasConnectPermission()) {
            val lastMac = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LAST_MAC, null)
            if (!lastMac.isNullOrEmpty() && _connectionState.value == BleConnectionState.DISCONNECTED) {
                connect(lastMac)
            }
        }
        return START_STICKY
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                    Log.w(TAG, "Bluetooth turned OFF by system")
                    handleDeadGatt("Bluetooth turned off")
                } else if (state == BluetoothAdapter.STATE_ON) {
                    Log.i(TAG, "Bluetooth turned ON, re-initializing adapter and reconnecting...")
                    bluetoothAdapter = bluetoothManager?.adapter
                    serviceScope.launch {
                        delay(1000)
                        val lastMac = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LAST_MAC, null)
                        if (!lastMac.isNullOrEmpty()) {
                            connect(lastMac)
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Bluetooth adapter", e)
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _is24Hour.value = prefs.getBoolean(KEY_24HOUR, true)
        _isMetric.value = prefs.getBoolean(KEY_METRIC, true)
        _isEnglish.value = prefs.getBoolean(KEY_ENGLISH, true)
        _sedentaryEnabled.value = prefs.getBoolean(KEY_SEDENTARY, false)
        _drinkWaterEnabled.value = prefs.getBoolean(KEY_DRINK_WATER, false)
        _dndEnabled.value = prefs.getBoolean(KEY_DND, false)
        _tiltEnabled.value = prefs.getBoolean(KEY_TILT, true)

        try {
            registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register btStateReceiver", e)
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
        try {
            unregisterReceiver(btStateReceiver)
        } catch (e: Exception) {}
        periodicSyncJob?.cancel()
        initializationJob?.cancel()
        queueWorkerJob?.cancel()
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

            val isConnected = _connectionState.value == BleConnectionState.CONNECTED
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Watch X")
                .setContentText(if (isConnected) "Connected to Lenovo Watch X" else "Monitoring Lenovo Watch X")
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

            val filters = mutableListOf<ScanFilter>()
            filters.add(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(LenovoWatchProtocol.UUID_SERVICE))
                    .build()
            )
            listOf("Watch X", "WATCH X", "WATCH XPLUS", "Watch 9", "Lenovo Watch").forEach { name ->
                filters.add(ScanFilter.Builder().setDeviceName(name).build())
            }

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            Log.i(TAG, "Starting BLE scan for Lenovo Watch devices")
            scanner.startScan(filters, settings, scanCallback)

            // Fallback scan after 3s with broader filter if nothing found yet
            serviceScope.launch {
                delay(3000)
                if (discoveredDevices.value.isEmpty() && _connectionState.value == BleConnectionState.SCANNING) {
                    try {
                        Log.i(TAG, "Broadening BLE scan...")
                        scanner.stopScan(scanCallback)
                        scanner.startScan(scanCallback)
                    } catch (e: Exception) {
                        Log.w(TAG, "Fallback broad scan failed: ${e.message}")
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
                val name = device.name ?: result.scanRecord?.deviceName ?: "Lenovo Watch"
                val address = device.address ?: return
                val rssi = result.rssi

                Log.d(TAG, "Discovered device: '$name' ($address) RSSI: $rssi dBm")

                val current = discoveredDevices.value.toMutableList()
                if (current.none { it.second == address }) {
                    current.add(Pair(if (name.isNotEmpty()) name else "Watch X", address))
                    discoveredDevices.value = current
                }

                // Auto connect to Watch X if detected while scanning
                val isWatchX = name.contains("Watch X", ignoreCase = true) ||
                        name.contains("Lenovo", ignoreCase = true) ||
                        address.endsWith(":7D:7D", ignoreCase = true) ||
                        result?.scanRecord?.serviceUuids?.any { it.uuid == LenovoWatchProtocol.UUID_SERVICE } == true

                if (_connectionState.value == BleConnectionState.SCANNING && isWatchX) {
                    Log.i(TAG, "Found target watch '$name' ($address)! Auto-connecting...")
                    connect(address)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in onScanResult", e)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE Scan failed: $errorCode")
            _connectionState.value = BleConnectionState.DISCONNECTED
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
        Log.i(TAG, "Connecting to watch at $macAddress")

        return try {
            try {
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Closing previous gatt instance", e)
            }
            bluetoothGatt = null

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
        Log.i(TAG, "Disconnect requested")
        periodicSyncJob?.cancel()
        periodicSyncJob = null
        initializationJob?.cancel()
        initializationJob = null
        queueWorkerJob?.cancel()
        queueWorkerJob = null

        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting GATT", e)
        } finally {
            bluetoothGatt = null
            writeCharacteristic = null
            notifyCharacteristic = null
            _connectionState.value = BleConnectionState.DISCONNECTED
            stopForegroundSafely()
        }
    }

    @Synchronized
    private fun handleDeadGatt(reason: String) {
        Log.w(TAG, "Handling dead GATT connection ($reason). Resetting...")
        periodicSyncJob?.cancel()
        periodicSyncJob = null
        initializationJob?.cancel()
        initializationJob = null
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {}
        bluetoothGatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        _connectionState.value = BleConnectionState.DISCONNECTED

        serviceScope.launch {
            delay(2000)
            val lastMac = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LAST_MAC, null)
            if (!lastMac.isNullOrEmpty() && _connectionState.value == BleConnectionState.DISCONNECTED) {
                Log.i(TAG, "Auto-reconnecting to $lastMac after dead GATT recovery")
                connect(lastMac)
            } else if (_connectionState.value == BleConnectionState.DISCONNECTED) {
                startScan()
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange: status=$status newState=$newState device=${gatt?.device?.address}")
            try {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Connected to watch. Requesting HIGH priority, MTU & discovering GATT services...")
                    gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    gatt?.requestMtu(512)
                    startForegroundSafely()

                    // Android BLE best practice: 300ms delay before discoverServices
                    serviceScope.launch {
                        delay(300)
                        gatt?.discoverServices()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.w(TAG, "Disconnected from watch (status=$status).")
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    stopForegroundSafely()
                    periodicSyncJob?.cancel()
                    periodicSyncJob = null
                    initializationJob?.cancel()
                    initializationJob = null
                    try {
                        gatt?.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing GATT", e)
                    }
                    bluetoothGatt = null
                    writeCharacteristic = null
                    notifyCharacteristic = null

                    // Auto-reconnect after disconnection
                    serviceScope.launch {
                        delay(2500)
                        val lastMac = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LAST_MAC, null)
                        if (!lastMac.isNullOrEmpty() && _connectionState.value == BleConnectionState.DISCONNECTED) {
                            Log.i(TAG, "Attempting auto-reconnect to $lastMac after disconnection...")
                            connect(lastMac)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in onConnectionStateChange", e)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            Log.i(TAG, "onMtuChanged: mtu=$mtu status=$status")
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            Log.i(TAG, "onServicesDiscovered: status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) {
                Log.e(TAG, "Service discovery failed with status $status")
                return
            }

            try {
                val service = gatt.getService(LenovoWatchProtocol.UUID_SERVICE)
                if (service == null) {
                    Log.e(TAG, "Service ${LenovoWatchProtocol.UUID_SERVICE} not found!")
                    gatt.services?.forEach { s -> Log.d(TAG, "Found service: ${s.uuid}") }
                    return
                }

                writeCharacteristic = service.getCharacteristic(LenovoWatchProtocol.UUID_WRITE)
                notifyCharacteristic = service.getCharacteristic(LenovoWatchProtocol.UUID_NOTIFY)

                Log.i(TAG, "GATT Services mapped: Write=${writeCharacteristic?.uuid}, Notify=${notifyCharacteristic?.uuid}")

                val notifyChar = notifyCharacteristic ?: run {
                    Log.e(TAG, "Notify characteristic not found!")
                    return
                }

                // 1. Enable local notifications
                val localNotifSuccess = gatt.setCharacteristicNotification(notifyChar, true)
                Log.i(TAG, "setCharacteristicNotification returned $localNotifSuccess")

                // 2. Also check if writeCharacteristic supports notify
                writeCharacteristic?.let { wc ->
                    if ((wc.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) {
                        gatt.setCharacteristicNotification(wc, true)
                    }
                }

                // 3. Write CCCD Descriptor (0x2902) to notify peripheral
                val descriptor = notifyChar.getDescriptor(LenovoWatchProtocol.UUID_CCCD)
                    ?: notifyChar.descriptors.firstOrNull { it.uuid.toString().contains("2902") }

                if (descriptor == null) {
                    Log.e(TAG, "CCCD descriptor not found on notify characteristic!")
                    return
                }

                val cccdValue = if ((notifyChar.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }

                val descWriteStarted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    descriptor.value = cccdValue
                    val res = gatt.writeDescriptor(descriptor, cccdValue)
                    Log.i(TAG, "writeDescriptor (API 33+) result: $res")
                    res == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = cccdValue
                    @Suppress("DEPRECATION")
                    val res = gatt.writeDescriptor(descriptor)
                    Log.i(TAG, "writeDescriptor (legacy) result: $res")
                    res
                }

                if (!descWriteStarted) {
                    Log.e(TAG, "Failed to start descriptor write")
                }
                // IMPORTANT: Do NOT send packets here! Wait for onDescriptorWrite callback!
            } catch (e: Exception) {
                Log.e(TAG, "Error in onServicesDiscovered", e)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            val statusStr = if (status == BluetoothGatt.GATT_SUCCESS) "SUCCESS" else "ERROR($status)"
            Log.i(TAG, "onDescriptorWrite: uuid=${descriptor?.uuid}, status=$statusStr")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = BleConnectionState.CONNECTED
                Log.i(TAG, "CCCD descriptor write confirmed. Watch radio is ready, starting initialization pipeline!")
                startInitializationSequence()
            } else {
                Log.e(TAG, "CCCD descriptor write failed with status $status")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            val statusStr = if (status == BluetoothGatt.GATT_SUCCESS) "SUCCESS" else "ERROR($status)"
            Log.d(TAG, "onCharacteristicWrite: uuid=${characteristic?.uuid}, status=$statusStr")
            writeCompletionDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // Android 13+ (API 33+)
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicRead (API 33+): uuid=${characteristic.uuid}, status=$status, size=${value.size}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onIncomingPacket(characteristic.uuid, value)
            }
        }

        // Android 12 and below
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && status == BluetoothGatt.GATT_SUCCESS) {
                val value = characteristic?.value ?: return
                val uuid = characteristic.uuid ?: return
                onIncomingPacket(uuid, value)
            }
        }

        // Android 13+ (API 33+)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onIncomingPacket(characteristic.uuid, value)
        }

        // Android 12 and below
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = characteristic?.value ?: return
                val uuid = characteristic.uuid ?: return
                onIncomingPacket(uuid, value)
            }
        }
    }

    private fun onIncomingPacket(uuid: UUID, value: ByteArray) {
        try {
            val hex = value.joinToString(" ") { "%02X".format(it) }
            Log.i(TAG, "RX notification [${value.size} bytes] on $uuid: $hex")

            val parsed = LenovoWatchProtocol.parseIncomingPacket(value) ?: run {
                Log.w(TAG, "Packet unparsed: $hex")
                return
            }

            Log.i(TAG, "Parsed WatchResponse: $parsed")

            when (parsed) {
                is WatchResponse.Battery -> {
                    _batteryLevel.value = parsed.levelPercent
                    Log.i(TAG, "Updated battery: ${parsed.levelPercent}% (normal=${parsed.isNormal})")
                }
                is WatchResponse.Firmware -> {
                    _firmwareVersion.value = parsed.version
                    Log.i(TAG, "Updated firmware: v${parsed.version}")
                }
                is WatchResponse.Steps -> {
                    _todaySteps.value = parsed.count
                    Log.i(TAG, "Updated step count: ${parsed.count}")
                }
                is WatchResponse.HeartRate -> {
                    _heartRateBpm.value = parsed.bpm
                    _isMeasuringHr.value = false
                    Log.i(TAG, "Updated heart rate: ${parsed.bpm} bpm")
                }
                is WatchResponse.BloodPressure -> {
                    _bloodPressure.value = Pair(parsed.systolic, parsed.diastolic)
                    _isMeasuringBp.value = false
                    Log.i(TAG, "Updated blood pressure: ${parsed.systolic}/${parsed.diastolic} mmHg")
                }
                is WatchResponse.Sleep -> {
                    _sleepSession.value = SleepSession(
                        totalMinutes = parsed.totalMinutes,
                        deepMinutes = parsed.deepMinutes,
                        lightMinutes = parsed.lightMinutes,
                        awakeMinutes = parsed.awakeMinutes,
                        score = (parsed.deepMinutes * 100 / parsed.totalMinutes.coerceAtLeast(1)).coerceIn(60, 99)
                    )
                    Log.i(TAG, "Updated sleep telemetry: ${parsed.totalMinutes} min")
                }
                is WatchResponse.ButtonTriggered -> {
                    Log.i(TAG, "Watch button pressed! Triggering find phone alarm")
                    triggerFindPhoneAlarm()
                }
                is WatchResponse.Ack -> {
                    Log.d(TAG, "Received ACK packet from watch")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onIncomingPacket", e)
        }
    }

    @Synchronized
    private fun startQueueWorkerIfNeeded() {
        if (queueWorkerJob?.isActive == true) return
        queueWorkerJob = serviceScope.launch {
            Log.i(TAG, "BLE Write Queue worker started")
            for (packet in writeChannel) {
                val gatt = bluetoothGatt
                val char = writeCharacteristic
                if (gatt == null || char == null) {
                    Log.w(TAG, "Dropping packet: GATT or writeCharacteristic is null")
                    continue
                }

                writeMutex.withLock {
                    val deferred = CompletableDeferred<Boolean>()
                    writeCompletionDeferred = deferred

                    val success = sendPacketInternal(gatt, char, packet)
                    if (success) {
                        withTimeoutOrNull(300) {
                            deferred.await()
                        }
                    } else {
                        Log.w(TAG, "sendPacketInternal returned false")
                    }
                    writeCompletionDeferred = null
                }
                delay(60)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendPacketInternal(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, packet: ByteArray): Boolean {
        val hex = packet.joinToString(" ") { "%02X".format(it) }
        val writeType = if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        Log.d(TAG, "TX [${packet.size}b] (writeType=$writeType): $hex")

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val res = gatt.writeCharacteristic(char, packet, writeType)
                val isSuccess = res == BluetoothStatusCodes.SUCCESS
                if (!isSuccess) {
                    Log.e(TAG, "gatt.writeCharacteristic (API 33+) failed with code: $res")
                    if (res == BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND) {
                        handleDeadGatt("GATT write failure status $res")
                    }
                }
                isSuccess
            } else {
                @Suppress("DEPRECATION")
                char.writeType = writeType
                @Suppress("DEPRECATION")
                char.value = packet
                @Suppress("DEPRECATION")
                val res = gatt.writeCharacteristic(char)
                if (!res) {
                    Log.e(TAG, "gatt.writeCharacteristic (legacy) returned false")
                }
                res
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during writeCharacteristic: ${e.message}", e)
            if (e is android.os.DeadObjectException || e.cause is android.os.DeadObjectException) {
                handleDeadGatt("DeadObjectException in writeCharacteristic")
            }
            false
        }
    }

    fun sendPacket(packet: ByteArray) {
        startQueueWorkerIfNeeded()
        writeChannel.trySend(packet)
    }

    private fun startInitializationSequence() {
        initializationJob?.cancel()
        initializationJob = serviceScope.launch {
            Log.i(TAG, "==================== WATCH X INIT PIPELINE START ====================")
            
            // 1. Send Handshake / Auth Packet (reconnect)
            Log.i(TAG, "Step 1/7: Sending Auth Reconnect Packet")
            sendPacket(LenovoWatchProtocol.buildAuthPacket(reconnect = true, seq = seq++))
            delay(150)

            // Step 1b: Also send initial pairing packet if watch was unpaired
            sendPacket(LenovoWatchProtocol.buildAuthPacket(reconnect = false, seq = seq++))
            delay(150)

            // 2. Enable Notifications in Watch Firmware
            Log.i(TAG, "Step 2/7: Enabling Firmware Notifications")
            sendPacket(LenovoWatchProtocol.buildEnableNotificationsPacket(seq = seq++))
            delay(150)

            // 3. Immediately Synchronize Watch Clock to Phone Time
            Log.i(TAG, "Step 3/7: Synchronizing Watch Clock (Real Time & Motors)")
            sendPacket(LenovoWatchProtocol.buildSetTimePacket(seq = seq++))
            delay(150)

            // Push saved user preferences using real Watch X commands
            sendPacket(LenovoWatchProtocol.buildTimeAndLanguagePacket(_isEnglish.value, _is24Hour.value, seq++))
            delay(120)
            sendPacket(LenovoWatchProtocol.buildUnitPacket(_isMetric.value, seq++))
            delay(120)
            sendPacket(LenovoWatchProtocol.buildLiftToWakePacket(_tiltEnabled.value, seq++))
            delay(120)
            sendPacket(LenovoWatchProtocol.buildDndPacket(_dndEnabled.value, seq++))
            delay(120)

            // 4. Query Firmware Version
            Log.i(TAG, "Step 4/7: Querying Firmware Version")
            sendPacket(LenovoWatchProtocol.buildFirmwareQueryPacket(seq = seq++))
            delay(150)

            // 5. Query Battery Level
            Log.i(TAG, "Step 5/7: Querying Battery Level")
            sendPacket(LenovoWatchProtocol.buildBatteryQueryPacket(seq = seq++))
            delay(150)

            // 6. Query Today's Steps
            Log.i(TAG, "Step 6/8: Querying Pedometer Steps")
            sendPacket(LenovoWatchProtocol.buildStepsQueryPacket(seq = seq++))
            delay(150)

            // 7. Query Sleep Data
            Log.i(TAG, "Step 7/8: Querying Sleep Telemetry")
            sendPacket(LenovoWatchProtocol.buildSleepQueryPacket(seq = seq++))
            delay(300)

            // 8. Query Heart Rate Telemetry
            Log.i(TAG, "Step 8/9: Querying Heart Rate Telemetry")
            sendPacket(LenovoWatchProtocol.buildHeartRateQueryPacket(seq = seq++))
            delay(300)

            // 9. Query Blood Pressure Telemetry
            Log.i(TAG, "Step 9/9: Querying Blood Pressure Telemetry")
            sendPacket(LenovoWatchProtocol.buildBpQueryPacket(seq = seq++))
            delay(300)

            // Lower connection priority to BALANCED to save watch battery
            Log.i(TAG, "Initialization complete. Switching to BALANCED BLE connection priority")
            bluetoothGatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)

            // Start periodic sync (steps, battery, HR, and BP every 45s as keep-alive)
            startPeriodicSync()
            Log.i(TAG, "==================== WATCH X READY & ACTIVE ====================")
        }
    }

    private fun startPeriodicSync() {
        periodicSyncJob?.cancel()
        periodicSyncJob = serviceScope.launch {
            while (_connectionState.value == BleConnectionState.CONNECTED) {
                delay(45_000)
                if (_connectionState.value == BleConnectionState.CONNECTED) {
                    Log.d(TAG, "Periodic keep-alive & telemetry sync")
                    querySteps()
                    delay(300)
                    sendPacket(LenovoWatchProtocol.buildBatteryQueryPacket(seq++))
                    delay(300)
                    queryHeartRate()
                    delay(300)
                    queryBloodPressure()
                }
            }
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

    fun queryHeartRate() {
        if (_connectionState.value != BleConnectionState.CONNECTED) return
        sendPacket(LenovoWatchProtocol.buildHeartRateQueryPacket(seq++))
    }

    fun measureHeartRate() {
        if (_connectionState.value != BleConnectionState.CONNECTED) return
        _isMeasuringHr.value = true
        Log.i(TAG, "Starting optical PPG heart rate measurement")
        serviceScope.launch {
            // Start green optical sensor (CMD_HEART_RATE_MEASURE: 0x03, 0x23, 0x01 with both TASK and WRITE, plus BP measure trigger)
            sendPacket(LenovoWatchProtocol.buildHeartRateMeasurementPacket(start = true, seq = seq++))
            delay(150)
            sendPacket(LenovoWatchProtocol.buildHeartRateMeasurementWritePacket(start = true, seq = seq++))
            delay(150)
            sendPacket(LenovoWatchProtocol.buildBpMeasurePacket(start = true, seq = seq++))
            delay(400)
            // Query stored HR reading
            sendPacket(LenovoWatchProtocol.buildHeartRateQueryPacket(seq++))

            // Keep sensor active for 20 seconds, querying periodically
            for (step in 1..6) {
                delay(3000)
                if (!_isMeasuringHr.value) break
                sendPacket(LenovoWatchProtocol.buildHeartRateQueryPacket(seq++))
            }

            _isMeasuringHr.value = false
            // Turn off optical sensor LEDs
            sendPacket(LenovoWatchProtocol.buildHeartRateMeasurementPacket(start = false, seq = seq++))
            delay(150)
            sendPacket(LenovoWatchProtocol.buildHeartRateMeasurementWritePacket(start = false, seq = seq++))
            delay(150)
            sendPacket(LenovoWatchProtocol.buildBpMeasurePacket(start = false, seq = seq++))
            delay(300)
            sendPacket(LenovoWatchProtocol.buildHeartRateQueryPacket(seq++))
        }
    }

    fun queryBloodPressure() {
        if (_connectionState.value != BleConnectionState.CONNECTED) return
        sendPacket(LenovoWatchProtocol.buildBpQueryPacket(seq++))
    }

    fun calibrateBloodPressure(systolic: Int, diastolic: Int) {
        if (_connectionState.value != BleConnectionState.CONNECTED) return
        Log.i(TAG, "Sending BP calibration: $systolic / $diastolic mmHg")
        sendPacket(LenovoWatchProtocol.buildBpCalibrationPacket(systolic, diastolic, seq++))
        serviceScope.launch {
            delay(600)
            queryBloodPressure()
        }
    }

    fun measureBloodPressure() {
        if (_connectionState.value != BleConnectionState.CONNECTED) return
        _isMeasuringBp.value = true
        Log.i(TAG, "Starting optical PPG blood pressure measurement")
        serviceScope.launch {
            // Trigger BP optical measurement
            sendPacket(LenovoWatchProtocol.buildBpMeasurePacket(start = true, seq = seq++))
            delay(200)
            sendPacket(LenovoWatchProtocol.buildHeartRateMeasurementPacket(start = true, seq = seq++))

            // Loop checking for incoming BP data over 25 seconds
            for (step in 1..8) {
                delay(3000)
                if (!_isMeasuringBp.value) break
                sendPacket(LenovoWatchProtocol.buildBpQueryPacket(seq++))
                delay(200)
                sendPacket(LenovoWatchProtocol.buildHeartRateQueryPacket(seq++))
            }

            _isMeasuringBp.value = false
            sendPacket(LenovoWatchProtocol.buildBpMeasurePacket(start = false, seq = seq++))
            sendPacket(LenovoWatchProtocol.buildHeartRateMeasurementPacket(start = false, seq = seq++))
            delay(300)
            sendPacket(LenovoWatchProtocol.buildBpQueryPacket(seq++))
        }
    }

    fun pushNotification(title: String, body: String, channel: Int = 0) {
        if (_connectionState.value != BleConnectionState.CONNECTED) return
        val combinedText = if (title.isNotEmpty()) "$title: $body" else body
        serviceScope.launch {
            val packets = LenovoWatchProtocol.buildNotificationPackets(combinedText, channel, seq)
            seq += packets.size
            for (pkt in packets) {
                sendPacket(pkt)
                delay(60)
            }
        }
    }

    fun calibrateHands(hour: Int, minute: Int) {
        if (_connectionState.value != BleConnectionState.CONNECTED) return
        serviceScope.launch {
            _isCalibratingHands.value = true
            Log.i(TAG, "Calibrating mechanical hands with observed position $hour:$minute")
            // 1. Enter calibration mode
            sendPacket(LenovoWatchProtocol.buildPacket(byteArrayOf(0x03, 0x31, 0x01), LenovoWatchProtocol.ACTION_TASK, seq++))
            delay(250)

            // 2. Keep calibration alive
            sendPacket(LenovoWatchProtocol.buildPacket(byteArrayOf(0x03, 0x34), LenovoWatchProtocol.ACTION_KEEP_ALIVE, seq++))
            delay(200)

            // 3. Send physical hand positions (total seconds)
            val pos = ((hour % 12) * 60 + minute) * 60
            val high = ((pos shr 8) and 0xFF).toByte()
            val low = (pos and 0xFF).toByte()
            sendPacket(LenovoWatchProtocol.buildPacket(byteArrayOf(0x03, 0x33, 0x01, high, low), LenovoWatchProtocol.ACTION_TASK, seq++))

            // 4. Send atomic time so watch calculates delta angle and starts motor movement
            delay(300)
            syncTime()

            // 5. Allow 12 seconds for mechanical stepping motors to physically sweep hands into place.
            // Pulse keep-alive every 3 seconds so the watch hardware does not abort rotation
            for (step in 1..4) {
                delay(3000)
                sendPacket(LenovoWatchProtocol.buildPacket(byteArrayOf(0x03, 0x34), LenovoWatchProtocol.ACTION_KEEP_ALIVE, seq++))
            }

            // 6. Exit calibration mode once hands have reached position
            sendPacket(LenovoWatchProtocol.buildPacket(byteArrayOf(0x03, 0x31, 0x00), LenovoWatchProtocol.ACTION_TASK, seq++))
            delay(300)
            syncTime()
            _isCalibratingHands.value = false
            Log.i(TAG, "Calibration mode completed and hands aligned to real time")
        }
    }

    fun setAlarm(slot: Int, hour: Int, minute: Int, daysMask: Int, enabled: Boolean) {
        if (_connectionState.value != BleConnectionState.CONNECTED) return
        val packet = LenovoWatchProtocol.buildAlarmPacket(slot, hour, minute, daysMask, enabled, seq++)
        sendPacket(packet)
    }

    fun setDnd(enabled: Boolean) {
        _dndEnabled.value = enabled
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_DND, enabled).apply()
        if (_connectionState.value == BleConnectionState.CONNECTED) {
            sendPacket(LenovoWatchProtocol.buildDndPacket(enabled, seq++))
        }
    }

    fun setLiftToWake(enabled: Boolean) {
        _tiltEnabled.value = enabled
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_TILT, enabled).apply()
        if (_connectionState.value == BleConnectionState.CONNECTED) {
            sendPacket(LenovoWatchProtocol.buildLiftToWakePacket(enabled, seq++))
        }
    }

    fun setTimeFormat(is24Hour: Boolean) {
        _is24Hour.value = is24Hour
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_24HOUR, is24Hour).apply()
        if (_connectionState.value == BleConnectionState.CONNECTED) {
            sendPacket(LenovoWatchProtocol.buildTimeAndLanguagePacket(_isEnglish.value, is24Hour, seq++))
        }
    }

    fun setUnit(isMetric: Boolean) {
        _isMetric.value = isMetric
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_METRIC, isMetric).apply()
        if (_connectionState.value == BleConnectionState.CONNECTED) {
            sendPacket(LenovoWatchProtocol.buildUnitPacket(isMetric, seq++))
        }
    }

    fun setLanguage(isEnglish: Boolean) {
        _isEnglish.value = isEnglish
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENGLISH, isEnglish).apply()
        if (_connectionState.value == BleConnectionState.CONNECTED) {
            sendPacket(LenovoWatchProtocol.buildTimeAndLanguagePacket(isEnglish, _is24Hour.value, seq++))
        }
    }

    fun setSedentary(enabled: Boolean, startHour: Int = 9, endHour: Int = 18, intervalMin: Int = 60) {
        _sedentaryEnabled.value = enabled
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_SEDENTARY, enabled).apply()
        if (_connectionState.value == BleConnectionState.CONNECTED) {
            sendPacket(LenovoWatchProtocol.buildSedentaryPacket(enabled, startHour, endHour, intervalMin, seq++))
        }
    }

    fun setDrinkWater(enabled: Boolean, intervalHours: Int = 2) {
        _drinkWaterEnabled.value = enabled
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_DRINK_WATER, enabled).apply()
        if (_connectionState.value == BleConnectionState.CONNECTED) {
            sendPacket(LenovoWatchProtocol.buildDrinkWaterPacket(enabled, intervalHours, seq++))
        }
    }

    fun setCameraMode(enabled: Boolean) {
        _cameraModeActive.value = enabled
        if (_connectionState.value == BleConnectionState.CONNECTED) {
            sendPacket(LenovoWatchProtocol.buildCameraShutterModePacket(enabled, seq++))
        }
    }

    fun setStepGoal(goal: Int) {
        _stepGoal.value = goal
        if (_connectionState.value != BleConnectionState.CONNECTED) return
        sendPacket(LenovoWatchProtocol.buildStepGoalPacket(goal, seq++))
    }

    fun findMyWatch() {
        if (_connectionState.value != BleConnectionState.CONNECTED) return
        Log.i(TAG, "Triggering Find My Watch call vibration")
        serviceScope.launch {
            // Channel 10 phone call alert causes the watch to vibrate continuously
            sendPacket(LenovoWatchProtocol.buildVibrationPacket(seq = seq++))
            delay(4000)
            // Cancel notification to stop vibration after 4 seconds
            sendPacket(LenovoWatchProtocol.buildCancelNotificationPacket(seq = seq++))
        }
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
