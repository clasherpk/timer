package com.timer.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SessionMode {
    @SerialName("idle")
    IDLE,
    @SerialName("camera_only")
    CAMERA_ONLY,
    @SerialName("audio_only")
    AUDIO_ONLY,
    @SerialName("camera_and_audio")
    CAMERA_AND_AUDIO
}

@Serializable
enum class CameraFacing {
    @SerialName("back")
    BACK,
    @SerialName("front")
    FRONT,
    @SerialName("unknown")
    UNKNOWN
}

@Serializable
enum class NetworkType {
    @SerialName("wifi")
    WIFI,
    @SerialName("cellular")
    CELLULAR,
    @SerialName("vpn")
    VPN,
    @SerialName("ethernet")
    ETHERNET,
    @SerialName("none")
    NONE
}

@Serializable
enum class TimerState {
    @SerialName("idle")
    IDLE,
    @SerialName("running")
    RUNNING,
    @SerialName("paused")
    PAUSED,
    @SerialName("completed")
    COMPLETED
}

@Serializable
data class DevicePermissions(
    val camera: Boolean = false,
    val audio: Boolean = false,
    val notifications: Boolean = false
)

@Serializable
data class DeviceHealth(
    val batteryPercentage: Int,
    val isCharging: Boolean,
    val isScreenInteractive: Boolean,
    val networkType: NetworkType,
    val isStandbyActive: Boolean,
    val isForegroundServiceRunning: Boolean,
    val isCameraActive: Boolean,
    val isMicActive: Boolean,
    val currentSessionMode: SessionMode,
    val currentCameraFacing: CameraFacing,
    val timerState: TimerState,
    val remainingTimeMs: Long,
    val permissions: DevicePermissions,
    val lastHeartbeatTimestamp: Long,
    val errorMessage: String? = null
)

@Serializable
enum class DeviceStatusCategory {
    ONLINE_READY,
    ONLINE_RESTRICTED,
    SESSION_ACTIVE,
    OFFLINE,
    STALE,
    ATTENTION_REQUIRED
}

@Serializable
data class LiveKitSessionGrant(
    val roomName: String,
    val serverUrl: String,
    val token: String,
    val identity: String,
    val mode: SessionMode,
    val expiresAt: Long
)

@Serializable
enum class CommandType {
    REQUEST_STATUS,
    START_SESSION,
    STOP_SESSION,
    SWITCH_CAMERA,
    CHANGE_QUALITY,
    UNPAIR
}

@Serializable
data class CommandRequest(
    val commandId: String,
    val targetDeviceId: String,
    val commandType: CommandType,
    val sessionMode: SessionMode? = null,
    val targetCamera: CameraFacing? = null,
    val quality: String? = null,
    val timestamp: Long,
    val signature: String
)

@Serializable
data class CommandConfirmation(
    val commandId: String,
    val commandType: CommandType,
    val success: Boolean,
    val executedAt: Long,
    val errorMessage: String? = null,
    val newSessionMode: SessionMode? = null,
    val newCameraFacing: CameraFacing? = null
)

@Serializable
data class PairingGenerateRequest(
    val cctvDeviceId: String
)

@Serializable
data class PairingGenerateResponse(
    val pairingCode: String,
    val expiresInSeconds: Long
)

@Serializable
data class PairingSubmitRequest(
    val adminDeviceId: String,
    val pairingCode: String
)

@Serializable
data class PairingSubmitResponse(
    val success: Boolean = false,
    val cctvDeviceId: String? = null,
    val sharedSecret: String? = null,
    val error: String? = null
)

@Serializable
data class HeartbeatRequest(
    val deviceId: String,
    val health: DeviceHealth
)

@Serializable
data class HeartbeatResponse(
    val success: Boolean,
    val computedStatus: DeviceStatusCategory,
    val serverTimestamp: Long
)

@Serializable
data class PairedDeviceStatusResponse(
    val cctvDeviceId: String,
    val statusCategory: DeviceStatusCategory,
    val lastSeenAt: Long,
    val health: DeviceHealth? = null,
    val activeSessionId: String? = null
)

@Serializable
data class StartSessionRequest(
    val adminDeviceId: String,
    val mode: SessionMode
)

@Serializable
data class StartSessionResponse(
    val success: Boolean = false,
    val mode: SessionMode = SessionMode.IDLE,
    val adminGrant: LiveKitSessionGrant? = null,
    val cctvGrant: LiveKitSessionGrant? = null,
    val error: String? = null
)
