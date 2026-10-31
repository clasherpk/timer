package com.timer.admin.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.timer.core.model.DeviceStatusCategory
import com.timer.core.model.SessionMode
import io.livekit.android.renderer.SurfaceViewRenderer
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: AdminViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val videoTrack by viewModel.subscriberManager.videoTrack.collectAsState()
    val isAudioActive by viewModel.subscriberManager.isAudioActive.collectAsState()

    var showUrlDialog by remember { mutableStateOf(false) }
    var tempUrl by remember { mutableStateOf(uiState.serverUrl) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Timer Admin", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        tempUrl = uiState.serverUrl
                        showUrlDialog = true
                    }) {
                        Icon(Icons.Default.Dns, contentDescription = "Server URL")
                    }
                    if (uiState.isPaired) {
                        IconButton(onClick = { viewModel.unpair() }) {
                            Icon(Icons.Default.LinkOff, contentDescription = "Unpair Device")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Error Snackbar Banner
            AnimatedVisibility(visible = uiState.errorMessage != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = uiState.errorMessage ?: "",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.clearError() }) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss")
                        }
                    }
                }
            }

            if (!uiState.isPaired) {
                // Pairing Card
                PairingCard(
                    onPair = { code -> viewModel.pairDevice(code) },
                    onAutoPair = { viewModel.attemptAutoPair() },
                    isLoading = uiState.isLoading
                )
            } else {
                // Device Health Dashboard Card
                HealthDashboardCard(
                    status = uiState.statusCategory,
                    lastSeenAt = uiState.lastSeenAt,
                    health = uiState.health,
                    onRefresh = { viewModel.fetchStatusOnce() }
                )

                // Live Stream Viewer or Mode Trigger Card
                if (uiState.activeSessionMode != SessionMode.IDLE) {
                    ActiveLiveSessionCard(
                        mode = uiState.activeSessionMode,
                        videoTrack = videoTrack,
                        isAudioActive = isAudioActive,
                        onInitRenderer = { renderer -> viewModel.subscriberManager.initRenderer(renderer) },
                        onSwitchCamera = { viewModel.switchCamera() },
                        onStopSession = { viewModel.stopSession() }
                    )
                } else {
                    SessionLauncherCard(
                        statusCategory = uiState.statusCategory,
                        isLoading = uiState.isLoading,
                        onStartCameraOnly = { viewModel.startSession(SessionMode.CAMERA_ONLY) },
                        onStartAudioOnly = { viewModel.startSession(SessionMode.AUDIO_ONLY) },
                        onStartCombined = { viewModel.startSession(SessionMode.CAMERA_AND_AUDIO) }
                    )
                }
            }
        }
    }

    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("Backend Server URL") },
            text = {
                OutlinedTextField(
                    value = tempUrl,
                    onValueChange = { tempUrl = it },
                    label = { Text("URL") },
                    placeholder = { Text("https://your-service.onrender.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.updateServerUrl(tempUrl)
                    showUrlDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun PairingCard(
    onPair: (String) -> Unit,
    onAutoPair: () -> Unit,
    isLoading: Boolean
) {
    var code by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.PhoneAndroid, contentDescription = "Pairing", tint = MaterialTheme.colorScheme.primary)
                Text("Pair with Spare Phone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(
                "Auto-pairing links your two personal phones automatically when both apps are installed. You can also manually enter a 6-letter pairing code.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = onAutoPair,
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (isLoading) "Connecting..." else "Auto-Link With Spare Phone")
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Text("  OR USE CODE  ", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(modifier = Modifier.weight(1f))
            }

            OutlinedTextField(
                value = code,
                onValueChange = { if (it.length <= 6) code = it.uppercase() },
                label = { Text("6-Letter Pairing Code") },
                placeholder = { Text("e.g. 7K9M2P") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp
                )
            )

            OutlinedButton(
                onClick = { onPair(code.trim()) },
                enabled = code.length == 6 && !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Submit Code")
            }
        }
    }
}

@Composable
fun HealthDashboardCard(
    status: DeviceStatusCategory,
    lastSeenAt: Long,
    health: com.timer.core.model.DeviceHealth?,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Spare Phone Telemetry", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
                }
            }

            // Status Pill
            val (statusText, statusBg, statusColor) = when (status) {
                DeviceStatusCategory.ONLINE_READY -> Triple("ONLINE & READY", Color(0xFF2E7D32), Color.White)
                DeviceStatusCategory.SESSION_ACTIVE -> Triple("SESSION ACTIVE", Color(0xFF1976D2), Color.White)
                DeviceStatusCategory.ONLINE_RESTRICTED -> Triple("RESTRICTED (STANDBY OFF)", Color(0xFFF57C00), Color.White)
                DeviceStatusCategory.STALE -> Triple("STATUS STALE", Color(0xFF757575), Color.White)
                DeviceStatusCategory.OFFLINE -> Triple("OFFLINE / APP CLOSED", Color(0xFFD32F2F), Color.White)
                DeviceStatusCategory.ATTENTION_REQUIRED -> Triple("ATTENTION REQUIRED", Color(0xFFC2185B), Color.White)
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(statusBg)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(statusText, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            Divider()

            // Metrics Grid
            if (health != null) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricItem(
                        icon = if (health.isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryStd,
                        label = "Battery",
                        value = "${health.batteryPercentage}%" + if (health.isCharging) " (Charging)" else ""
                    )
                    MetricItem(
                        icon = if (health.isScreenInteractive) Icons.Default.Smartphone else Icons.Default.PhoneLocked,
                        label = "Screen",
                        value = if (health.isScreenInteractive) "On / Interactive" else "Off / Idle"
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricItem(
                        icon = Icons.Default.Wifi,
                        label = "Network",
                        value = health.networkType.name
                    )
                    MetricItem(
                        icon = Icons.Default.Shield,
                        label = "Standby SVC",
                        value = if (health.isStandbyActive) "Active" else "Disabled"
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricItem(
                        icon = Icons.Default.Videocam,
                        label = "Camera Sensor",
                        value = if (health.isCameraActive) "Active (${health.currentCameraFacing.name})" else "Idle"
                    )
                    MetricItem(
                        icon = Icons.Default.Mic,
                        label = "Mic Sensor",
                        value = if (health.isMicActive) "Active" else "Idle"
                    )
                }
            }

            // Timestamp
            val timeString = if (lastSeenAt > 0) {
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastSeenAt))
            } else "Never"
            Text(
                "Last Heartbeat: $timeString",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun MetricItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun ActiveLiveSessionCard(
    mode: SessionMode,
    videoTrack: io.livekit.android.room.track.RemoteVideoTrack?,
    isAudioActive: Boolean,
    onInitRenderer: (SurfaceViewRenderer) -> Unit,
    onSwitchCamera: () -> Unit,
    onStopSession: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Live View ($mode)", fontWeight = FontWeight.Bold)
                if (isAudioActive) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.VolumeUp, contentDescription = "Audio Live", tint = Color(0xFF2E7D32), modifier = Modifier.size(16.dp))
                        Text("Audio Live", fontSize = 12.sp, color = Color(0xFF2E7D32))
                    }
                }
            }

            // Video Stream View
            if (mode != SessionMode.AUDIO_ONLY) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (videoTrack != null) {
                        AndroidView(
                            factory = { context ->
                                SurfaceViewRenderer(context).apply {
                                    onInitRenderer(this)
                                    videoTrack.addRenderer(this)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color.White)
                            Spacer(Modifier.height(8.dp))
                            Text("Connecting video stream...", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Action Buttons (Switch Camera, Stop)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mode != SessionMode.AUDIO_ONLY) {
                    OutlinedButton(
                        onClick = onSwitchCamera,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FlipCameraAndroid, contentDescription = "Switch Camera")
                        Spacer(Modifier.width(6.dp))
                        Text("Switch Cam")
                    }
                }

                Button(
                    onClick = onStopSession,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text("Stop Live", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun SessionLauncherCard(
    statusCategory: DeviceStatusCategory,
    isLoading: Boolean,
    onStartCameraOnly: () -> Unit,
    onStartAudioOnly: () -> Unit,
    onStartCombined: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Initiate Remote Monitoring", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Live stream video and real-time audio through your phone's loudspeaker. Sensors activate on-demand.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Primary Big Button: Camera + Audio
            Button(
                onClick = onStartCombined,
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20))
            ) {
                Icon(Icons.Default.VideoCall, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (isLoading) "Connecting Stream..." else "Start Live Camera + Audio", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            // Secondary row: Video Only / Audio Only
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onStartCameraOnly,
                    enabled = !isLoading,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Camera Only", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onStartAudioOnly,
                    enabled = !isLoading,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Audio Only", fontSize = 12.sp)
                }
            }
        }
    }
}
