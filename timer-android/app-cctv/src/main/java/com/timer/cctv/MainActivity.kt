package com.timer.cctv

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.timer.cctv.service.CctvStandbyService
import com.timer.cctv.ui.cctv.CctvSettingsSheet
import com.timer.cctv.ui.timer.TimerScreen
import com.timer.cctv.ui.timer.TimerViewModel
import com.timer.core.model.SessionMode
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val timerViewModel: TimerViewModel by viewModels()

    private var standbyService: CctvStandbyService? by mutableStateOf(null)
    private var isBound by mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CctvStandbyService.LocalBinder
            standbyService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            standbyService = null
            isBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permissions updated
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRequiredPermissions()

        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                var showSettingsSheet by remember { mutableStateOf(false) }

                val falseFlow = remember { MutableStateFlow(false) }
                val idleFlow = remember { MutableStateFlow(SessionMode.IDLE) }

                val service = standbyService
                val isStandbyActive by (service?.isStandbyActive ?: falseFlow).collectAsState()
                val isCameraActive by (service?.publisherManager?.isCameraActive ?: falseFlow).collectAsState()
                val isMicActive by (service?.publisherManager?.isMicActive ?: falseFlow).collectAsState()
                val sessionMode by (service?.publisherManager?.sessionMode ?: idleFlow).collectAsState()

                TimerScreen(
                    timerViewModel = timerViewModel,
                    isStandbyActive = isStandbyActive,
                    currentSessionMode = sessionMode,
                    isCameraActive = isCameraActive,
                    isMicActive = isMicActive,
                    onOpenCctvSettings = { showSettingsSheet = true }
                )

                if (showSettingsSheet) {
                    CctvSettingsSheet(
                        isStandbyActive = isStandbyActive,
                        currentSessionMode = sessionMode,
                        onDismiss = { showSettingsSheet = false }
                    )
                }
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, CctvStandbyService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
