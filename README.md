# Timer — Personal Remote Android CCTV System

An on-demand, battery-conscious remote monitoring system engineered for two personal Android phones:
- **Timer** (`com.timer.cctv`): Installed on the spare Android phone. Features a fully functional countdown timer UI and an on-demand, transparent CCTV client with background standby and telemetry reporting.
- **Timer Admin** (`com.timer.admin`): Installed on the primary Android phone. Provides the admin control dashboard, live health telemetry viewer, camera/audio live viewer, and authenticated remote commands.
- **Timer Backend** (`timer-backend`): A lightweight, deployable API and WebSocket signaling service hosted on Render that generates short-lived LiveKit access tokens and routes commands without relaying heavy media.

---

## Architecture & Data Flow

```
[ Timer (Spare Phone) ]  <--- (WebSockets / FCM Signaling) --->  [ Timer Backend (Render) ]
          |                                                               |
  LiveKit WebRTC Tracks (Camera / Mic)                                    |
          |                                                       Authenticated
          v                                                          Signaling
[ LiveKit Cloud / SFU ]  <=======================================>        |
          |                                                               |
  LiveKit WebRTC Tracks (Audio / Video)                                   |
          v                                                               v
[ Timer Admin (Primary Phone) ] <-----------------------------------------+
```

### Media Modes
1. **Camera Only**: Transmits live CameraX video. Microphone is untouched and no audio track is created.
2. **Audio Only**: Transmits live microphone audio. Camera hardware is unbinded and no video track is created.
3. **Camera + Audio**: Transmits both video and audio simultaneously.

---

## Project Structure

```
timer/
├── timer-backend/                # Node.js + TypeScript Fastify/Express API service
│   ├── src/
│   │   ├── index.ts              # Entry point & server boot
│   │   ├── routes/               # Pairing, sessions, health, commands
│   │   ├── services/             # LiveKit token generator, device store, ws hub
│   │   └── types.ts              # DTOs and contracts
│   ├── render.yaml               # Render Infrastructure-as-Code blueprint
│   ├── Dockerfile                # Production container build
│   └── package.json
│
└── timer-android/                # Gradle multi-module Android project
    ├── core/
    │   ├── model/                # Shared Kotlin DTOs (Health, Commands, Sessions)
    │   └── network/              # Shared HTTP/WebSocket client wrappers
    ├── app-cctv/                 # App 2: "Timer" (com.timer.cctv)
    │   ├── src/main/
    │   │   ├── java/com/timer/cctv/
    │   │   │   ├── ui/timer/     # Functional dummy timer UI & countdown logic
    │   │   │   ├── ui/cctv/      # Standby controls & pairing QR
    │   │   │   ├── service/      # Standby Foreground Service & Sensor manager
    │   │   │   ├── camera/       # CameraX capture & lens switcher
    │   │   │   ├── media/        # LiveKit publisher wrapper
    │   │   │   └── telemetry/    # Battery, screen, network monitors
    │   │   └── AndroidManifest.xml
    │   └── build.gradle.kts
    │
    └── app-admin/                # App 1: "Timer Admin" (com.timer.admin)
        ├── src/main/
        │   ├── java/com/timer/admin/
        │   │   ├── ui/dashboard/ # Device health dashboard
        │   │   ├── ui/viewer/    # Live stream player & controls (switch camera, stop)
        │   │   ├── ui/pairing/   # Device pairing & selection
        │   │   └── media/        # LiveKit subscriber wrapper
        │   └── AndroidManifest.xml
        └── build.gradle.kts
```

---

## Setup & Deployment Guide

### 1. Backend (Render)
1. Sign up for a free account at [Render](https://render.com).
2. Create a Web Service connected to your repository (pointing to root directory `timer-backend`).
3. Set environment variables:
   - `PORT`: `10000`
   - `LIVEKIT_URL`: (e.g. `wss://your-project.livekit.cloud`)
   - `LIVEKIT_API_KEY`: Your LiveKit API key
   - `LIVEKIT_API_SECRET`: Your LiveKit API secret
   - `ADMIN_SECRET`: A secure random string for master pairing authorization

### 2. LiveKit Cloud
1. Sign up for a free tier at [LiveKit Cloud](https://livekit.io).
2. Generate an API Key and Secret from the project settings.
3. Add the project WebSocket URL and keys to the backend environment variables.

### 3. Android Sideloading
1. Build debug or release APKs via Gradle:
   - `./gradlew :app-cctv:assembleDebug` -> installs as **Timer**
   - `./gradlew :app-admin:assembleDebug` -> installs as **Timer Admin**
2. Transfer and sideload the respective APKs onto each phone.
3. Open **Timer** on the spare phone, grant Camera/Mic/Notification permissions, and note the Pairing Code.
4. Open **Timer Admin** on the primary phone, enter the Backend URL and Pairing Code to establish connection.
