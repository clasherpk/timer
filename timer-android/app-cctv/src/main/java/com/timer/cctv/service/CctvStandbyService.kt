package com.timer.cctv.service

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.timer.cctv.MainActivity
import com.timer.cctv.R
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

    // Standby locks to keep CPU and WiFi awake with screen off
    private var standbyWakeLock: PowerManager.WakeLock? = null
    private var standbyWifiLock: WifiManager.WifiLock? = null

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

        val prefs = getSharedPreferences("timer_cctv_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("standby_enabled", false)) {
            _isStandbyActive.value = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("timer_cctv_prefs", Context.MODE_PRIVATE)
        val action = intent?.action ?: if (prefs.getBoolean("standby_enabled", false)) ACTION_START_STANDBY else null

        when (action) {
            ACTION_START_STANDBY -> {
                prefs.edit().putBoolean("standby_enabled", true).apply()
                startStandby()
            }
            ACTION_STOP_STANDBY -> {
                prefs.edit().putBoolean("standby_enabled", false).apply()
                stopStandby()
            }
            ACTION_STOP_SESSION -> {
                publisherManager.stopSession()
                updateForegroundService(isStreaming = false)
                try {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(TimerApplication.NOTIFICATION_ID_WAKE)
                } catch (ignored: Exception) {}
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val prefs = getSharedPreferences("timer_cctv_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("standby_enabled", false)) {
            scheduleServiceRestart()
        }
    }

    private fun scheduleServiceRestart() {
        try {
            val restartServiceIntent = Intent(applicationContext, CctvStandbyService::class.java).apply {
                action = ACTION_START_STANDBY
            }
            val restartPending = PendingIntent.getService(
                applicationContext,
                1001,
                restartServiceIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1000,
                restartPending
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun acquireStandbyLocks() {
        try {
            if (standbyWakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                standbyWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Timer:StandbyWakeLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (standbyWakeLock?.isHeld == false) {
                standbyWakeLock?.acquire()
            }

            if (standbyWifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                standbyWifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Timer:StandbyWifiLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (standbyWifiLock?.isHeld == false) {
                standbyWifiLock?.acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseStandbyLocks() {
        try {
            standbyWakeLock?.let { if (it.isHeld) it.release() }
            standbyWakeLock = null
            standbyWifiLock?.let { if (it.isHeld) it.release() }
            standbyWifiLock = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startStandby() {
        _isStandbyActive.value = true
        acquireStandbyLocks()
        promoteToForeground(isStreaming = false)
        startHeartbeatLoop()
        startWebSocketSignaling()
    }

    private fun stopStandby() {
        _isStandbyActive.value = false
        publisherManager.stopSession()
        heartbeatJob?.cancel()
        webSocketJob?.cancel()
        releaseStandbyLocks()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stopSelf()
    }

    private fun promoteToForeground(isStreaming: Boolean) {
        try {
            val notification = buildNotification(isStreaming)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (isStreaming && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val hasCamera = ContextCompat.checkSelfPermission(
                        this, Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    val hasMic = ContextCompat.checkSelfPermission(
                        this, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                    if (hasCamera) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    if (hasMic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                ServiceCompat.startForeground(
                    this,
                    TimerApplication.NOTIFICATION_ID_STANDBY,
                    notification,
                    type
                )
            } else {
                startForeground(TimerApplication.NOTIFICATION_ID_STANDBY, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(TimerApplication.NOTIFICATION_ID_STANDBY, buildNotification(isStreaming))
            } catch (ignored: Exception) {}
        }
    }

    private fun updateForegroundService(isStreaming: Boolean) {
        promoteToForeground(isStreaming)
    }

    private fun wakeUpAndBringAppToForeground() {
        try {
            // 1. Wake CPU and turn screen on
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null) {
                @Suppress("DEPRECATION")
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "Timer:LiveStreamWake"
                )
                wakeLock.acquire(15_000L)
            }

            // 2. Prepare Intent to launch MainActivity over lockscreen
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("EXTRA_FROM_REMOTE_STREAM", true)
            }

            try {
                startActivity(launchIntent)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 3. Post high-priority full-screen intent notification on CHANNEL_CCTV_STREAM_ALERT
            val fullScreenPending = PendingIntent.getActivity(
                this,
                TimerApplication.NOTIFICATION_ID_WAKE,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val wakeNotification = NotificationCompat.Builder(this, TimerApplication.CHANNEL_CCTV_STREAM_ALERT)
                .setSmallIcon(R.drawable.ic_notification_camera)
                .setContentTitle("Live Monitoring Active")
                .setContentText("Camera stream connected.")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
                .setFullScreenIntent(fullScreenPending, true)
                .setAutoCancel(true)
                .build()

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(TimerApplication.NOTIFICATION_ID_WAKE, wakeNotification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
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

            // Auto-register this device on backend so Admin pairs automatically
            try {
                app.apiClient.autoPair(cctvDeviceId = app.deviceId)
            } catch (ignored: Exception) {}

            val wsUrl = app.apiClient.getBaseUrl().replace("http://", "ws://").replace("https://", "wss://") + "/ws"

            while (isActive && _isStandbyActive.value) {
                try {
                    app.apiClient.httpClient.webSocket(urlString = wsUrl) {
                        // 1. Identify to signaling hub
                        val identifyMsg = """{"type":"IDENTIFY","deviceId":"${app.deviceId}"}"""
                        send(Frame.Text(identifyMsg))

                        // 2. Launch background ping loop to keep socket connection hot through deep sleep
                        val pingJob = launch {
                            while (isActive) {
                                delay(15_000L)
                                try {
                                    send(Frame.Text("""{"type":"PING"}"""))
                                } catch (e: Exception) {
                                    break
                                }
                            }
                        }

                        try {
                            // 3. Incoming messages listener
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    handleIncomingCommand(text, this)
                                }
                            }
                        } finally {
                            pingJob.cancel()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(3000L) // retry backoff
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

                        // 1. Wake phone and bring app to foreground
                        wakeUpAndBringAppToForeground()
                        delay(750L)

                        // 2. Elevate foreground service
                        updateForegroundService(isStreaming = true)

                        // 3. Start LiveKit session with retry
                        val success = publisherManager.startSession(grant)
                        if (!success) {
                            updateForegroundService(isStreaming = false)
                        }

                        // 4. Dismiss full-screen alert
                        try {
                            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            nm.cancel(TimerApplication.NOTIFICATION_ID_WAKE)
                        } catch (ignored: Exception) {}
                    }
                }
                "STOP_SESSION" -> {
                    publisherManager.stopSession()
                    updateForegroundService(isStreaming = false)
                    try {
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.cancel(TimerApplication.NOTIFICATION_ID_WAKE)
                    } catch (ignored: Exception) {}
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

    private fun buildNotification(isStreaming: Boolean): Notification {
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

        val iconRes = if (isStreaming) R.drawable.ic_notification_camera else R.drawable.ic_notification_timer

        val builder = NotificationCompat.Builder(this, TimerApplication.CHANNEL_CCTV_STANDBY)
            .setSmallIcon(iconRes)
            .setContentTitle(if (isStreaming) "Live Monitoring Active" else "Timer Remote Standby")
            .setContentText(
                if (isStreaming) "Camera/Microphone is streaming on-demand."
                else "Ready for authorized remote access. Sensors are idle."
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingOpen)

        if (isStreaming) {
            builder.addAction(R.drawable.ic_stop, "Stop Live Session", pendingStopSession)
        }

        return builder.build()
    }

    override fun onDestroy() {
        super.onDestroy()
        val prefs = getSharedPreferences("timer_cctv_prefs", Context.MODE_PRIVATE)
        val shouldKeepRunning = prefs.getBoolean("standby_enabled", false)
        if (shouldKeepRunning) {
            scheduleServiceRestart()
        } else {
            publisherManager.stopSession()
            heartbeatJob?.cancel()
            webSocketJob?.cancel()
            releaseStandbyLocks()
        }
        serviceScope.cancel()
    }

    companion object {
        const val ACTION_START_STANDBY = "com.timer.cctv.START_STANDBY"
        const val ACTION_STOP_STANDBY = "com.timer.cctv.STOP_STANDBY"
        const val ACTION_STOP_SESSION = "com.timer.cctv.STOP_SESSION"
    }
}
