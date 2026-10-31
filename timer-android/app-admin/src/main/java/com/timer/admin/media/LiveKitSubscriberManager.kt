package com.timer.admin.media

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.timer.admin.AdminApplication
import com.timer.core.model.*
import io.livekit.android.LiveKit
import io.livekit.android.events.*
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class LiveKitSubscriberManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var currentRoom: Room? = null

    private val _videoTrack = MutableStateFlow<RemoteVideoTrack?>(null)
    val videoTrack: StateFlow<RemoteVideoTrack?> = _videoTrack.asStateFlow()

    private val _isAudioActive = MutableStateFlow(false)
    val isAudioActive: StateFlow<Boolean> = _isAudioActive.asStateFlow()

    private val _currentMode = MutableStateFlow(SessionMode.IDLE)
    val currentMode: StateFlow<SessionMode> = _currentMode.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private fun routeToSpeakerphone() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val speakerDevice = audioManager.availableCommunicationDevices.find {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (speakerDevice != null) {
                    audioManager.setCommunicationDevice(speakerDevice)
                }
            }
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun resetAudioRouting() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun connect(grant: LiveKitSessionGrant): Boolean {
        disconnect()

        return try {
            val room = LiveKit.create(appContext = context)
            room.connect(grant.serverUrl, grant.token)
            currentRoom = room
            _currentMode.value = grant.mode
            routeToSpeakerphone()

            scope.launch {
                room.events.collect { event ->
                    when (event) {
                        is RoomEvent.TrackSubscribed -> {
                            if (event.track is RemoteVideoTrack) {
                                _videoTrack.value = event.track as RemoteVideoTrack
                            } else if (event.track is RemoteAudioTrack) {
                                _isAudioActive.value = true
                                routeToSpeakerphone()
                            }
                        }
                        is RoomEvent.TrackUnsubscribed -> {
                            if (event.track is RemoteVideoTrack) {
                                _videoTrack.value = null
                            } else if (event.track is RemoteAudioTrack) {
                                _isAudioActive.value = false
                            }
                        }
                        is RoomEvent.Disconnected -> {
                            disconnect()
                        }
                        else -> {}
                    }
                }
            }
            true
        } catch (e: Exception) {
            _error.value = e.message ?: "Connection failed"
            disconnect()
            false
        }
    }

    fun initRenderer(renderer: SurfaceViewRenderer) {
        currentRoom?.initVideoRenderer(renderer)
    }

    suspend fun switchRemoteCamera(): Boolean {
        val app = AdminApplication.instance
        val cctvId = app.pairedCctvDeviceId ?: return false
        val secret = app.sharedSecret ?: return false

        val timestamp = System.currentTimeMillis()
        val signature = app.apiClient.createHmacSignature("SWITCH_CAMERA", timestamp, secret)

        val command = CommandRequest(
            commandId = "cmd_${System.currentTimeMillis()}",
            targetDeviceId = cctvId,
            commandType = CommandType.SWITCH_CAMERA,
            timestamp = timestamp,
            signature = signature
        )

        val result = app.apiClient.sendCommand(command)
        return result.getOrDefault(false)
    }

    fun disconnect() {
        try {
            resetAudioRouting()
            currentRoom?.disconnect()
            currentRoom?.release()
            currentRoom = null
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            _videoTrack.value = null
            _isAudioActive.value = false
            _currentMode.value = SessionMode.IDLE
        }
    }
}
