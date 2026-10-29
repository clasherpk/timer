package com.timer.cctv.ui.timer

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.timer.cctv.MainActivity
import com.timer.cctv.R
import com.timer.cctv.TimerApplication
import com.timer.core.model.TimerState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TimerUiState(
    val inputHours: Int = 0,
    val inputMinutes: Int = 5,
    val inputSeconds: Int = 0,
    val totalDurationMs: Long = 5 * 60 * 1000L,
    val remainingMs: Long = 5 * 60 * 1000L,
    val state: TimerState = TimerState.IDLE
)

class TimerViewModel(
    private val context: Context = TimerApplication.instance
) : ViewModel() {

    private val _uiState = MutableStateFlow(TimerUiState())
    val uiState: StateFlow<TimerUiState> = _uiState.asStateFlow()

    private var countdownJob: Job? = null
    private var targetEndTimeMs: Long = 0L

    fun setHours(hours: Int) {
        if (_uiState.value.state != TimerState.IDLE) return
        val clamped = hours.coerceIn(0, 99)
        updateTotalDuration(clamped, _uiState.value.inputMinutes, _uiState.value.inputSeconds)
    }

    fun setMinutes(minutes: Int) {
        if (_uiState.value.state != TimerState.IDLE) return
        val clamped = minutes.coerceIn(0, 59)
        updateTotalDuration(_uiState.value.inputHours, clamped, _uiState.value.inputSeconds)
    }

    fun setSeconds(seconds: Int) {
        if (_uiState.value.state != TimerState.IDLE) return
        val clamped = seconds.coerceIn(0, 59)
        updateTotalDuration(_uiState.value.inputHours, _uiState.value.inputMinutes, clamped)
    }

    private fun updateTotalDuration(h: Int, m: Int, s: Int) {
        val totalMs = (h * 3600L + m * 60L + s) * 1000L
        _uiState.update {
            it.copy(
                inputHours = h,
                inputMinutes = m,
                inputSeconds = s,
                totalDurationMs = totalMs,
                remainingMs = totalMs
            )
        }
    }

    fun start() {
        val current = _uiState.value
        if (current.remainingMs <= 0L) return

        targetEndTimeMs = SystemClock.elapsedRealtime() + current.remainingMs
        _uiState.update { it.copy(state = TimerState.RUNNING) }

        startTickLoop()
    }

    fun pause() {
        if (_uiState.value.state != TimerState.RUNNING) return
        countdownJob?.cancel()
        val remaining = (targetEndTimeMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        _uiState.update {
            it.copy(
                state = TimerState.PAUSED,
                remainingMs = remaining
            )
        }
    }

    fun resume() {
        if (_uiState.value.state != TimerState.PAUSED) return
        start()
    }

    fun reset() {
        countdownJob?.cancel()
        val total = _uiState.value.totalDurationMs
        _uiState.update {
            it.copy(
                state = TimerState.IDLE,
                remainingMs = total
            )
        }
    }

    private fun startTickLoop() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                val diff = targetEndTimeMs - now

                if (diff <= 0L) {
                    _uiState.update {
                        it.copy(
                            remainingMs = 0L,
                            state = TimerState.COMPLETED
                        )
                    }
                    triggerCompletionAlert()
                    break
                } else {
                    _uiState.update { it.copy(remainingMs = diff) }
                }
                delay(100L) // smooth 10Hz tick
            }
        }
    }

    private fun triggerCompletionAlert() {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val notification = NotificationCompat.Builder(context, TimerApplication.CHANNEL_TIMER_COMPLETION)
                .setSmallIcon(R.drawable.ic_notification_timer)
                .setContentTitle("Timer Finished!")
                .setContentText("Your countdown of ${_uiState.value.inputHours}h ${_uiState.value.inputMinutes}m ${_uiState.value.inputSeconds}s has completed.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setSound(soundUri)
                .build()

            notificationManager.notify(TimerApplication.NOTIFICATION_ID_TIMER_COMPLETE, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
    }
}
