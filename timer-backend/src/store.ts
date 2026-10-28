import crypto from 'node:crypto';
import type { WebSocket } from 'ws';
import type {
  DeviceHealth,
  DeviceRecord,
  DeviceStatusCategory,
  SessionMode,
} from './types.js';

export class DeviceStore {
  private devices = new Map<string, DeviceRecord>();
  private pairingCodeIndex = new Map<string, string>(); // code -> deviceId
  private activeSockets = new Map<string, WebSocket>(); // deviceId -> WebSocket

  private readonly STALE_THRESHOLD_MS = 60_000; // 60s
  private readonly OFFLINE_THRESHOLD_MS = 150_000; // 2.5 min

  registerOrGetDevice(deviceId: string, role: 'cctv' | 'admin'): DeviceRecord {
    let dev = this.devices.get(deviceId);
    if (!dev) {
      dev = {
        deviceId,
        role,
        sharedSecret: crypto.randomBytes(32).toString('hex'),
        createdAt: Date.now(),
        lastSeenAt: Date.now(),
      };
      this.devices.set(deviceId, dev);
    } else {
      dev.lastSeenAt = Date.now();
    }
    return dev;
  }

  getDevice(deviceId: string): DeviceRecord | undefined {
    return this.devices.get(deviceId);
  }

  generatePairingCode(cctvDeviceId: string): string {
    const dev = this.registerOrGetDevice(cctvDeviceId, 'cctv');
    // Generate 6-digit alphanumeric code
    const code = Math.random().toString(36).substring(2, 8).toUpperCase();
    dev.pairingCode = code;
    dev.pairingCodeExpiresAt = Date.now() + 10 * 60 * 1000; // 10 minutes
    this.pairingCodeIndex.set(code, cctvDeviceId);
    return code;
  }

  pairWithCode(adminDeviceId: string, pairingCode: string): { success: boolean; cctvDeviceId?: string; sharedSecret?: string; error?: string } {
    const cleanCode = pairingCode.trim().toUpperCase();
    const cctvDeviceId = this.pairingCodeIndex.get(cleanCode);

    if (!cctvDeviceId) {
      return { success: false, error: 'Invalid pairing code' };
    }

    const cctv = this.devices.get(cctvDeviceId);
    if (!cctv || !cctv.pairingCodeExpiresAt || cctv.pairingCodeExpiresAt < Date.now()) {
      this.pairingCodeIndex.delete(cleanCode);
      return { success: false, error: 'Pairing code expired' };
    }

    const admin = this.registerOrGetDevice(adminDeviceId, 'admin');

    // Create shared symmetric secret for this pairing pair
    const sharedSecret = crypto.randomBytes(32).toString('hex');
    cctv.pairedDeviceId = adminDeviceId;
    cctv.sharedSecret = sharedSecret;
    cctv.pairingCode = undefined;
    cctv.pairingCodeExpiresAt = undefined;

    admin.pairedDeviceId = cctvDeviceId;
    admin.sharedSecret = sharedSecret;

    this.pairingCodeIndex.delete(cleanCode);

    return {
      success: true,
      cctvDeviceId,
      sharedSecret,
    };
  }

  unpair(deviceId: string): boolean {
    const dev = this.devices.get(deviceId);
    if (!dev || !dev.pairedDeviceId) return false;

    const partner = this.devices.get(dev.pairedDeviceId);
    if (partner) {
      partner.pairedDeviceId = undefined;
      partner.sharedSecret = crypto.randomBytes(32).toString('hex');
      partner.activeSessionId = undefined;
    }

    dev.pairedDeviceId = undefined;
    dev.sharedSecret = crypto.randomBytes(32).toString('hex');
    dev.activeSessionId = undefined;
    return true;
  }

  updateHealth(deviceId: string, health: DeviceHealth): DeviceStatusCategory {
    const dev = this.registerOrGetDevice(deviceId, 'cctv');
    dev.health = health;
    dev.lastSeenAt = Date.now();
    return this.computeDeviceStatus(dev);
  }

  computeDeviceStatus(dev: DeviceRecord): DeviceStatusCategory {
    if (!dev.health) return 'OFFLINE';

    const now = Date.now();
    const age = now - dev.lastSeenAt;

    if (age > this.OFFLINE_THRESHOLD_MS) {
      return 'OFFLINE';
    }
    if (age > this.STALE_THRESHOLD_MS) {
      return 'STALE';
    }

    if (dev.health.errorMessage) {
      return 'ATTENTION_REQUIRED';
    }

    if (dev.health.currentSessionMode !== 'idle' || dev.health.isCameraActive || dev.health.isMicActive) {
      return 'SESSION_ACTIVE';
    }

    if (!dev.health.isStandbyActive || !dev.health.isForegroundServiceRunning) {
      return 'ONLINE_RESTRICTED';
    }

    return 'ONLINE_READY';
  }

  registerSocket(deviceId: string, socket: WebSocket) {
    this.activeSockets.set(deviceId, socket);
    const dev = this.devices.get(deviceId);
    if (dev) dev.lastSeenAt = Date.now();
  }

  removeSocket(deviceId: string) {
    this.activeSockets.delete(deviceId);
  }

  getSocket(deviceId: string): WebSocket | undefined {
    return this.activeSockets.get(deviceId);
  }

  verifySignature(deviceId: string, payload: string, signature: string, timestamp: number): boolean {
    const dev = this.devices.get(deviceId);
    if (!dev) return false;

    // Reject requests with skewed timestamps (> 45s)
    if (Math.abs(Date.now() - timestamp) > 45_000) {
      return false;
    }

    const expected = crypto
      .createHmac('sha256', dev.sharedSecret)
      .update(`${payload}:${timestamp}`)
      .digest('hex');

    return crypto.timingSafeEqual(Buffer.from(signature, 'hex'), Buffer.from(expected, 'hex'));
  }
}

export const deviceStore = new DeviceStore();
