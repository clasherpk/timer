package com.timer.cctv.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.timer.cctv.MainActivity
import com.timer.cctv.TimerApplication
import com.timer.cctv.media.LiveKitPublisherManager
import com.timer.cctv.telemetry.DeviceHealthMonitor
import com.timer.core.model.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CctvStandbyService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    lateinit var healthMonitor: DeviceHealthMonitor
        private set

    lateinit var publisherManager: LiveKitPublisherManager
        private set

    private var heartbeatJob: Job? = null
    private var webSocketJob: Job? = null

    private val _isStandbyActive = MutableStateFlow(false)
    val isStandbyActive: StateFlow<Boolean> = _isStandbyActive.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    inner class LocalBinder : Binder() {
        fun getService(): CctvStandbyService = this@CctvStandbyService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        healthMonitor = DeviceHealthMonitor(this)
        publisherManager = LiveKitPublisherManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_STANDBY -> {
                startStandby()
            }
            ACTION_STOP_STANDBY -> {
                stopStandby()
            }
            ACTION_STOP_SESSION -> {
                publisherManager.stopSession()
                updateNotification(false)
            }
        }
        return START_STICKY
    }

    private fun startStandby() {
        _isStandbyActive.value = true
        startForeground(TimerApplication.NOTIFICATION_ID_STANDBY, buildNotification(isStreaming = false))

        startHeartbeatLoop()
        startWebSocketSignaling()
    }

    private fun stopStandby() {
        _isStandbyActive.value = false
        publisherManager.stopSession()
        heartbeatJob?.cancel()
        webSocketJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            val app = TimerApplication.instance
            while (isActive && _isStandbyActive.value) {
                try {
                    val health = healthMonitor.collectHealth(
                        isStandbyActive = true,
                        isForegroundServiceRunning = true,
                        isCameraActive = publisherManager.isCameraActive.value,
                        isMicActive = publisherManager.isMicActive.value,
                        currentSessionMode = publisherManager.sessionMode.value,
                        currentCameraFacing = publisherManager.currentFacing.value,
                        timerState = TimerState.IDLE,
                        remainingTimeMs = 0L
                    )
                    app.apiClient.sendHeartbeat(app.deviceId, health)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(30_000L) // 30-second heartbeat
            }
        }
    }

    private fun startWebSocketSignaling() {
        webSocketJob?.cancel()
        webSocketJob = serviceScope.launch {
            val app = TimerApplication.instance
            val wsUrl = app.apiClient.getBaseUrl().replace("http://", "ws://").replace("https://", "wss://") + "/ws"

            while (isActive && _isStandbyActive.value) {
                try {
                    app.apiClient.httpClient.webSocket(urlString = wsUrl) {
                        // 1. Identify to signaling hub
                        val identifyMsg = """{"type":"IDENTIFY","deviceId":"${app.deviceId}"}"""
                        send(Frame.Text(identifyMsg))

                        // 2. Incoming messages listener
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                handleIncomingCommand(text, this)
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(5000L) // retry backoff
            }
        }
    }

    private suspend fun handleIncomingCommand(message: String, session: WebSocketSession) {
        try {
            val root = json.parseToJsonElement(message).jsonObject
            val type = root["type"]?.jsonPrimitive?.content ?: root["commandType"]?.jsonPrimitive?.content

            when (type) {
                "START_SESSION" -> {
                    val grantElement = root["grant"]
                    if (grantElement != null) {
                        val grant = json.decodeFromJsonElement(LiveKitSessionGrant.serializer(), grantElement)
                        val success = publisherManager.startSession(grant)
                        updateNotification(isStreaming = success)
                    }
                }
                "STOP_SESSION" -> {
                    publisherManager.stopSession()
                    updateNotification(isStreaming = false)
                }
                "SWITCH_CAMERA" -> {
                    val newFacing = publisherManager.switchCamera()
                    val commandId = root["commandId"]?.jsonPrimitive?.content ?: "cmd_${System.currentTimeMillis()}"
                    val confirmation = CommandConfirmation(
                        commandId = commandId,
                        commandType = CommandType.SWITCH_CAMERA,
                        success = true,
                        executedAt = System.currentTimeMillis(),
                        newCameraFacing = newFacing
                    )
                    session.send(Frame.Text(json.encodeToString(CommandConfirmation.serializer(), confirmation)))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateNotification(isStreaming: Boolean) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(TimerApplication.NOTIFICATION_ID_STANDBY, buildNotification(isStreaming))
    }

    private fun buildNotification(isStreaming: Boolean): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopSessionIntent = Intent(this, CctvStandbyService::class.java).apply {
            action = ACTION_STOP_SESSION
        }
        val pendingStopSession = PendingIntent.getService(
            this,
            1,
            stopSessionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, TimerApplication.CHANNEL_CCTV_STANDBY)
            .setSmallIcon(if (isStreaming) android.R.drawable.presence_video_online else android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (isStreaming) "Live Monitoring Active" else "Timer Remote Standby")
            .setContentText(
                if (isStreaming) "Camera/Microphone is streaming on-demand."
                else "Ready for authorized remote access. Sensors are idle."
            )
            .setOngoing(true)
            .setContentIntent(pendingOpen)

        if (isStreaming) {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Live Session", pendingStopSession)
        }

        return builder.build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStandby()
        serviceScope.cancel()
    }

    companion object {
        const val ACTION_START_STANDBY = "com.timer.cctv.START_STANDBY"
        const val ACTION_STOP_STANDBY = "com.timer.cctv.STOP_STANDBY"
        const val ACTION_STOP_SESSION = "com.timer.cctv.STOP_SESSION"
    }
}
