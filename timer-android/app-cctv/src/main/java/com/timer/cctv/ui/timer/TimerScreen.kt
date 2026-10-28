package com.timer.cctv.ui.timer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.timer.core.model.SessionMode
import com.timer.core.model.TimerState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerScreen(
    timerViewModel: TimerViewModel,
    isStandbyActive: Boolean,
    currentSessionMode: SessionMode,
    isCameraActive: Boolean,
    isMicActive: Boolean,
    onOpenCctvSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by timerViewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Timer",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        // Live sensor alert pill (mandatory transparency)
                        if (isCameraActive || isMicActive) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFD32F2F))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isCameraActive) Icons.Default.Videocam else Icons.Default.Mic,
                                        contentDescription = "Sensor Active",
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = if (isCameraActive && isMicActive) "LIVE A/V"
                                        else if (isCameraActive) "LIVE CAM"
                                        else "LIVE MIC",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        } else if (isStandbyActive) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF2E7D32).copy(alpha = 0.2f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "STANDBY READY",
                                    color = Color(0xFF2E7D32),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenCctvSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "CCTV Settings"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Completion Banner
            AnimatedVisibility(visible = uiState.state == TimerState.COMPLETED) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = "Timer Finished",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Timer Finished!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(0.5f))

            // 2. Big Digital Clock Readout
            val totalSeconds = (uiState.remainingMs / 1000L).coerceAtLeast(0L)
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            val formattedTime = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)

            val progress = if (uiState.totalDurationMs > 0L) {
                (uiState.remainingMs.toFloat() / uiState.totalDurationMs.toFloat()).coerceIn(0f, 1f)
            } else 0f

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(280.dp)
            ) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 12.dp,
                    color = if (uiState.state == TimerState.COMPLETED) Color(0xFF2E7D32)
                    else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formattedTime,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when (uiState.state) {
                            TimerState.IDLE -> "Ready"
                            TimerState.RUNNING -> "Counting Down"
                            TimerState.PAUSED -> "Paused"
                            TimerState.COMPLETED -> "Finished"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.5f))

            // 3. Duration Adjustment Controls (Visible when IDLE)
            if (uiState.state == TimerState.IDLE) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TimeUnitAdjuster(
                        label = "Hours",
                        value = uiState.inputHours,
                        onIncrement = { timerViewModel.setHours(uiState.inputHours + 1) },
                        onDecrement = { timerViewModel.setHours(uiState.inputHours - 1) }
                    )
                    TimeUnitAdjuster(
                        label = "Minutes",
                        value = uiState.inputMinutes,
                        onIncrement = { timerViewModel.setMinutes(uiState.inputMinutes + 1) },
                        onDecrement = { timerViewModel.setMinutes(uiState.inputMinutes - 1) }
                    )
                    TimeUnitAdjuster(
                        label = "Seconds",
                        value = uiState.inputSeconds,
                        onIncrement = { timerViewModel.setSeconds(uiState.inputSeconds + 5) },
                        onDecrement = { timerViewModel.setSeconds(uiState.inputSeconds - 5) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 4. Timer Action Controls (Start, Pause, Resume, Reset)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (uiState.state) {
                    TimerState.IDLE -> {
                        Button(
                            onClick = { timerViewModel.start() },
                            modifier = Modifier.height(56.dp).weight(1f),
                            shape = RoundedCornerShape(28.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Start")
                            Spacer(Modifier.width(8.dp))
                            Text("Start", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    TimerState.RUNNING -> {
                        OutlinedButton(
                            onClick = { timerViewModel.pause() },
                            modifier = Modifier.height(56.dp).weight(1f),
                            shape = RoundedCornerShape(28.dp)
                        ) {
                            Icon(Icons.Default.Pause, contentDescription = "Pause")
                            Spacer(Modifier.width(8.dp))
                            Text("Pause", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                        FilledTonalButton(
                            onClick = { timerViewModel.reset() },
                            modifier = Modifier.height(56.dp).weight(1f),
                            shape = RoundedCornerShape(28.dp)
                        ) {
                            Icon(Icons.Default.Replay, contentDescription = "Reset")
                            Spacer(Modifier.width(8.dp))
                            Text("Reset", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    TimerState.PAUSED -> {
                        Button(
                            onClick = { timerViewModel.resume() },
                            modifier = Modifier.height(56.dp).weight(1f),
                            shape = RoundedCornerShape(28.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Resume")
                            Spacer(Modifier.width(8.dp))
                            Text("Resume", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                        FilledTonalButton(
                            onClick = { timerViewModel.reset() },
                            modifier = Modifier.height(56.dp).weight(1f),
                            shape = RoundedCornerShape(28.dp)
                        ) {
                            Icon(Icons.Default.Replay, contentDescription = "Reset")
                            Spacer(Modifier.width(8.dp))
                            Text("Reset", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    TimerState.COMPLETED -> {
                        Button(
                            onClick = { timerViewModel.reset() },
                            modifier = Modifier.height(56.dp).weight(1f),
                            shape = RoundedCornerShape(28.dp)
                        ) {
                            Icon(Icons.Default.Replay, contentDescription = "Reset")
                            Spacer(Modifier.width(8.dp))
                            Text("Reset", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TimeUnitAdjuster(
    label: String,
    value: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconButton(
            onClick = onIncrement,
            modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Increment $label")
        }
        Text(
            text = String.format(Locale.US, "%02d", value),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        IconButton(
            onClick = onDecrement,
            modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Decrement $label")
        }
    }
}
