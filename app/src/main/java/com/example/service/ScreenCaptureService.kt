package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.encoder.VideoEncoder
import com.example.network.SecureStreamServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScreenCaptureService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    
    private var encoder: VideoEncoder? = null
    private var server: SecureStreamServer? = null

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "NexusMirrorServiceChannel"
        private const val NOTIFICATION_ID = 101

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_FPS = "fps"
        const val EXTRA_PORT = "port"

        private val _serverState = MutableStateFlow(ServerState.IDLE)
        val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

        private val _fpsStats = MutableStateFlow(0)
        val fpsStats: StateFlow<Int> = _fpsStats.asStateFlow()

        private val _bitrateStats = MutableStateFlow(0L) // in Kbps
        val bitrateStats: StateFlow<Long> = _bitrateStats.asStateFlow()

        private val _connectedClients = MutableStateFlow(0)
        val connectedClients: StateFlow<Int> = _connectedClients.asStateFlow()

        private val _pairingCode = MutableStateFlow("")
        val pairingCode: StateFlow<String> = _pairingCode.asStateFlow()
    }

    enum class ServerState {
        IDLE,
        STARTING,
        RUNNING,
        STREAMING,
        ERROR
    }

    override fun onCreate() {
        super.onCreate()
        com.example.input.InputInjector.init(this)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val width = intent.getIntExtra(EXTRA_WIDTH, 1080)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 1920)
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 4_000_000) // 4 Mbps default
        val fps = intent.getIntExtra(EXTRA_FPS, 60)
        val port = intent.getIntExtra(EXTRA_PORT, 8080)

        if (resultCode == -1 || resultData == null) {
            Log.e(TAG, "Invalid projection intent result code or data.")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundServiceWithNotification()

        serviceScope.launch(Dispatchers.Default) {
            try {
                _serverState.value = ServerState.STARTING
                
                // Initialize Secure Stream TCP/WebSocket Server
                server = SecureStreamServer(
                    port = port,
                    onClientConnected = { count ->
                        _connectedClients.value = count
                        if (count > 0 && _serverState.value == ServerState.RUNNING) {
                            _serverState.value = ServerState.STREAMING
                        } else if (count == 0 && _serverState.value == ServerState.STREAMING) {
                            _serverState.value = ServerState.RUNNING
                        }
                    },
                    onPairingGenerated = { code ->
                        _pairingCode.value = code
                    }
                )
                server?.start()

                // Initialize Hardware Video Encoder
                encoder = VideoEncoder(
                    width = width,
                    height = height,
                    bitrate = bitrate,
                    fps = fps,
                    onEncodedFrame = { data, flags, timestampUs ->
                        // Send encoded Annex-B H.264 frames over TCP/Secure network sockets
                        server?.broadcastVideoFrame(data, flags, timestampUs)
                    },
                    onStatsUpdate = { currentFps, currentBitrateKbps ->
                        _fpsStats.value = currentFps
                        _bitrateStats.value = currentBitrateKbps
                    }
                )
                encoder?.start()

                // Acquire Media Projection Screen Capture permission
                val projection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
                if (projection == null) {
                    throw IllegalStateException("Failed to acquire Media Projection session.")
                }
                mediaProjection = projection

                // Register projection callback for teardown detection
                projection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.i(TAG, "MediaProjection stopped.")
                        stopSelf()
                    }
                }, null)

                // Create Virtual Display projecting screen into MediaCodec surface input
                val surface = encoder?.inputSurface ?: throw IllegalStateException("Encoder Input Surface is null")
                
                virtualDisplay = projection.createVirtualDisplay(
                    "NexusMirrorDisplay",
                    width,
                    height,
                    resources.displayMetrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    null
                )

                _serverState.value = ServerState.RUNNING
                Log.i(TAG, "Nexus Stream Server actively running on port $port")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start streaming services", e)
                _serverState.value = ServerState.ERROR
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val notification = createNotification("Nexus Screen Server is active and listening...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nexus Mirror Core")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Nexus Mirror Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows screen mirroring active connection status."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Tearing down Nexus Server resources...")
        
        _serverState.value = ServerState.IDLE
        _fpsStats.value = 0
        _bitrateStats.value = 0
        _connectedClients.value = 0
        _pairingCode.value = ""

        virtualDisplay?.release()
        virtualDisplay = null

        encoder?.stop()
        encoder = null

        server?.stop()
        server = null

        mediaProjection?.stop()
        mediaProjection = null

        serviceScope.cancel()
        super.onDestroy()
    }
}
