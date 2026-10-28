export type SessionMode = 'idle' | 'camera_only' | 'audio_only' | 'camera_and_audio';
export type CameraFacing = 'back' | 'front' | 'unknown';
export type NetworkType = 'wifi' | 'cellular' | 'vpn' | 'ethernet' | 'none';
export type TimerRunState = 'idle' | 'running' | 'paused' | 'completed';

export interface DevicePermissions {
  camera: boolean;
  audio: boolean;
  notifications: boolean;
}

export interface DeviceHealth {
  batteryPercentage: number;
  isCharging: boolean;
  isScreenInteractive: boolean;
  networkType: NetworkType;
  isStandbyActive: boolean;
  isForegroundServiceRunning: boolean;
  isCameraActive: boolean;
  isMicActive: boolean;
  currentSessionMode: SessionMode;
  currentCameraFacing: CameraFacing;
  timerState: TimerRunState;
  remainingTimeMs: number;
  permissions: DevicePermissions;
  lastHeartbeatTimestamp: number;
  errorMessage?: string;
}

export type DeviceStatusCategory =
  | 'ONLINE_READY'
  | 'ONLINE_RESTRICTED'
  | 'SESSION_ACTIVE'
  | 'OFFLINE'
  | 'STALE'
  | 'ATTENTION_REQUIRED';

export interface DeviceRecord {
  deviceId: string;
  role: 'cctv' | 'admin';
  pairedDeviceId?: string;
  sharedSecret: string;
  pairingCode?: string;
  pairingCodeExpiresAt?: number;
  createdAt: number;
  lastSeenAt: number;
  health?: DeviceHealth;
  fcmToken?: string;
  activeSessionId?: string;
}

export type CommandType =
  | 'REQUEST_STATUS'
  | 'START_SESSION'
  | 'STOP_SESSION'
  | 'SWITCH_CAMERA'
  | 'CHANGE_QUALITY'
  | 'UNPAIR';

export interface CommandRequest {
  commandId: string;
  targetDeviceId: string;
  commandType: CommandType;
  sessionMode?: SessionMode;
  targetCamera?: CameraFacing;
  quality?: 'low' | 'medium' | 'high';
  timestamp: number;
  signature: string;
}

export interface CommandConfirmation {
  commandId: string;
  commandType: CommandType;
  success: boolean;
  executedAt: number;
  errorMessage?: string;
  newSessionMode?: SessionMode;
  newCameraFacing?: CameraFacing;
}

export interface LiveKitSessionGrant {
  roomName: string;
  serverUrl: string;
  token: string;
  identity: string;
  mode: SessionMode;
  expiresAt: number;
}
