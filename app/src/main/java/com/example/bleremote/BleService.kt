package com.example.bleremote

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "BleService"
private const val ACTION_MAINTOGGLE = "com.example.bleremote.ACTION_MAINTOGGLE"
private const val ACTION_HAZARDTOGGLE = "com.example.bleremote.ACTION_HAZARDTOGGLE"
private const val ACTION_HORN = "com.example.bleremote.ACTION_HORN"
private const val ACTION_SEAT = "com.example.bleremote.ACTION_SEAT"

class BleService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null

    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState: StateFlow<String> = _connectionState

    private val _lastMessage = MutableStateFlow("")
    val lastMessage: StateFlow<String> = _lastMessage

    private val SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val RX_CHAR_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private val TX_CHAR_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val DEVICE_NAME = "ProMicro_Secure"

    private val _sessionKey = MutableStateFlow("")
    val sessionKey: StateFlow<String> = _sessionKey.asStateFlow()

    private val _isOn = MutableStateFlow(false)
    val isOn: StateFlow<Boolean> = _isOn.asStateFlow()

    private val _isHazard = MutableStateFlow(false)
    val isHazard: StateFlow<Boolean> = _isHazard.asStateFlow()

    // RSSI Proximity Auto-Off Configuration
    private var rssiPollingJob: Job? = null
    private var lowRssiCount = 0
    private val RSSI_THRESHOLD_3M = -78 // dBm threshold (~3 meters)
    private val REQUIRED_LOW_READINGS = 3 // Requires 3 consecutive weak readings (~6s) to avoid false triggers

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): BleService = this@BleService
    }

    @SuppressLint("MissingPermission")
    private fun startRssiPolling() {
        rssiPollingJob?.cancel()
        lowRssiCount = 0
        rssiPollingJob = serviceScope.launch {
            while (isActive && _connectionState.value == "Connected") {
                bluetoothGatt?.readRemoteRssi()
                delay(2000) // Read RSSI every 2 seconds
            }
        }
    }

    private fun stopRssiPolling() {
        rssiPollingJob?.cancel()
        rssiPollingJob = null
        lowRssiCount = 0
    }

    // ADDED: BroadcastReceiver to listen for Phone Bluetooth ON/OFF toggles
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_OFF -> {
                        Log.d(TAG, "Phone Bluetooth turned OFF")
                        _connectionState.value = "Bluetooth OFF"

                        // The Bluetooth stack is dead. We must clean up our dead objects.
                        bluetoothGatt?.close()
                        bluetoothGatt = null
                    }
                    BluetoothAdapter.STATE_ON -> {
                        Log.d(TAG, "Phone Bluetooth turned ON")
                        _connectionState.value = "Disconnected"

                        // Bluetooth is back! Re-initialize the adapter and restart scanning.
                        bluetoothAdapter = bluetoothManager?.adapter
                        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
                        startScanning()
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        // ADDED: Register the receiver to listen for Bluetooth state changes
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothStateReceiver, filter)

        // NEW: Automate notification updates based on connection state, power, and hazard
        serviceScope.launch {
            combine(_connectionState, _isOn, _isHazard) { state, _, _ ->
                state
            }.collect { state ->
                updateNotification(getNotificationText(state))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MAINTOGGLE -> {
                sendMessage("MAINTOGGLE")
                return START_STICKY
            }
            ACTION_HAZARDTOGGLE -> {
                sendMessage("HAZARDTOGGLE")
                return START_STICKY
            }
            ACTION_HORN -> {
                sendMessage("HORNON")
                return START_STICKY
            }
            ACTION_SEAT -> {
                sendMessage("SEAT")
                return START_STICKY
            }
        }

        startForegroundService()
        if (bluetoothGatt == null && bluetoothAdapter?.isEnabled == true) {
            startScanning()
        } else if (bluetoothAdapter?.isEnabled == false) {
            _connectionState.value = "Bluetooth OFF"
        }
        return START_STICKY
    }

    private fun buildNotification(contentText: String): Notification {
        val channelId = "ble_service_channel"

        val mainToggleIntent = Intent(this, BleService::class.java).apply {
            action = ACTION_MAINTOGGLE
        }
        val mainTogglePendingIntent = PendingIntent.getService(
            this,
            0,
            mainToggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val hazardToggleIntent = Intent(this, BleService::class.java).apply {
            action = ACTION_HAZARDTOGGLE
        }
        val hazardTogglePendingIntent = PendingIntent.getService(
            this,
            1,
            hazardToggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val hornIntent = Intent(this, BleService::class.java).apply {
            action = ACTION_HORN
        }
        val hornPendingIntent = PendingIntent.getService(
            this,
            2,
            hornIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val seatIntent = Intent(this, BleService::class.java).apply {
            action = ACTION_SEAT
        }
        val seatPendingIntent = PendingIntent.getService(
            this,
            3,
            seatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build Custom RemoteViews
        val remoteViews = RemoteViews(packageName, R.layout.notification_remote)

        // Colors (Matching the Soft / Dark UI logic)
        val colorActiveGreen = android.graphics.Color.parseColor("#81C784") // SoftGrass
        val colorWarningOrange = android.graphics.Color.parseColor("#FF8C00") // WarningOrange
        val colorInactive = android.graphics.Color.parseColor("#8A9A8A") // InactiveGrey

        if (_connectionState.value == "Connected") {
            // Set Dot to Green
            remoteViews.setInt(R.id.iv_status_dot, "setColorFilter", colorActiveGreen)

            // Set Power Button state
            remoteViews.setImageViewResource(R.id.btn_power, R.drawable.ic_power)
            remoteViews.setOnClickPendingIntent(R.id.btn_power, mainTogglePendingIntent)
            remoteViews.setInt(R.id.btn_power, "setColorFilter", if (_isOn.value) colorActiveGreen else colorInactive)

            // Set Hazard Button state
            remoteViews.setImageViewResource(R.id.btn_hazard, R.drawable.ic_hazard)
            remoteViews.setOnClickPendingIntent(R.id.btn_hazard, hazardTogglePendingIntent)
            remoteViews.setInt(R.id.btn_hazard, "setColorFilter", if (_isHazard.value) colorWarningOrange else colorInactive)

            // Set Horn & Seat (Static inactive color for now)
            remoteViews.setImageViewResource(R.id.btn_horn, R.drawable.ic_horn)
            remoteViews.setOnClickPendingIntent(R.id.btn_horn, hornPendingIntent)
            remoteViews.setInt(R.id.btn_horn, "setColorFilter", colorInactive)

            remoteViews.setImageViewResource(R.id.btn_seat, R.drawable.ic_chevron_up)
            remoteViews.setOnClickPendingIntent(R.id.btn_seat, seatPendingIntent)
            remoteViews.setInt(R.id.btn_seat, "setColorFilter", colorInactive)
            
            // Make actions visible
            remoteViews.setViewVisibility(R.id.action_container, android.view.View.VISIBLE)
        } else {
            // Set Dot to Gray
            remoteViews.setInt(R.id.iv_status_dot, "setColorFilter", colorInactive)
            
            // Hide actions when disconnected
            remoteViews.setViewVisibility(R.id.action_container, android.view.View.GONE)
        }

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(remoteViews)

        return builder.build()
    }

    private fun startForegroundService() {
        val channelId = "ble_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "BLE Remote Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = buildNotification(getNotificationText(_connectionState.value))
        startForeground(1, notification)
    }

    private fun getNotificationText(state: String): String {
        return when (state) {
            "Connected" -> "Connected to $DEVICE_NAME"
            "Scanning..." -> "Scanning for $DEVICE_NAME..."
            "Connecting..." -> "Connecting to $DEVICE_NAME..."
            "Disconnected" -> "Waiting for $DEVICE_NAME..."
            "Bluetooth OFF" -> "Bluetooth is disabled"
            "Connection Error" -> "Connection Error"
            "Scan Failed" -> "Scan failed, retrying..."
            else -> state
        }
    }

    private fun updateNotification(contentText: String) {
        val notification = buildNotification(contentText)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(1, notification)
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (_connectionState.value == "Connected" || _connectionState.value == "Connecting") return

        // Make sure Bluetooth is actually ON before we try to scan
        if (bluetoothAdapter?.isEnabled != true) {
            _connectionState.value = "Bluetooth OFF"
            return
        }

        _connectionState.value = "Scanning..."

        val filter = ScanFilter.Builder()
            .setDeviceName(DEVICE_NAME)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.d(TAG, "Found device: ${result.device.name}")
            bluetoothLeScanner?.stopScan(this)
            connectToDevice(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            _connectionState.value = "Scan Failed"
            serviceScope.launch {
                delay(5000)
                startScanning()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        _connectionState.value = "Connecting..."
        bluetoothGatt = device.connectGatt(this, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String, autoConnect: Boolean = true): Boolean {
        val adapter = bluetoothAdapter ?: return false
        if (!adapter.isEnabled) return false

        try {
            val device: BluetoothDevice = adapter.getRemoteDevice(address)
            bluetoothGatt = device.connectGatt(this, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
            return true
        } catch (exception: IllegalArgumentException) {
            Log.e(TAG, "Invalid MAC Address")
            return false
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS || status == 143) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    _connectionState.value = "Connected"
                    Log.d(TAG, "Connected to GATT server. Optimizing connection...")

                    // 1. Request High Priority (reduces interval from ~50ms to ~11ms)
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

                    // 2. Request larger MTU (allows full JSON packets in one go)
                    gatt.requestMtu(512)

                    gatt.discoverServices()
                    startRssiPolling()

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    _connectionState.value = "Disconnected"
                    Log.d(TAG, "Disconnected from GATT server. Waiting for auto-reconnect.")
                    stopRssiPolling()
                }
            } else {
                Log.e(TAG, "GATT Status Error: $status")
                _connectionState.value = "Connection Error"

                gatt.close()
                bluetoothGatt = null
                stopRssiPolling()

                serviceScope.launch {
                    delay(2000.milliseconds)
                    startScanning()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed to: $mtu, status: $status")
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Read RSSI: $rssi dBm (IsOn: ${_isOn.value}, LowRssiCount: $lowRssiCount)")
                if (_isOn.value) {
                    if (rssi < RSSI_THRESHOLD_3M) {
                        lowRssiCount++
                        Log.w(TAG, "Low RSSI detected ($rssi dBm < $RSSI_THRESHOLD_3M dBm). Count: $lowRssiCount/$REQUIRED_LOW_READINGS")
                        if (lowRssiCount >= REQUIRED_LOW_READINGS) {
                            Log.w(TAG, "Device out of range (~3 meters). Sending MAINTOGGLE to turn OFF.")
                            sendMessage("MAINTOGGLE")
                            lowRssiCount = 0
                        }
                    } else {
                        lowRssiCount = 0
                    }
                } else {
                    lowRssiCount = 0
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                val txChar = service?.getCharacteristic(TX_CHAR_UUID)
                if (txChar != null) {
                    gatt.setCharacteristicNotification(txChar, true)
                    val descriptor = txChar.getDescriptor(CCCD_UUID)
                    if (descriptor != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(descriptor)
                        }
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (descriptor.uuid == CCCD_UUID) {
                    Log.d(TAG, "Subscribed to TX notifications")
                    sendMessage("ACK")
                }
            }
        }

        // Modern onCharacteristicChanged (API 33+)
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == TX_CHAR_UUID) {
                val message = String(value).trim()
                if (message.isNotEmpty()) {
                    processIncomingMessage(message)
                }
            }
        }

        // Removed deprecated onCharacteristicChanged to prevent duplicate processing

        private fun processIncomingMessage(message: String) {
            if(message.startsWith("ACK_")) {
                _sessionKey.value = message.substringAfter("ACK_")
                return
            }

            if(message.startsWith("AUTH_")) {
                sendMessage("OK$message")
                return
            }

            if (message == "ON") {
                _isOn.value = true;
            }

            if (message == "OFF") {
                _isOn.value = false;
            }

            if (message == "HAZARDON") {
                _isHazard.value = true
            }

            if (message == "HAZARDOFF") {
                _isHazard.value = false;
            }

            Log.d(TAG, "Message Received: $message")
            _lastMessage.value += message + "\n"
        }
    }

    @SuppressLint("MissingPermission")
    fun sendMessage(message: String) {
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(SERVICE_UUID)
        val rxChar = service?.getCharacteristic(RX_CHAR_UUID)
        if (rxChar != null) {
            var bytes = message.toByteArray()

            if (message != "ACK" && !message.startsWith("OKAUTH_")) {
                bytes = (message + "_" + _sessionKey.value).toByteArray()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(rxChar, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                rxChar.value = bytes
                rxChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(rxChar)
            }
            Log.d(TAG, "Sent: $message")
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bluetoothStateReceiver)
        stopRssiPolling()
        serviceJob.cancel()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
    }
}