package com.timer.admin

import android.app.Application
import android.content.Context
import com.timer.core.network.TimerApiClient
import java.util.UUID

class AdminApplication : Application() {

    lateinit var apiClient: TimerApiClient
        private set

    lateinit var deviceId: String
        private set

    var pairedCctvDeviceId: String? = null
        private set

    var sharedSecret: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        val prefs = getSharedPreferences("timer_admin_prefs", Context.MODE_PRIVATE)
        deviceId = "admin_primary"
        pairedCctvDeviceId = "cctv_primary"
        sharedSecret = "timer_cctv_secret_key_2026"
        prefs.edit()
            .putString("admin_device_id", deviceId)
            .putString("paired_cctv_id", pairedCctvDeviceId)
            .putString("shared_secret", sharedSecret)
            .apply()

        val savedServerUrl = prefs.getString("backend_url", "https://timer-hutq.onrender.com") ?: "https://timer-hutq.onrender.com"
        apiClient = TimerApiClient(savedServerUrl)
    }

    fun savePairing(cctvId: String, secret: String) {
        pairedCctvDeviceId = cctvId
        sharedSecret = secret
        getSharedPreferences("timer_admin_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("paired_cctv_id", cctvId)
            .putString("shared_secret", secret)
            .apply()
    }

    fun clearPairing() {
        pairedCctvDeviceId = null
        sharedSecret = null
        getSharedPreferences("timer_admin_prefs", Context.MODE_PRIVATE)
            .edit()
            .remove("paired_cctv_id")
            .remove("shared_secret")
            .apply()
    }

    companion object {
        lateinit var instance: AdminApplication
            private set
    }
}
