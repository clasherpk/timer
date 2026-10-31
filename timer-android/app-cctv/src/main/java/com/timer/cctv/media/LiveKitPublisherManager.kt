package com.timer.cctv.media

import android.content.Context
import com.timer.core.model.CameraFacing
import com.timer.core.model.LiveKitSessionGrant
import com.timer.core.model.SessionMode
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

class LiveKitPublisherManager(private val context: Context) {

    private var currentRoom: Room? = null

    private val _isCameraActive = MutableStateFlow(false)
    val isCameraActive: StateFlow<Boolean> = _isCameraActive.asStateFlow()

    private val _isMicActive = MutableStateFlow(false)
    val isMicActive: StateFlow<Boolean> = _isMicActive.asStateFlow()

    private val _currentFacing = MutableStateFlow(CameraFacing.BACK)
    val currentFacing: StateFlow<CameraFacing> = _currentFacing.asStateFlow()

    private val _sessionMode = MutableStateFlow(SessionMode.IDLE)
    val sessionMode: StateFlow<SessionMode> = _sessionMode.asStateFlow()

    suspend fun startSession(grant: LiveKitSessionGrant): Boolean {
        stopSession()

        return try {
            val room = LiveKit.create(appContext = context)
            room.connect(grant.serverUrl, grant.token)
            currentRoom = room
            _sessionMode.value = grant.mode

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

    fun stopSession() {
        try {
            currentRoom?.let { room ->
                runBlocking {
                    try {
                        room.localParticipant.setCameraEnabled(false)
                        room.localParticipant.setMicrophoneEnabled(false)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                room.disconnect()
                room.release()
            }
            currentRoom = null
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            _isCameraActive.value = false
            _isMicActive.value = false
            _sessionMode.value = SessionMode.IDLE
        }
    }
}
