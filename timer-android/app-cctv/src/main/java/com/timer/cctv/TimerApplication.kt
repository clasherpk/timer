package com.timer.cctv

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.timer.core.network.TimerApiClient
import java.util.UUID

class TimerApplication : Application() {

    lateinit var apiClient: TimerApiClient
        private set

    lateinit var deviceId: String
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Retrieve or generate persistent device UUID
        val prefs = getSharedPreferences("timer_cctv_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        deviceId = id

        val savedServerUrl = prefs.getString("backend_url", "http://10.0.2.2:10000") ?: "http://10.0.2.2:10000"
        apiClient = TimerApiClient(savedServerUrl)

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

            notificationManager.createNotificationChannel(timerChannel)
            notificationManager.createNotificationChannel(standbyChannel)
        }
    }

    companion object {
        const val CHANNEL_TIMER_COMPLETION = "channel_timer_completion"
        const val CHANNEL_CCTV_STANDBY = "channel_cctv_standby"
        const val NOTIFICATION_ID_STANDBY = 2001
        const val NOTIFICATION_ID_TIMER_COMPLETE = 2002

        lateinit var instance: TimerApplication
            private set
    }
}
