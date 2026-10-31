package com.timer.cctv

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.timer.core.network.TimerApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

class TimerApplication : Application() {

    lateinit var apiClient: TimerApiClient
        private set

    lateinit var deviceId: String
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        val prefs = getSharedPreferences("timer_cctv_prefs", Context.MODE_PRIVATE)
        deviceId = "cctv_primary"
        if (!prefs.contains("standby_enabled")) {
            prefs.edit().putBoolean("standby_enabled", true).apply()
        }
        prefs.edit().putString("device_id", deviceId).apply()

        val savedServerUrl = prefs.getString("backend_url", "https://timer-hutq.onrender.com") ?: "https://timer-hutq.onrender.com"
        apiClient = TimerApiClient(savedServerUrl)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                apiClient.autoPair(cctvDeviceId = deviceId)
            } catch (ignored: Exception) {}
        }

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // 1. Timer Completion Channel (High importance with sound)
            val timerChannel = NotificationChannel(
                CHANNEL_TIMER_COMPLETION,
                "Timer Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when the countdown timer finishes"
                enableVibration(true)
            }

            // 2. CCTV Standby Channel (Low importance ongoing)
            val standbyChannel = NotificationChannel(
                CHANNEL_CCTV_STANDBY,
                "Timer Standby & Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing notification for remote standby and active monitoring"
                setShowBadge(false)
            }

            // 3. CCTV Stream Wake-up Channel (High importance with full-screen intent)
            val streamAlertChannel = NotificationChannel(
                CHANNEL_CCTV_STREAM_ALERT,
                "Live Stream Wakeup Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Wakes display and starts camera when remote live stream is requested"
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            notificationManager.createNotificationChannel(timerChannel)
            notificationManager.createNotificationChannel(standbyChannel)
            notificationManager.createNotificationChannel(streamAlertChannel)
        }
    }

    companion object {
        const val CHANNEL_TIMER_COMPLETION = "channel_timer_completion"
        const val CHANNEL_CCTV_STANDBY = "channel_cctv_standby"
        const val CHANNEL_CCTV_STREAM_ALERT = "channel_cctv_stream_alert"
        const val NOTIFICATION_ID_STANDBY = 2001
        const val NOTIFICATION_ID_TIMER_COMPLETE = 2002
        const val NOTIFICATION_ID_WAKE = 2003

        lateinit var instance: TimerApplication
            private set
    }
}
