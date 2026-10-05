package com.example.bleremote

import android.content.*
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class BleViewModel : ViewModel() {

    private var bleService: BleService? = null

    private val _isServiceBound = MutableStateFlow(false)
    val isServiceBound: StateFlow<Boolean> = _isServiceBound.asStateFlow()

    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    private val _lastMessage = MutableStateFlow("")
    val lastMessage: StateFlow<String> = _lastMessage.asStateFlow()

    // ADDED: A list to hold your log history!
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _isOn = MutableStateFlow(false)
    val isOn: StateFlow<Boolean> = _isOn.asStateFlow()

    private val _isHazard = MutableStateFlow(false)
    val isHazard: StateFlow<Boolean> = _isHazard.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BleService.LocalBinder
            bleService = binder.getService()
            _isServiceBound.value = true

            viewModelScope.launch {
                bleService?.connectionState?.collect { _connectionState.value = it }
            }
            viewModelScope.launch {
                bleService?.isOn?.collect { _isOn.value = it }
            }
            viewModelScope.launch {
                bleService?.isHazard?.collect { _isHazard.value = it }
            }
            viewModelScope.launch {
                bleService?.lastMessage?.collect { newMsg ->
                    _lastMessage.value = newMsg

                    if (newMsg.isNotEmpty()) {
                        _logs.value = (_logs.value + newMsg).takeLast(50) // Keep the last 50 logs
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            _isServiceBound.value = false
        }
    }

    fun bindService(context: Context) {
        val intent = Intent(context, BleService::class.java)
        context.applicationContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun unbindService(context: Context) {
        if (_isServiceBound.value) {
            context.applicationContext.unbindService(serviceConnection)
            _isServiceBound.value = false
        }
    }

//    fun connectToDevice(deviceAddress: String, autoConnect: Boolean = true) {
//        bleService?.connect(deviceAddress, autoConnect)
//    }

    fun mainToggle() {
        bleService?.sendMessage("MAINTOGGLE")
    }

    fun hazardToggle() {
        bleService?.sendMessage("HAZARDTOGGLE")
    }

    fun start() {
        bleService?.sendMessage("START")
    }

    fun stop() {
        bleService?.sendMessage("STOP")
    }

    fun hornOn() {
        bleService?.sendMessage("HORNON")
    }

    fun hornOff() {
        bleService?.sendMessage("HORNOFF")
    }

    fun openSeat() {
        bleService?.sendMessage("SEAT")
    }
}