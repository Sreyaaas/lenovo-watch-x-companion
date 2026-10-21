package com.lenovo.watchx.companion

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log

class LenovoWatchBleService : Service() {

    companion object {
        const val TAG = "LenovoWatchBleService"
        const val CHANNEL_ID = "lenovo_watch_service_channel"
        const val NOTIFICATION_ID = 1001

        var instance: LenovoWatchBleService? = null
            private set
    }

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    private var seq = 1
    var isConnected = false
        private set

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
                "Lenovo Watch BLE Connection",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Lenovo Watch X Connected")
                .setContentText("Listening for alerts and notifications")
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("Lenovo Watch X Connected")
                .setContentText("Listening for alerts and notifications")
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .build()
        }

        startForeground(NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission")
    fun connect(macAddress: String): Boolean {
        val device = bluetoothAdapter?.getRemoteDevice(macAddress) ?: return false
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        return true
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        isConnected = false
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to Lenovo Watch X. Discovering services...")
                bluetoothGatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "Disconnected from Lenovo Watch X.")
                isConnected = false
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt?.getService(LenovoWatchProtocol.UUID_SERVICE)
                if (service != null) {
                    writeCharacteristic = service.getCharacteristic(LenovoWatchProtocol.UUID_WRITE)
                    notifyCharacteristic = service.getCharacteristic(LenovoWatchProtocol.UUID_NOTIFY)

                    // Enable notifications on 0000a802
                    notifyCharacteristic?.let { char ->
                        gatt.setCharacteristicNotification(char, true)
                        val descriptor = char.getDescriptor(LenovoWatchProtocol.UUID_CCCD)
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        }
                    }

                    isConnected = true
                    Log.i(TAG, "Lenovo Watch X BLE handshake ready.")
                    // Initial Auth & clock sync
                    sendPacket(LenovoWatchProtocol.buildAuthPacket(seq++))
                    sendPacket(LenovoWatchProtocol.buildSetTimePacket(seq = seq++))
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            val value = characteristic?.value ?: return
            Log.d(TAG, "Notification received from watch: ${value.joinToString(" ") { "%02X".format(it) }}")
        }
    }

    @SuppressLint("MissingPermission")
    fun sendPacket(packet: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val char = writeCharacteristic ?: return
        char.value = packet
        gatt.writeCharacteristic(char)
    }

    fun pushNotification(title: String, body: String, channel: Int = 7) {
        if (!isConnected) return
        val packet = LenovoWatchProtocol.buildNotificationPacket(title, body, channel, seq++)
        sendPacket(packet)
    }

    fun syncTime() {
        if (!isConnected) return
        val packet = LenovoWatchProtocol.buildSetTimePacket(seq = seq++)
        sendPacket(packet)
    }

    fun calibrateHands(hour: Int, minute: Int) {
        if (!isConnected) return
        // 1. Enter calibration mode
        sendPacket(LenovoWatchProtocol.buildPacket(byteArrayOf(0x03, 0x31, 0x01), LenovoWatchProtocol.ACTION_TASK, seq++))
        Thread.sleep(200)
        // 2. Set hands position
        sendPacket(LenovoWatchProtocol.buildCalibrationPositionPacket(hour, minute, seq++))
        Thread.sleep(300)
        // 3. Sync real time
        syncTime()
        Thread.sleep(200)
        // 4. Exit calibration mode
        sendPacket(LenovoWatchProtocol.buildPacket(byteArrayOf(0x03, 0x31, 0x00), LenovoWatchProtocol.ACTION_TASK, seq++))
    }
}
