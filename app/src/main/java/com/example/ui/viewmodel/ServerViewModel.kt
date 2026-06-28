package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.service.ScreenCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

class ServerViewModel : ViewModel() {

    private val TAG = "ServerViewModel"

    // Configuration Settings State
    private val _port = MutableStateFlow(8080)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _width = MutableStateFlow(1080)
    val width: StateFlow<Int> = _width.asStateFlow()

    private val _height = MutableStateFlow(1920)
    val height: StateFlow<Int> = _height.asStateFlow()

    private val _bitrate = MutableStateFlow(6_000_000) // Default 6 Mbps
    val bitrate: StateFlow<Int> = _bitrate.asStateFlow()

    private val _fps = MutableStateFlow(60)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    private val _localIp = MutableStateFlow("Unknown")
    val localIp: StateFlow<String> = _localIp.asStateFlow()

    // Bindings to Service Stats Flows
    val serverState: StateFlow<ScreenCaptureService.ServerState> = ScreenCaptureService.serverState
    val liveFps: StateFlow<Int> = ScreenCaptureService.fpsStats
    val liveBitrateKbps: StateFlow<Long> = ScreenCaptureService.bitrateStats
    val connectedClients: StateFlow<Int> = ScreenCaptureService.connectedClients
    val pairingCode: StateFlow<String> = ScreenCaptureService.pairingCode

    // Combined connection string for easier scanning (e.g. "192.168.1.15:8080:pairingCode")
    val connectionUri: StateFlow<String> = combine(_localIp, _port, pairingCode) { ip, port, code ->
        if (ip != "Unknown" && code.isNotEmpty()) {
            "NEXUS://_HOST=$ip&_PORT=$port&_KEY=$code"
        } else {
            ""
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    init {
        refreshNetworkIp()
    }

    fun setPort(value: Int) {
        _port.value = value
    }

    fun setResolution(w: Int, h: Int) {
        _width.value = w
        _height.value = h
    }

    fun setBitrate(value: Int) {
        _bitrate.value = value
    }

    fun setFps(value: Int) {
        _fps.value = value
    }

    fun refreshNetworkIp() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ip = fetchLocalIpAddress()
                _localIp.value = ip ?: "Offline"
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve network IP address", e)
                _localIp.value = "Unavailable"
            }
        }
    }

    private fun fetchLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val netInterface = interfaces.nextElement()
                // Skip loopback interfaces, virtual tunnels, or interfaces that are down
                if (netInterface.isLoopback || !netInterface.isUp) continue

                val addresses = netInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val hostAddress = address.hostAddress
                        // Ensure we fetch standard IPv4 addresses (ignoring local/link-local placeholders if unnecessary)
                        if (!hostAddress.isNullOrEmpty()) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error iterating through network interface sockets", e)
        }
        return null
    }

    /**
     * Starts the Foreground streaming and encoding service.
     */
    fun startMirroringServer(context: Context, resultCode: Int, data: Intent) {
        val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCaptureService.EXTRA_WIDTH, _width.value)
            putExtra(ScreenCaptureService.EXTRA_HEIGHT, _height.value)
            putExtra(ScreenCaptureService.EXTRA_BITRATE, _bitrate.value)
            putExtra(ScreenCaptureService.EXTRA_FPS, _fps.value)
            putExtra(ScreenCaptureService.EXTRA_PORT, _port.value)
        }
        
        try {
            context.startService(serviceIntent)
            Log.i(TAG, "Successfully invoked startService on ScreenCaptureService.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ScreenCaptureService foreground execution context", e)
        }
    }

    /**
     * Stops the Screen Mirroring server.
     */
    fun stopMirroringServer(context: Context) {
        val serviceIntent = Intent(context, ScreenCaptureService::class.java)
        context.stopService(serviceIntent)
        Log.i(TAG, "Successfully invoked stopService on ScreenCaptureService.")
    }
}
