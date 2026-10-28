package com.timer.cctv.media

import android.content.Context
import com.timer.core.model.CameraFacing
import com.timer.core.model.LiveKitSessionGrant
import com.timer.core.model.SessionMode
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.CameraPosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LiveKitPublisherManager(private val context: Context) {

    private var currentRoom: Room? = null
    private var localVideoTrack: LocalVideoTrack? = null
    private var localAudioTrack: LocalAudioTrack? = null

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
            val room = LiveKit.connect(
                appContext = context,
                url = grant.serverUrl,
                token = grant.token
            )
            currentRoom = room
            _sessionMode.value = grant.mode

            when (grant.mode) {
                SessionMode.CAMERA_ONLY -> {
                    enableVideo(room, CameraPosition.BACK)
                }
                SessionMode.AUDIO_ONLY -> {
                    enableAudio(room)
                }
                SessionMode.CAMERA_AND_AUDIO -> {
                    enableVideo(room, CameraPosition.BACK)
                    enableAudio(room)
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

    private suspend fun enableVideo(room: Room, position: CameraPosition) {
        val videoTrack = room.localParticipant.createVideoTrack(
            options = LocalVideoTrackOptions(position = position)
        )
        room.localParticipant.publishVideoTrack(videoTrack)
        localVideoTrack = videoTrack
        _isCameraActive.value = true
        _currentFacing.value = if (position == CameraPosition.FRONT) CameraFacing.FRONT else CameraFacing.BACK
    }

    private suspend fun enableAudio(room: Room) {
        val audioTrack = room.localParticipant.createAudioTrack()
        room.localParticipant.publishAudioTrack(audioTrack)
        localAudioTrack = audioTrack
        _isMicActive.value = true
    }

    suspend fun switchCamera(): CameraFacing {
        val videoTrack = localVideoTrack ?: return _currentFacing.value
        val newPosition = if (_currentFacing.value == CameraFacing.BACK) {
            CameraPosition.FRONT
        } else {
            CameraPosition.BACK
        }

        try {
            videoTrack.switchCamera(newPosition)
            _currentFacing.value = if (newPosition == CameraPosition.FRONT) CameraFacing.FRONT else CameraFacing.BACK
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return _currentFacing.value
    }

    fun stopSession() {
        try {
            localVideoTrack?.stop()
            localAudioTrack?.stop()

            localVideoTrack = null
            localAudioTrack = null

            currentRoom?.disconnect()
            currentRoom?.release()
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
