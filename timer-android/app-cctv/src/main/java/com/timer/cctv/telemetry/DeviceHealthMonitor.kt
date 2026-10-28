package com.timer.cctv.telemetry

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.timer.core.model.*

class DeviceHealthMonitor(private val context: Context) {

    fun collectHealth(
        isStandbyActive: Boolean,
        isForegroundServiceRunning: Boolean,
        isCameraActive: Boolean,
        isMicActive: Boolean,
        currentSessionMode: SessionMode,
        currentCameraFacing: CameraFacing,
        timerState: TimerState,
        remainingTimeMs: Long,
        errorMessage: String? = null
    ): DeviceHealth {
        val (batteryPct, isCharging) = getBatteryInfo()
        val isScreenInteractive = getScreenInteractiveState()
        val networkType = getNetworkType()
        val permissions = getPermissionsStatus()

        return DeviceHealth(
            batteryPercentage = batteryPct,
            isCharging = isCharging,
            isScreenInteractive = isScreenInteractive,
            networkType = networkType,
            isStandbyActive = isStandbyActive,
            isForegroundServiceRunning = isForegroundServiceRunning,
            isCameraActive = isCameraActive,
            isMicActive = isMicActive,
            currentSessionMode = currentSessionMode,
            currentCameraFacing = currentCameraFacing,
            timerState = timerState,
            remainingTimeMs = remainingTimeMs,
            permissions = permissions,
            lastHeartbeatTimestamp = System.currentTimeMillis(),
            errorMessage = errorMessage
        )
    }

    private fun getBatteryInfo(): Pair<Int, Boolean> {
        val batteryStatus: Intent? = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) {
            (level * 100 / scale.toFloat()).toInt()
        } else 100

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return Pair(batteryPct, isCharging)
    }

    private fun getScreenInteractiveState(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isInteractive ?: true
    }

    private fun getNetworkType(): NetworkType {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkType.NONE

        val activeNetwork = connectivityManager.activeNetwork ?: return NetworkType.NONE
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return NetworkType.NONE

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkType.VPN
            else -> NetworkType.NONE
        }
    }

    private fun getPermissionsStatus(): DevicePermissions {
        val hasCamera = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val hasAudio = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        return DevicePermissions(
            camera = hasCamera,
            audio = hasAudio,
            notifications = hasNotifications
        )
    }
}
