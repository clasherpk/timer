import Fastify from 'fastify';
import cors from '@fastify/cors';
import websocket from '@fastify/websocket';
import dotenv from 'dotenv';
import { deviceStore } from './store.js';
import { liveKitService } from './livekit.js';
import type {
  CommandConfirmation,
  CommandRequest,
  DeviceHealth,
  SessionMode,
} from './types.js';

dotenv.config();

const port = Number(process.env.PORT) || 10000;
const host = process.env.HOST || '0.0.0.0';

const fastify = Fastify({
  logger: true,
});

await fastify.register(cors, {
  origin: true,
  methods: ['GET', 'POST', 'PUT', 'DELETE'],
});

await fastify.register(websocket);

// 1. Healthcheck for Render
fastify.get('/health', async () => {
  return {
    status: 'ok',
    timestamp: Date.now(),
    liveKitConfigured: liveKitService.isConfigured(),
  };
});

// 2. Register / Ping Device
fastify.post<{ Body: { deviceId: string; role: 'cctv' | 'admin' } }>(
  '/api/device/register',
  async (request, reply) => {
    const { deviceId, role } = request.body;
    if (!deviceId || !role) {
      return reply.code(400).send({ error: 'Missing deviceId or role' });
    }
    const dev = deviceStore.registerOrGetDevice(deviceId, role);
    return {
      deviceId: dev.deviceId,
      role: dev.role,
      pairedDeviceId: dev.pairedDeviceId,
      sharedSecret: dev.sharedSecret,
    };
  }
);

// 3. Generate Pairing Code (called by CCTV device)
fastify.post<{ Body: { cctvDeviceId: string } }>(
  '/api/pairing/generate',
  async (request, reply) => {
    const { cctvDeviceId } = request.body;
    if (!cctvDeviceId) {
      return reply.code(400).send({ error: 'Missing cctvDeviceId' });
    }
    const pairingCode = deviceStore.generatePairingCode(cctvDeviceId);
    return {
      pairingCode,
      expiresInSeconds: 600,
    };
  }
);

// 4. Pair with Pairing Code (called by Admin device)
fastify.post<{ Body: { adminDeviceId: string; pairingCode: string } }>(
  '/api/pairing/pair',
  async (request, reply) => {
    const { adminDeviceId, pairingCode } = request.body;
    if (!adminDeviceId || !pairingCode) {
      return reply.code(400).send({ error: 'Missing adminDeviceId or pairingCode' });
    }
    const result = deviceStore.pairWithCode(adminDeviceId, pairingCode);
    if (!result.success) {
      return reply.code(400).send({ error: result.error });
    }
    return {
      success: true,
      cctvDeviceId: result.cctvDeviceId,
      sharedSecret: result.sharedSecret,
    };
  }
);

// 5. Unpair Device
fastify.post<{ Body: { deviceId: string } }>(
  '/api/pairing/unpair',
  async (request, reply) => {
    const { deviceId } = request.body;
    if (!deviceId) return reply.code(400).send({ error: 'Missing deviceId' });
    const success = deviceStore.unpair(deviceId);
    return { success };
  }
);

// 6. Device Heartbeat (called periodically by CCTV device)
fastify.post<{ Body: { deviceId: string; health: DeviceHealth } }>(
  '/api/heartbeat',
  async (request, reply) => {
    const { deviceId, health } = request.body;
    if (!deviceId || !health) {
      return reply.code(400).send({ error: 'Missing deviceId or health payload' });
    }
    const status = deviceStore.updateHealth(deviceId, health);
    return {
      success: true,
      computedStatus: status,
      serverTimestamp: Date.now(),
    };
  }
);

// 7. Get Paired CCTV Device Status (called by Admin device)
fastify.get<{ Params: { deviceId: string } }>(
  '/api/device/:deviceId/status',
  async (request, reply) => {
    const { deviceId } = request.params;
    const admin = deviceStore.getDevice(deviceId);
    if (!admin || !admin.pairedDeviceId) {
      return reply.code(404).send({ error: 'Device not found or not paired' });
    }

    const cctv = deviceStore.getDevice(admin.pairedDeviceId);
    if (!cctv) {
      return reply.code(404).send({ error: 'Paired CCTV phone not found' });
    }

    const statusCategory = deviceStore.computeDeviceStatus(cctv);

    return {
      cctvDeviceId: cctv.deviceId,
      statusCategory,
      lastSeenAt: cctv.lastSeenAt,
      health: cctv.health,
      activeSessionId: cctv.activeSessionId,
    };
  }
);

// 8. Start Monitoring Session (called by Admin device)
fastify.post<{
  Body: {
    adminDeviceId: string;
    mode: SessionMode;
  };
}>('/api/session/start', async (request, reply) => {
  const { adminDeviceId, mode } = request.body;
  if (!adminDeviceId || !mode || mode === 'idle') {
    return reply.code(400).send({ error: 'Missing adminDeviceId or invalid mode' });
  }

  const admin = deviceStore.getDevice(adminDeviceId);
  if (!admin || !admin.pairedDeviceId) {
    return reply.code(400).send({ error: 'Admin is not paired with any CCTV phone' });
  }

  const cctv = deviceStore.getDevice(admin.pairedDeviceId);
  if (!cctv) {
    return reply.code(404).send({ error: 'Paired CCTV device record not found' });
  }

  const cctvStatus = deviceStore.computeDeviceStatus(cctv);
  if (cctvStatus === 'OFFLINE') {
    return reply.code(503).send({
      error: 'CCTV phone is offline. Open Timer on the spare phone to resume standby.',
    });
  }

  // Issue LiveKit session tokens
  const tokens = await liveKitService.createTokens(cctv.deviceId, admin.deviceId, mode);

  // Send START_SESSION command to CCTV client over active WebSocket if available
  const cctvSocket = deviceStore.getSocket(cctv.deviceId);
  const commandPayload = {
    type: 'START_SESSION',
    mode,
    grant: tokens.cctvGrant,
    timestamp: Date.now(),
  };

  if (cctvSocket && cctvSocket.readyState === cctvSocket.OPEN) {
    cctvSocket.send(JSON.stringify(commandPayload));
  }

  return {
    success: true,
    mode,
    adminGrant: tokens.adminGrant,
    cctvGrant: tokens.cctvGrant, // Return cctv grant as fallback if signaling over socket is in-flight
  };
});

// 9. Stop Active Session (called by Admin or CCTV)
fastify.post<{ Body: { deviceId: string } }>(
  '/api/session/stop',
  async (request, reply) => {
    const { deviceId } = request.body;
    const dev = deviceStore.getDevice(deviceId);
    if (!dev) return reply.code(404).send({ error: 'Device not found' });

    const cctvDeviceId = dev.role === 'cctv' ? dev.deviceId : dev.pairedDeviceId;
    if (cctvDeviceId) {
      const cctvSocket = deviceStore.getSocket(cctvDeviceId);
      if (cctvSocket && cctvSocket.readyState === cctvSocket.OPEN) {
        cctvSocket.send(JSON.stringify({ type: 'STOP_SESSION', timestamp: Date.now() }));
      }
    }

    return { success: true };
  }
);

// 10. Execute Authenticated Remote Command (e.g. SWITCH_CAMERA)
fastify.post<{ Body: CommandRequest }>(
  '/api/command',
  async (request, reply) => {
    const cmd = request.body;
    if (!cmd.commandId || !cmd.targetDeviceId || !cmd.commandType) {
      return reply.code(400).send({ error: 'Malformed command request' });
    }

    const cctv = deviceStore.getDevice(cmd.targetDeviceId);
    if (!cctv) return reply.code(404).send({ error: 'Target device not found' });

    const cctvSocket = deviceStore.getSocket(cmd.targetDeviceId);
    if (!cctvSocket || cctvSocket.readyState !== cctvSocket.OPEN) {
      return reply.code(503).send({
        error: 'CCTV phone signaling connection is not active. Push command failed.',
      });
    }

    cctvSocket.send(JSON.stringify(cmd));
    return { success: true, queued: true, commandId: cmd.commandId };
  }
);

// 11. Real-time WebSocket Signaling Hub
fastify.get('/ws', { websocket: true }, (socket, req) => {
  let attachedDeviceId: string | null = null;

  socket.on('message', (message: Buffer | string) => {
    try {
      const data = JSON.parse(message.toString());

      if (data.type === 'IDENTIFY' && data.deviceId) {
        attachedDeviceId = data.deviceId;
        deviceStore.registerSocket(data.deviceId, socket);
        socket.send(
          JSON.stringify({ type: 'IDENTIFIED', timestamp: Date.now() })
        );
      } else if (data.type === 'COMMAND_CONFIRMATION') {
        const conf = data as CommandConfirmation;
        // Forward confirmation to paired admin if connected
        if (attachedDeviceId) {
          const dev = deviceStore.getDevice(attachedDeviceId);
          if (dev && dev.pairedDeviceId) {
            const adminSocket = deviceStore.getSocket(dev.pairedDeviceId);
            if (adminSocket && adminSocket.readyState === adminSocket.OPEN) {
              adminSocket.send(JSON.stringify(conf));
            }
          }
        }
      } else if (data.type === 'PING') {
        socket.send(JSON.stringify({ type: 'PONG', timestamp: Date.now() }));
      }
    } catch (err) {
      fastify.log.error(err, 'Failed to parse WebSocket message');
    }
  });

  socket.on('close', () => {
    if (attachedDeviceId) {
      deviceStore.removeSocket(attachedDeviceId);
    }
  });
});

// Start Server
try {
  await fastify.listen({ port, host });
  fastify.log.info(`Timer Backend Service running at http://${host}:${port}`);
} catch (err) {
  fastify.log.error(err);
  process.exit(1);
}
