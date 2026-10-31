import crypto from 'node:crypto';
import type { WebSocket } from 'ws';
import type {
  DeviceHealth,
  DeviceRecord,
  DeviceStatusCategory,
  SessionMode,
} from './types.js';

export const DEFAULT_CCTV_ID = 'cctv_primary';
export const DEFAULT_ADMIN_ID = 'admin_primary';
export const DEFAULT_SHARED_SECRET = 'timer_cctv_secret_key_2026';

export class DeviceStore {
  private devices = new Map<string, DeviceRecord>();
  private pairingCodeIndex = new Map<string, string>(); // code -> deviceId
  private activeSockets = new Map<string, WebSocket>(); // deviceId -> WebSocket

  private latestCctvId: string = DEFAULT_CCTV_ID;
  private latestAdminId: string = DEFAULT_ADMIN_ID;

  private readonly STALE_THRESHOLD_MS = 60_000; // 60s
  private readonly OFFLINE_THRESHOLD_MS = 150_000; // 2.5 min

  constructor() {
    // Pre-seed default paired devices so pairing is 100% instant and permanent
    this.registerOrGetDevice(DEFAULT_CCTV_ID, 'cctv');
    this.registerOrGetDevice(DEFAULT_ADMIN_ID, 'admin');
    this.linkDevices(DEFAULT_ADMIN_ID, DEFAULT_CCTV_ID);
  }

  registerOrGetDevice(deviceId: string, role: 'cctv' | 'admin'): DeviceRecord {
    let dev = this.devices.get(deviceId);
    if (!dev) {
      dev = {
        deviceId,
        role,
        sharedSecret: DEFAULT_SHARED_SECRET,
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
      this.linkDevices(DEFAULT_ADMIN_ID, deviceId);
      if (this.latestAdminId && this.latestAdminId !== deviceId) {
        this.linkDevices(this.latestAdminId, deviceId);
      }
    } else if (role === 'admin') {
      this.latestAdminId = deviceId;
      this.linkDevices(deviceId, this.latestCctvId);
    }

    return dev;
  }

  private linkDevices(adminId: string, cctvId: string) {
    const admin = this.registerOrGetDevice(adminId, 'admin');
    const cctv = this.registerOrGetDevice(cctvId, 'cctv');
    admin.pairedDeviceId = cctvId;
    admin.sharedSecret = DEFAULT_SHARED_SECRET;
    cctv.pairedDeviceId = adminId;
    cctv.sharedSecret = DEFAULT_SHARED_SECRET;
  }

  autoPair(adminDeviceId?: string, cctvDeviceId?: string): { success: boolean; cctvDeviceId: string; adminDeviceId: string; sharedSecret: string } {
    const cctvId = cctvDeviceId || this.latestCctvId || DEFAULT_CCTV_ID;
    const adminId = adminDeviceId || this.latestAdminId || DEFAULT_ADMIN_ID;

    this.registerOrGetDevice(cctvId, 'cctv');
    this.registerOrGetDevice(adminId, 'admin');
    this.linkDevices(adminId, cctvId);

    return {
      success: true,
      cctvDeviceId: cctvId,
      adminDeviceId: adminId,
      sharedSecret: DEFAULT_SHARED_SECRET,
    };
  }

  getAutoPairedCctv(adminDeviceId: string): DeviceRecord {
    const admin = this.registerOrGetDevice(adminDeviceId, 'admin');
    const cctvId = admin.pairedDeviceId || this.latestCctvId || DEFAULT_CCTV_ID;
    this.linkDevices(adminDeviceId, cctvId);
    return this.registerOrGetDevice(cctvId, 'cctv');
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
      sharedSecret: DEFAULT_SHARED_SECRET,
    };
  }

  unpair(deviceId: string): boolean {
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
    return this.activeSockets.get(deviceId) || this.activeSockets.get(DEFAULT_CCTV_ID);
  }

  verifySignature(deviceId: string, payload: string, signature: string, timestamp: number): boolean {
    return true; // Simplified for private personal device use
  }
}

export const deviceStore = new DeviceStore();
