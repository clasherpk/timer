package com.timer.cctv.ui.cctv

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.timer.cctv.TimerApplication
import com.timer.cctv.service.CctvStandbyService
import com.timer.core.model.SessionMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CctvSettingsSheet(
    isStandbyActive: Boolean,
    currentSessionMode: SessionMode,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val app = TimerApplication.instance

    var serverUrl by remember {
        val prefs = context.getSharedPreferences("timer_cctv_prefs", Context.MODE_PRIVATE)
        mutableStateOf(prefs.getString("backend_url", "https://timer-hutq.onrender.com") ?: "https://timer-hutq.onrender.com")
    }

    var pairingCode by remember { mutableStateOf<String?>(null) }
    var pairingLoading by remember { mutableStateOf(false) }
    var pairingError by remember { mutableStateOf<String?>(null) }

    // Permissions check
    var missingPermissions by remember { mutableStateOf<List<String>>(emptyList()) }

    fun refreshMissingPermissions() {
        val list = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            list.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            list.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                list.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        missingPermissions = list
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshMissingPermissions()
    }

    LaunchedEffect(Unit) {
        refreshMissingPermissions()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CCTV & Standby Settings",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            // Permissions Alert Banner (if any missing)
            if (missingPermissions.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFF856404))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Permissions Needed",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF856404)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Camera, microphone, and notification permissions are required for remote standby and streaming.",
                            fontSize = 12.sp,
                            color = Color(0xFF856404)
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                permissionLauncher.launch(missingPermissions.toTypedArray())
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF856404))
                        ) {
                            Text("Grant Permissions", color = Color.White)
                        }
                    }
                }
            }

            // 1. Standby Toggle
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isStandbyActive) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Remote-Ready Standby",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (isStandbyActive) "Running persistent standby service. Awaiting admin connection."
                            else "Standby is disabled. Phone cannot be reached remotely.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isStandbyActive,
                        onCheckedChange = { enable ->
                            if (enable && missingPermissions.isNotEmpty()) {
                                permissionLauncher.launch(missingPermissions.toTypedArray())
                            }

                            try {
                                val intent = Intent(context, CctvStandbyService::class.java).apply {
                                    action = if (enable) CctvStandbyService.ACTION_START_STANDBY
                                    else CctvStandbyService.ACTION_STOP_STANDBY
                                }
                                if (enable) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(intent)
                                    } else {
                                        context.startService(intent)
                                    }
                                } else {
                                    context.stopService(intent)
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                Toast.makeText(
                                    context,
                                    "Failed to toggle standby: ${e.localizedMessage}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    )
                }
            }

            // 2. Active Session Banner & Stop Button
            if (currentSessionMode != SessionMode.IDLE) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFD32F2F).copy(alpha = 0.1f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                        Text(
                            text = "ACTIVE LIVE SESSION: $currentSessionMode",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD32F2F)
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val intent = Intent(context, CctvStandbyService::class.java).apply {
                                    action = CctvStandbyService.ACTION_STOP_SESSION
                                }
                                context.startService(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                        ) {
                            Text("Stop Live Stream Now", color = Color.White)
                        }
                    }
                }
            }

            // 3. Backend URL Configuration
            OutlinedTextField(
                value = serverUrl,
                onValueChange = {
                    serverUrl = it
                    app.apiClient.updateBaseUrl(it)
                    context.getSharedPreferences("timer_cctv_prefs", Context.MODE_PRIVATE)
                        .edit().putString("backend_url", it).apply()
                },
                label = { Text("Backend Server URL") },
                placeholder = { Text("https://your-service.onrender.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // 4. Pairing Code Generator
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(
                        text = "Device Pairing",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Generate a one-time code to link this phone with Timer Admin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(12.dp))

                    if (pairingCode != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("PAIRING CODE", style = MaterialTheme.typography.labelSmall)
                                Text(
                                    text = pairingCode!!,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 4.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text("Valid for 10 minutes", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    if (pairingError != null) {
                        Text(pairingError!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                pairingLoading = true
                                pairingError = null
                                val result = app.apiClient.generatePairingCode(app.deviceId)
                                pairingLoading = false
                                result.onSuccess {
                                    pairingCode = it.pairingCode
                                }.onFailure {
                                    pairingError = it.message ?: "Failed to generate pairing code"
                                }
                            }
                        },
                        enabled = !pairingLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (pairingLoading) "Generating..." else "Generate Pairing Code")
                    }
                }
            }

            // 5. Battery Optimization Notice
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isIgnoringBattery = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
            if (!isIgnoringBattery) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Battery Optimization Warning",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE65100),
                                fontSize = 14.sp
                            )
                            Text(
                                "Android may sleep or delay standby commands if battery optimization is enabled.",
                                color = Color(0xFFE65100),
                                fontSize = 12.sp
                            )
                        }
                        TextButton(onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }) {
                            Text("Disable", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
