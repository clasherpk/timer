import { AccessToken, TrackSource } from 'livekit-server-sdk';
import type { LiveKitSessionGrant, SessionMode } from './types.js';

export class LiveKitService {
  private url: string;
  private apiKey: string;
  private apiSecret: string;

  constructor() {
    this.url = process.env.LIVEKIT_URL || 'wss://your-project.livekit.cloud';
    this.apiKey = process.env.LIVEKIT_API_KEY || 'devkey';
    this.apiSecret = process.env.LIVEKIT_API_SECRET || 'secret';
  }

  isConfigured(): boolean {
    return Boolean(
      process.env.LIVEKIT_URL &&
      process.env.LIVEKIT_API_KEY &&
      process.env.LIVEKIT_API_SECRET &&
      process.env.LIVEKIT_API_KEY !== 'devkey'
    );
  }

  async createTokens(
    cctvDeviceId: string,
    adminDeviceId: string,
    mode: SessionMode
  ): Promise<{ cctvGrant: LiveKitSessionGrant; adminGrant: LiveKitSessionGrant }> {
    const roomName = `room_${cctvDeviceId.slice(0, 8)}`;
    const ttlSeconds = 600; // 10 minutes
    const expiresAt = Date.now() + ttlSeconds * 1000;

    // 1. CCTV Publisher Token
    const cctvToken = new AccessToken(this.apiKey, this.apiSecret, {
      identity: `cctv_${cctvDeviceId.slice(0, 8)}`,
      name: 'Timer CCTV Client',
      ttl: ttlSeconds,
    });

    const sources: TrackSource[] =
      mode === 'camera_only'
        ? [TrackSource.CAMERA]
        : mode === 'audio_only'
        ? [TrackSource.MICROPHONE]
        : [TrackSource.CAMERA, TrackSource.MICROPHONE];

    cctvToken.addGrant({
      roomJoin: true,
      room: roomName,
      canPublish: true,
      canSubscribe: false,
      canPublishData: true,
      canPublishSources: sources,
    });

    // 2. Admin Subscriber Token
    const adminToken = new AccessToken(this.apiKey, this.apiSecret, {
      identity: `admin_${adminDeviceId.slice(0, 8)}`,
      name: 'Timer Admin Controller',
      ttl: ttlSeconds,
    });

    adminToken.addGrant({
      roomJoin: true,
      room: roomName,
      canPublish: false,
      canSubscribe: true,
      canPublishData: true,
    });

    const cctvJwt = await cctvToken.toJwt();
    const adminJwt = await adminToken.toJwt();

    return {
      cctvGrant: {
        roomName,
        serverUrl: this.url,
        token: cctvJwt,
        identity: `cctv_${cctvDeviceId.slice(0, 8)}`,
        mode,
        expiresAt,
      },
      adminGrant: {
        roomName,
        serverUrl: this.url,
        token: adminJwt,
        identity: `admin_${adminDeviceId.slice(0, 8)}`,
        mode,
        expiresAt,
      },
    };
  }
}

export const liveKitService = new LiveKitService();
