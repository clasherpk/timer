package com.timer.cctv.media

import android.content.Context
import com.timer.core.model.CameraFacing
import com.timer.core.model.LiveKitSessionGrant
import com.timer.core.model.SessionMode
import io.livekit.android.LiveKit
import io.livekit.android.events.*
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LiveKitPublisherManager(private val context: Context) {

    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentRoom: Room? = null
    private var eventsJob: Job? = null

    private val _isCameraActive = MutableStateFlow(false)
    val isCameraActive: StateFlow<Boolean> = _isCameraActive.asStateFlow()

    private val _isMicActive = MutableStateFlow(false)
    val isMicActive: StateFlow<Boolean> = _isMicActive.asStateFlow()

    private val _currentFacing = MutableStateFlow(CameraFacing.BACK)
    val currentFacing: StateFlow<CameraFacing> = _currentFacing.asStateFlow()

    private val _sessionMode = MutableStateFlow(SessionMode.IDLE)
    val sessionMode: StateFlow<SessionMode> = _sessionMode.asStateFlow()

    suspend fun startSession(grant: LiveKitSessionGrant): Boolean {
        // Cleanly stop any existing session and wait for Camera2 HAL to be completely released
        stopSessionSuspend()
        delay(400L)

        return try {
            val room = LiveKit.create(appContext = context)
            currentRoom = room
            _sessionMode.value = grant.mode

            // Listen for room disconnection or remote subscriber disconnection
            eventsJob?.cancel()
            eventsJob = managerScope.launch {
                room.events.collect { event ->
                    when (event) {
                        is RoomEvent.Disconnected -> {
                            stopSession()
                        }
                        is RoomEvent.ParticipantDisconnected -> {
                            // If the remote subscriber (Admin) leaves, automatically release camera/mic
                            delay(3000L)
                            if (room.remoteParticipants.isEmpty() && currentRoom === room) {
                                stopSession()
                            }
                        }
                        else -> {}
                    }
                }
            }

            room.connect(grant.serverUrl, grant.token)

            when (grant.mode) {
                SessionMode.CAMERA_ONLY -> {
                    enableCameraWithRetry(room)
                    _isCameraActive.value = true
                    _isMicActive.value = false
                }
                SessionMode.AUDIO_ONLY -> {
                    enableMicWithRetry(room)
                    _isCameraActive.value = false
                    _isMicActive.value = true
                }
                SessionMode.CAMERA_AND_AUDIO -> {
                    enableCameraWithRetry(room)
                    enableMicWithRetry(room)
                    _isCameraActive.value = true
                    _isMicActive.value = true
                }
                SessionMode.IDLE -> {
                    stopSession()
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            stopSession()
            false
        }
    }

    private suspend fun enableCameraWithRetry(room: Room, maxRetries: Int = 4) {
        var attempt = 0
        var lastError: Exception? = null
        while (attempt < maxRetries) {
            try {
                room.localParticipant.setCameraEnabled(true)
                return
            } catch (e: Exception) {
                lastError = e
                attempt++
                delay(750L) // Wait for screen/hardware display to finish powering up
            }
        }
        if (lastError != null) throw lastError
    }

    private suspend fun enableMicWithRetry(room: Room, maxRetries: Int = 3) {
        var attempt = 0
        var lastError: Exception? = null
        while (attempt < maxRetries) {
            try {
                room.localParticipant.setMicrophoneEnabled(true)
                return
            } catch (e: Exception) {
                lastError = e
                attempt++
                delay(500L)
            }
        }
        if (lastError != null) throw lastError
    }

    suspend fun switchCamera(): CameraFacing {
        val room = currentRoom ?: return _currentFacing.value
        try {
            val publication = room.localParticipant.getTrackPublication(Track.Source.CAMERA)
            val videoTrack = publication?.track as? LocalVideoTrack
            videoTrack?.switchCamera()
            val nextFacing = if (_currentFacing.value == CameraFacing.BACK) CameraFacing.FRONT else CameraFacing.BACK
            _currentFacing.value = nextFacing
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return _currentFacing.value
    }

    suspend fun stopSessionSuspend() {
        eventsJob?.cancel()
        eventsJob = null

        val room = currentRoom
        currentRoom = null

        if (room != null) {
            try {
                try {
                    room.localParticipant.setCameraEnabled(false)
                } catch (ignored: Exception) {}

                try {
                    room.localParticipant.setMicrophoneEnabled(false)
                } catch (ignored: Exception) {}

                // Give Camera2 and AudioRecord HAL threads 400ms to close cleanly
                delay(400L)

                try {
                    room.disconnect()
                } catch (ignored: Exception) {}

                try {
                    room.release()
                } catch (ignored: Exception) {}
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        _isCameraActive.value = false
        _isMicActive.value = false
        _sessionMode.value = SessionMode.IDLE
    }

    fun stopSession() {
        managerScope.launch {
            stopSessionSuspend()
        }
    }

    fun stopSessionAsync() {
        stopSession()
    }
}
