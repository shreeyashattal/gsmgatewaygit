# GSM-SIP Gateway

<div align="center">
  <h2 style="font-size: 2.5em; margin: 20px 0; color: #3b82f6;">
    🌐 Professional Dual-SIM GSM-to-VoIP Trunk Gateway
  </h2>
  <p style="font-size: 1.1em; color: #9ca3af;">
    Convert GSM cellular lines into SIP trunks for enterprise PBX integration
  </p>
  <p style="font-size: 0.95em; color: #6b7280; margin-top: 10px;">
    <strong>Version:</strong> 2.4.0-ROOT-STABLE
  </p>
</div>

---

## 📋 Overview

**GSM-SIP Gateway** is a high-performance, production-grade telephony gateway that bridges cellular GSM lines with enterprise VoIP systems. It converts dual-SIM GSM voice channels into professional SIP trunks, enabling seamless integration between legacy cellular networks and modern IP-PBX systems (Asterisk, FreeSWITCH, 3CX, etc.).

### Core Concept

Each SIM card appears to your PBX as **one dedicated SIP trunk**, complete with:
- **Incoming DID termination** - GSM calls routed through your PBX dialplan
- **Outgoing origination** - Place GSM calls directly from your PBX
- **Real-time audio bridging** - Direct PCM audio between GSM modem and SIP RTP streams
- **Dual-SIM support** - Two independent trunks from a single device

---

## 🚀 Key Features

### Bidirectional Call Handling
- **Incoming GSM → SIP**: Incoming cellular calls appear as inbound SIP calls to your PBX (DIDs)
- **Outgoing SIP → GSM**: Place GSM calls from your PBX dialplan to any phone number
- **Call state synchronization** - Real-time call state between GSM and SIP layers

### Dual-SIM Multiplexing
- **Two independent SIM slots** - Each SIM is a separate SIP trunk
- **Parallel call support** - Two simultaneous calls (one per SIM)
- **Intelligent routing** - Automatic SIM selection or forced per-call routing

### Enterprise Integration
- **RFC 3261 SIP Compliance** - Full SIP User Agent with REGISTER, INVITE, BYE, CANCEL
- **SIP Trunk Registration** - Registers with any standard SIP-enabled PBX
- **SIP Authentication** - Digest authentication for secure trunk connections
- **Configurable endpoints** - PBX address, port, username, password per SIM

### High-Performance Audio Bridging
- **Kernel-level PCM routing** - Direct audio via ALSA/TinyALSA (tinycap/tinyplay)
- **Low-latency bridging** - Real-time audio from GSM modem to RTP streams
- **Hardware audio support** - Optimized for Qualcomm Snapdragon and MediaTek chipsets

### Real-Time Monitoring
- **Live call dashboard** - Active calls with caller ID, duration, RTP stats
- **Signal strength indicators** - Per-SIM signal quality and bar display
- **Audio bridge metrics** - PCM stream quality, packet loss, jitter
- **Event logging** - Complete audit trail of all calls and system events
- **Health monitoring** - PBX connection status, SIP registration state

### Administration
- **Web-based dashboard** - Responsive UI for configuration and monitoring
- **Per-SIM settings** - Independent PBX endpoints for each SIM
- **Live logs** - Real-time syslog viewer with filtering
- **Modem debugging** - Low-level GSM/SIP debugging tools
- **Architecture visualization** - System topology diagram

---

## 🏗️ Architecture

### Three-Layer Design

```
┌─────────────────────────────────────────────────────┐
│           WEB UI LAYER (React + TypeScript)         │
│  Dashboard | Call View | Settings | Logs | Debugger│
└────────────────────┬────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────┐
│          SERVICE LAYER (TypeScript/JS)              │
│  GatewayDaemon | Event Bus | State Management       │
└────────────────────┬────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────┐
│      ANDROID/NATIVE LAYER (Java + Capacitor)       │
│  ┌──────────────┐  ┌──────────────┐                │
│  │   SIM SLOT 1 │  │   SIM SLOT 2 │                │
│  ├──────────────┤  ├──────────────┤                │
│  │ SIPClient    │  │ SIPClient    │                │
│  │ (Registration)  (Registration) │                │
│  ├──────────────┤  ├──────────────┤                │
│  │ GSM Handler  │  │ GSM Handler  │                │
│  │ (Dialer)     │  │ (Dialer)     │                │
│  ├──────────────┤  ├──────────────┤                │
│  │ Audio Bridge │  │ Audio Bridge │                │
│  │ (PCM/ALSA)   │  │ (PCM/ALSA)   │                │
│  └──────────────┘  └──────────────┘                │
│           │                │                       │
│  ┌────────▼────────────────▼────────────┐          │
│  │    RTP Manager (UDP Sockets)        │          │
│  │    ALSA Root Audio Router (su)      │          │
│  └─────────────────────────────────────┘          │
│                    │                              │
│  ┌────────────────▼─────────────────┐             │
│  │  GSM Modem  │  Cellular Network  │             │
│  │  Audio      │  (Voice/Data)      │             │
│  └────────────────────────────────────┘             │
└─────────────────────────────────────────────────────┘
```

### Core Components

#### 1. **SIP Layer** (`sip/SIPClient.java`)
Full RFC 3261-compliant SIP User Agent implementation:
- **REGISTER**: Periodic registration with PBX
- **INVITE**: Handle incoming calls (PBX → Gateway) and initiate outgoing (Gateway → PBX)
- **200 OK / CANCEL / BYE**: Complete call signaling
- **Authentication**: Digest auth for trunk security
- **Concurrent calls**: Per-SIM call handling via thread pools
- **Retransmission**: RFC-compliant transaction management

#### 2. **GSM/Cellular Layer** (`GsmBridgePlugin.java`, `CallReceiver.java`)
Android Telephony integration:
- **GsmBridgePlugin.java**: Capacitor plugin for placing GSM calls via Telecom API
- **CallReceiver.java**: System broadcast receiver for detecting incoming GSM calls
- **DualSIMManager.java**: Multi-SIM detection and slot management
- **Subscription API**: Maps SIM slots to subscription IDs

#### 3. **Audio Bridging** (`NativePCMAudioBridge.java`, `RootAudioRouter.java`)
Real-time PCM audio routing:
- **NativePCMAudioBridge.java**:
  - Records GSM audio via `tinycap` (ALSA recording)
  - Plays SIP audio via `tinyplay` (ALSA playback)
  - Real-time PCM streaming in threads
  
- **RootAudioRouter.java**:
  - Configures ALSA mixer via `tinymix` commands
  - Executed with root privileges (`su`)
  - Sets audio routes/paths for modem and speakers
  - Persistent configuration during calls

- **SystemAudioBridge.java**:
  - Switches Android audio mode to call mode
  - Silences ringer during gateway operation
  - Manages audio focus

#### 4. **RTP Management** (`RTPManager.java`)
UDP socket management for real-time audio:
- UDP socket creation on dynamic ports
- RTP stream handling
- Codec selection and negotiation

#### 5. **Session Management** (`CallSession.java`)
Per-call state machine:
- Tracks GSM call ↔ SIP call mapping
- Manages call states (ringing, answered, ended)
- RTP endpoint tracking (address:port)
- Call timing and metrics

#### 6. **Main Orchestrator** (`GatewayService.java`)
Central service managing the entire gateway:
- Initializes SIP clients (one per SIM)
- Coordinates incoming/outgoing call flows
- Manages active sessions
- Implements call state machines
- Handles audio bridge lifecycle
- Monitors PBX registration state

#### 7. **Frontend Dashboard** (React + TypeScript)
Modern web UI for:
- Real-time call monitoring with caller ID
- Per-SIM signal strength and quality metrics
- Audio bridge RTP statistics
- Live event logging
- Configuration management
- Architecture visualization
- Modem debugging tools

---

## 📞 Call Flows

### **Incoming GSM Call → SIP INVITE to PBX**

```
1. [GSM ring detected on SIM1]
   ↓
2. [CallReceiver broadcasts incoming call event]
   ↓
3. GatewayService.handleIncomingGSMCall(simSlot=0, callerNumber="+1234567890")
   ↓
4. Create CallSession linking GSM → SIP
   ↓
5. SIPClient[0].sendInvite(callerNumber) to PBX
   • From: <sip:+1234567890@gateway>
   • To: <sip:trunk0@pbx.example.com>
   • Offer SDP with RTP endpoints
   ↓
6. [PBX rings extension/IVR]
   ↓
7. [If PBX answers with 200 OK]
   ↓
8. GatewayService.acceptGSMCall() - Answer the cellular call
   ↓
9. Start NativePCMAudioBridge
   • tinycap records GSM modem audio
   • tinyplay plays RTP stream to modem
   ↓
10. Audio flows: [Caller] ←SIP RTP→ [Gateway] ←PCM/ALSA→ [Cell Network]
```

### **Outgoing GSM Call ← SIP INVITE from PBX**

```
1. [PBX sends SIP INVITE to gateway]
   • INVITE sip:+5551234567@gateway.local
   • To: <sip:+5551234567@gateway>
   ↓
2. SIPClient[0].onInviteReceived(sipCall)
   ↓
3. Extract dialed number from INVITE URI
   ↓
4. GatewayService.handleIncomingSIPCall(simSlot=0, dialedNumber="+5551234567")
   ↓
5. Create CallSession linking SIP → GSM
   ↓
6. GsmBridgePlugin.placeCall(phoneNumber="+5551234567", slot=0)
   • Uses TelecomManager API
   • Places call on SIM1
   ↓
7. [Remote party answers GSM call]
   ↓
8. CallSession detects GSM call answered
   ↓
9. SIPClient[0].send200OK() to PBX
   • Answer SDP with RTP endpoints
   ↓
10. Start NativePCMAudioBridge
    • tinycap records GSM modem audio
    • tinyplay plays RTP stream to modem
    ↓
11. Audio flows: [Caller on PBX] ←RTP→ [Gateway] ←PCM/ALSA→ [Called Party]
```

---

## 🛠️ Technology Stack

### Frontend
- **React 19** - Modern UI framework
- **TypeScript** - Type-safe development
- **Vite** - Lightning-fast build tool
- **Tailwind CSS** - Utility-first styling
- **Capacitor** - Web-to-native bridge

### Backend Services
- **TypeScript/JavaScript** - Daemon and services
- **Event bus** - Real-time state synchronization
- **Local storage** - Configuration persistence

### Android/Native
- **Java** - Android service layer
- **Android Telephony API** - GSM/SIM management
- **Android Telecom API** - Call control
- **ALSA/TinyALSA** - Kernel audio routing
- **Root (su)** - Privileged audio configuration
- **Capacitor** - TypeScript ↔ Java bridge

### SIP Layer
- **RFC 3261** - Full SIP compliance
- **UDP/RTP** - Real-time audio transport
- **SDP** - Session description
- **Digest Authentication** - Trunk security

### Audio
- **ALSA (Advanced Linux Sound Architecture)** - Kernel audio subsystem
- **TinyALSA utilities**:
  - `tinycap` - PCM recording
  - `tinyplay` - PCM playback
  - `tinymix` - Mixer configuration

---

## 📋 Prerequisites

### Hardware
- **Android device** with:
  - **Dual SIM support** (nano SIM or eSIM + nano SIM)
  - **Qualcomm Snapdragon** or **MediaTek** chipset (with ALSA audio support)
  - **Root access** (rooted via Magisk, LineageOS, or manufacturer unlock)
  - Minimum **6GB RAM**, **2GB storage** for app and cache

### Software
- **Node.js** v18+ (for web development)
- **Java JDK** 17+ (for Android builds)
- **Android Studio** 2023.1+ (Gradle 8.1+)
- **Rooted Android OS** with adb access

### Network
- **PBX Server** (Asterisk, FreeSWITCH, 3CX, Kamailio, etc.) on your network
  - SIP port open (default 5060, or custom)
  - Trunk registration enabled
- **WiFi or LAN** connection for PBX communication
  - Local network access to PBX

---

## 📦 Installation & Build

### 1. Prerequisites Setup

```bash
# Install Node dependencies
npm install

# Create .env.local for future features (optional)
echo "GEMINI_API_KEY=your_key_here" > .env.local
```

### 2. Web Asset Build

```bash
# Build optimized web assets
npm run build

# Or development with hot reload
npm run dev
```

### 3. Sync to Android

```bash
# Sync web assets to Capacitor Android project
npx cap sync
```

### 4. Android APK Build

```bash
# Open Android Studio
npx cap open android
```

Then in Android Studio:
1. Wait for Gradle sync to complete
2. Select `Build` → `Build Bundle(s) / APK(s)` → `Build APK(s)`
3. Generated APK: `android/app/build/outputs/apk/debug/app-debug.apk`

### 5. Device Installation

```bash
# Install APK on device
adb install android/app/build/outputs/apk/debug/app-debug.apk

# Or on release:
adb install android/app/build/outputs/apk/release/app-release.apk
```

---

## ⚙️ Configuration

### First Time Setup

1. **Open app on device**
2. **Configure each SIM**:
   - Go to Settings tab
   - For each SIM slot (SIM1, SIM2):
     - **PBX Host**: IP or hostname (e.g., `192.168.1.50`)
     - **PBX Port**: SIP port (default: `5060`)
     - **Username**: SIP username/trunk ID (e.g., `gsm_sim1`)
     - **Password**: SIP password
     - **Local SIP Port**: Port gateway listens on (e.g., `5061` for SIM1, `5062` for SIM2)

3. **Save and restart** - Gateway will register trunks immediately

### PBX Configuration

**Asterisk example** (`/etc/asterisk/sip.conf`):
```ini
[gsm_sim1]
type=peer
host=192.168.1.100          ; Gateway IP
port=5061                   ; Gateway SIP port for SIM1
fromuser=gsm_sim1
secret=YourPassword
context=from-trunk          ; Dialplan context for incoming calls
insecure=port,invite        ; Allow calls without auth verification

[gsm_sim1_outbound]
type=friend
host=dynamic
context=from-trunk
```

**Dialplan example** (`/etc/asterisk/extensions.conf`):
```ini
[from-trunk]
; Incoming GSM calls
exten => _X.,1,Goto(main,${EXTEN},1)

[outgoing-to-gsm]
; Outgoing GSM calls
exten => _1NXXNXXXXXX,1,Dial(SIP/${EXTEN}@gsm_sim1)
exten => _1NXXNXXXXXX,n,Hangup()
```

---

## 📊 Monitoring & Debugging

### Dashboard Tabs

1. **Status**: Real-time call monitoring
   - Per-SIM signal strength and quality
   - Active calls with caller ID and duration
   - RTP bridge statistics (jitter, packet loss, bitrate)
   - PBX registration status

2. **Config**: Trunk configuration
   - Per-SIM PBX credentials and endpoints
   - Local listening ports
   - Audio settings
   - Call routing preferences

3. **Logs**: Real-time event log
   - Call setup/teardown events
   - SIP signaling messages (optional detailed view)
   - Audio bridge events
   - Error conditions
   - Configurable filtering by level and component

4. **Modem Debugger**: Low-level diagnostics
   - Raw SIP message inspection
   - GSM/modem command testing
   - Audio device debugging
   - Signal measurement tools

---

## 🔒 Security Considerations

### Root Access
- **Required for**: Audio routing via `tinymix`, `tinycap`, `tinyplay`
- **Risk**: Standard Magisk/rooted device risks
- **Mitigation**: Use trusted ROM, verify APK integrity

### SIP Authentication
- **Digest auth** supported for trunk security
- **Encrypted channels**: Use TLS (configure in PBX settings)
- **Firewall**: Restrict SIP ports to internal networks only

### Device Hardening
- Keep Android OS updated
- Use strong device PIN
- Restrict app permissions in system settings
- Monitor network access logs

---

## 🚀 Performance Notes

### Call Capacity
- **2 simultaneous calls** (one per SIM)
- **Call setup time**: ~2-3 seconds (GSM + SIP)
- **Audio latency**: ~100-150ms (PCM bridging)

### Resource Usage
- **Memory**: ~150-200MB baseline, +50MB per active call
- **CPU**: Low baseline, spikes during call setup
- **Network**: ~80Kbps per call (G.711 codec)

### Optimization Tips
- **Separate local network** for PBX and gateway (lower jitter)
- **WiFi 5GHz** for lower latency
- **Disable background apps** to reduce CPU contention
- **Use good signal area** for stable GSM connection

---

## 🐛 Troubleshooting

### App Won't Start
- Ensure device is **rooted** with **root access granted to app**
- Check Android version (minimum **Android 8.0**, recommended **Android 11+**)
- Verify APK installed correctly: `adb shell pm list packages | grep shreeyash`

### PBX Not Connecting
- Verify **PBX IP/port** reachable: `adb shell ping 192.168.1.50`
- Check **SIP port** open on PBX: `netstat -tlnp | grep 5060`
- Verify **credentials** (username/password) in settings
- Check firewall rules allow UDP 5060+

### No Audio on Calls
- Verify **root access** granted (Settings → Apps → Permissions)
- Check `/system/bin/tinycap` exists on device
- Test audio with: `adb shell su -c "tinycap /tmp/test.wav -D 0 -d 0 -c 1 -r 8000 -b 16 -p 160"`
- Verify **modem audio path** not routed to speaker

### Calls Drop Unexpectedly
- Monitor **WiFi signal** strength (poor signal → dropped calls)
- Check **PBX logs** for SIP errors: `asterisk -rvvv`
- Inspect **gateway logs** for audio bridge errors
- Verify **RTP ports** not firewalled between gateway and PBX

### High Latency/Jitter
- Move gateway closer to PBX (wired preferred)
- Check for **packet loss** in logs
- Verify **no other network-heavy apps** running
- Consider **dedicated VLAN** for telephony traffic

---

## 📝 Development

### Project Structure
```
├── android/                      # Android/Java layer
│   └── app/src/main/java/...
│       └── com/shreeyash/gateway/
│           ├── GatewayService.java
│           ├── sip/SIPClient.java
│           ├── NativePCMAudioBridge.java
│           └── ... [other components]
│
├── components/                   # React UI components
│   ├── Dashboard.tsx            # Status monitoring
│   ├── Settings.tsx             # Configuration
│   ├── Logs.tsx                 # Event logging
│   ├── CallView.tsx             # Call details
│   └── ModemDebugger.tsx        # Debugging
│
├── services/                     # TypeScript services
│   ├── GatewayDaemon.ts         # State management
│   └── ...
│
├── App.tsx                      # Main React app
├── types.ts                     # TypeScript types
└── constants.tsx                # App constants
```

### Running Development Server
```bash
npm run dev
```
Starts Vite dev server with hot module reload.

### Building for Production
```bash
npm run build
npx cap sync android
```

---

## 📄 License & Support

This project is designed for enterprise telephony integration.

---

## 🎯 Roadmap

- [ ] Web dashboard redesign (in progress)
- [ ] Advanced call routing rules
- [ ] Call recording to storage
- [ ] SMS support for 2G networks
- [ ] REST API for external integrations
- [ ] Cloud sync for configuration backup
- [ ] High-availability mode (cluster support)

---

<div align="center">
  <p style="color: #9ca3af; font-size: 0.9em; margin-top: 30px;">
    Enterprise-Grade GSM-to-VoIP Gateway | Production Ready
  </p>
</div>
