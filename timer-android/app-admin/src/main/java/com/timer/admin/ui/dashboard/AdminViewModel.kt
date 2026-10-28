package com.timer.admin.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.timer.admin.AdminApplication
import com.timer.admin.media.LiveKitSubscriberManager
import com.timer.core.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AdminUiState(
    val serverUrl: String = "http://10.0.2.2:10000",
    val isPaired: Boolean = false,
    val pairedCctvId: String? = null,
    val statusCategory: DeviceStatusCategory = DeviceStatusCategory.OFFLINE,
    val lastSeenAt: Long = 0L,
    val health: DeviceHealth? = null,
    val activeSessionMode: SessionMode = SessionMode.IDLE,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class AdminViewModel : ViewModel() {

    private val app = AdminApplication.instance
    val subscriberManager = LiveKitSubscriberManager(app)

    private val _uiState = MutableStateFlow(
        AdminUiState(
            serverUrl = app.apiClient.getBaseUrl(),
            isPaired = app.pairedCctvDeviceId != null,
            pairedCctvId = app.pairedCctvDeviceId
        )
    )
    val uiState: StateFlow<AdminUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    init {
        startStatusPolling()
    }

    fun updateServerUrl(url: String) {
        val cleanUrl = url.trimEnd('/')
        app.apiClient.updateBaseUrl(cleanUrl)
        app.getSharedPreferences("timer_admin_prefs", Context.MODE_PRIVATE)
            .edit().putString("backend_url", cleanUrl).apply()
        _uiState.update { it.copy(serverUrl = cleanUrl) }
    }

    fun pairDevice(code: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = app.apiClient.submitPairing(app.deviceId, code)
            result.onSuccess { res ->
                if (res.success && res.cctvDeviceId != null && res.sharedSecret != null) {
                    app.savePairing(res.cctvDeviceId, res.sharedSecret)
                    _uiState.update {
                        it.copy(
                            isPaired = true,
                            pairedCctvId = res.cctvDeviceId,
                            isLoading = false
                        )
                    }
                    fetchStatusOnce()
                } else {
                    _uiState.update { it.copy(isLoading = false, errorMessage = res.error ?: "Pairing rejected") }
                }
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message ?: "Failed to connect to backend") }
            }
        }
    }

    fun unpair() {
        viewModelScope.launch {
            val cctvId = app.pairedCctvDeviceId
            if (cctvId != null) {
                app.apiClient.unpair(app.deviceId)
            }
            subscriberManager.disconnect()
            app.clearPairing()
            _uiState.update {
                it.copy(
                    isPaired = false,
                    pairedCctvId = null,
                    statusCategory = DeviceStatusCategory.OFFLINE,
                    health = null,
                    activeSessionMode = SessionMode.IDLE
                )
            }
        }
    }

    fun startSession(mode: SessionMode) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = app.apiClient.startSession(app.deviceId, mode)
            result.onSuccess { res ->
                if (res.success) {
                    val connected = subscriberManager.connect(res.adminGrant)
                    if (connected) {
                        _uiState.update {
                            it.copy(
                                activeSessionMode = mode,
                                isLoading = false
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = "Failed to connect WebRTC stream"
                            )
                        }
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false, errorMessage = res.error ?: "Session start rejected") }
                }
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message ?: "Backend error") }
            }
        }
    }

    fun stopSession() {
        viewModelScope.launch {
            subscriberManager.disconnect()
            app.apiClient.stopSession(app.deviceId)
            _uiState.update { it.copy(activeSessionMode = SessionMode.IDLE) }
            fetchStatusOnce()
        }
    }

    fun switchCamera() {
        viewModelScope.launch {
            subscriberManager.switchRemoteCamera()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun startStatusPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                if (_uiState.value.isPaired) {
                    fetchStatusOnce()
                }
                delay(8000L) // poll telemetry every 8s
            }
        }
    }

    fun fetchStatusOnce() {
        viewModelScope.launch {
            val result = app.apiClient.getPairedDeviceStatus(app.deviceId)
            result.onSuccess { res ->
                _uiState.update {
                    it.copy(
                        statusCategory = res.statusCategory,
                        lastSeenAt = res.lastSeenAt,
                        health = res.health
                    )
                }
            }.onFailure {
                // If backend unreachable, mark as offline/stale
                _uiState.update {
                    it.copy(statusCategory = DeviceStatusCategory.STALE)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
        subscriberManager.disconnect()
    }
}
