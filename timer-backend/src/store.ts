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

  private latestCctvId?: string;
  private latestAdminId?: string;

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
      dev.role = role;
    }

    if (role === 'cctv') {
      this.latestCctvId = deviceId;
      if (this.latestAdminId && this.latestAdminId !== deviceId) {
        this.linkDevices(this.latestAdminId, deviceId);
      }
    } else if (role === 'admin') {
      this.latestAdminId = deviceId;
      if (this.latestCctvId && this.latestCctvId !== deviceId) {
        this.linkDevices(deviceId, this.latestCctvId);
      }
    }

    return dev;
  }

  private linkDevices(adminId: string, cctvId: string) {
    const admin = this.devices.get(adminId);
    const cctv = this.devices.get(cctvId);
    if (admin && cctv) {
      const secret = cctv.sharedSecret || admin.sharedSecret || crypto.randomBytes(32).toString('hex');
      admin.pairedDeviceId = cctvId;
      admin.sharedSecret = secret;
      cctv.pairedDeviceId = adminId;
      cctv.sharedSecret = secret;
    }
  }

  autoPair(adminDeviceId?: string, cctvDeviceId?: string): { success: boolean; cctvDeviceId?: string; adminDeviceId?: string; sharedSecret?: string } {
    if (cctvDeviceId) {
      this.registerOrGetDevice(cctvDeviceId, 'cctv');
    }
    if (adminDeviceId) {
      this.registerOrGetDevice(adminDeviceId, 'admin');
    }

    const cctvId = cctvDeviceId || this.latestCctvId || Array.from(this.devices.values()).find(d => d.role === 'cctv')?.deviceId;
    const adminId = adminDeviceId || this.latestAdminId || Array.from(this.devices.values()).find(d => d.role === 'admin')?.deviceId;

    if (!cctvId) {
      return { success: false };
    }

    if (adminId) {
      this.linkDevices(adminId, cctvId);
      const cctv = this.devices.get(cctvId);
      return {
        success: true,
        cctvDeviceId: cctvId,
        adminDeviceId: adminId,
        sharedSecret: cctv?.sharedSecret,
      };
    }

    return {
      success: true,
      cctvDeviceId: cctvId,
    };
  }

  getAutoPairedCctv(adminDeviceId: string): DeviceRecord | undefined {
    const admin = this.registerOrGetDevice(adminDeviceId, 'admin');
    if (admin.pairedDeviceId) {
      const cctv = this.devices.get(admin.pairedDeviceId);
      if (cctv) return cctv;
    }

    const auto = this.autoPair(adminDeviceId);
    if (auto.cctvDeviceId) {
      return this.devices.get(auto.cctvDeviceId);
    }
    return undefined;
  }

  getDevice(deviceId: string): DeviceRecord | undefined {
    return this.devices.get(deviceId);
  }

  generatePairingCode(cctvDeviceId: string): string {
    const dev = this.registerOrGetDevice(cctvDeviceId, 'cctv');
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

    this.linkDevices(adminDeviceId, cctvDeviceId);
    cctv.pairingCode = undefined;
    cctv.pairingCodeExpiresAt = undefined;
    this.pairingCodeIndex.delete(cleanCode);

    return {
      success: true,
      cctvDeviceId,
      sharedSecret: cctv.sharedSecret,
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
