# GSM-SIP Gateway for Android

A rooted Android application that turns a dual-SIM phone into a VoIP trunk gateway, bridging GSM calls to/from a SIP PBX.

## Project Intent

Transform a **rooted Redmi Note 10 (dual-SIM)** into a GSM gateway that:
- Exposes both SIM cards as SIP trunks to a PBX (e.g., FreePBX, Asterisk)
- Routes incoming GSM calls → SIP (to PBX)
- Routes outgoing SIP calls → GSM (from PBX)
- Bridges audio between GSM voice channel and RTP streams

### Use Case
```
                    ┌─────────────────┐
   PSTN ←──GSM──→   │  Android Phone  │  ←──SIP/RTP──→  PBX  ←──→  VoIP Clients
   (Carriers)       │  (Dual SIM)     │
                    │  - SIM1 → gsm1  │
                    │  - SIM2 → gsm2  │
                    └─────────────────┘
```

## Architecture

### Components

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           GatewayService                                 │
│  (Main orchestrator - Foreground Service)                               │
│                                                                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────────┐  │
│  │  SIPClient   │  │ CallSession  │  │  NativePCMAudioBridge        │  │
│  │  (UDP:5080)  │  │  (per-call)  │  │  (RTP ↔ Voice Audio)         │  │
│  └──────────────┘  └──────────────┘  └──────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
         │                    │                        │
         │                    │                        │
         ▼                    ▼                        ▼
┌──────────────────┐  ┌───────────────────┐  ┌─────────────────────────┐
│ GatewayConnServ  │  │ GatewayInCallServ │  │    RootAudioRouter      │
│ (ConnectionMgr)  │  │ (Call Events)     │  │  (Mixer path control)   │
└──────────────────┘  └───────────────────┘  └─────────────────────────┘
```

### Key Classes

| Class | Purpose |
|-------|---------|
| `GatewayService` | Main foreground service, orchestrates call flows, manages sessions |
| `SIPClient` | Lightweight SIP UA - handles REGISTER, INVITE, BYE, ACK, RTP negotiation |
| `CallSession` | Tracks state of a bridged call (GSM side + SIP side) |
| `GatewayConnectionService` | Android Telecom ConnectionService - registered as SimCallManager |
| `GatewayConnection` | Wrapper Connection for managing calls via Telecom framework |
| `GatewayInCallService` | InCallService for receiving call events and answering calls |
| `NativePCMAudioBridge` | Captures voice audio and bridges to RTP (and vice versa) |
| `RootAudioRouter` | Uses root to configure audio mixer paths for call audio capture |
| `CallReceiver` | BroadcastReceiver for legacy phone state events |

### Call Flows

#### Incoming GSM Call (GSM → SIP)
```
1. GSM call rings on phone
2. CallReceiver/GatewayInCallService detects incoming call
3. GatewayService.handleIncomingGSMCall() creates CallSession
4. SIPClient sends INVITE to PBX (with GSM caller ID)
5. PBX answers (200 OK)
6. GatewayService answers GSM call via InCallService.Call.answer()
7. Audio bridge starts (GSM voice ↔ RTP)
8. Either party hangs up → BYE sent, call ended
```

#### Outgoing GSM Call (SIP → GSM)
```
1. PBX sends INVITE to gateway (e.g., INVITE sip:+1234567890@gateway)
2. SIPClient receives INVITE, extracts destination number
3. GatewayService.handleIncomingSIPCall() creates CallSession
4. Gateway places GSM call to destination
5. GSM party answers
6. GatewayService sends 200 OK to PBX
7. Audio bridge starts (RTP ↔ GSM voice)
8. Either party hangs up → call ended
```

### Privileged Permissions (via Magisk Module)

The app requires these privileged permissions (granted via priv-app installation):

| Permission | Purpose |
|------------|---------|
| `CAPTURE_AUDIO_OUTPUT` | Capture voice call audio (VOICE_CALL source) |
| `CONTROL_INCALL_EXPERIENCE` | Bind as InCallService to receive call events |
| `MODIFY_PHONE_STATE` | Answer/end calls, modify telephony state |
| `READ_PRECISE_PHONE_STATE` | Detailed call state information |
| `BIND_TELECOM_CONNECTION_SERVICE` | Register as connection manager |
| `MODIFY_AUDIO_ROUTING` | Control audio paths |

### Magisk Module Structure

```
gsm-sip-gateway-magisk/
├── module.prop
├── customize.sh
├── service.sh                    # Boot-time permission grants
├── system/
│   ├── priv-app/
│   │   └── GSMGateway/
│   │       └── app-debug.apk
│   └── etc/
│       ├── permissions/
│       │   └── privapp-permissions-gsm-sip-gateway.xml
│       └── default-permissions/
│           └── gsm-gateway-default-permissions.xml
```

## Current Status

### Accomplished

- [x] **Magisk module deployment** - App installs as privileged system app
- [x] **Privileged permissions granted** - CAPTURE_AUDIO_OUTPUT, CONTROL_INCALL_EXPERIENCE, etc.
- [x] **SIP registration working** - PBX registers with gateway, learns PBX address
- [x] **SIP signaling complete** - INVITE, 180, 200 OK, ACK, BYE all working
- [x] **Default dialer role** - App is set as default dialer
- [x] **InCallService binding** - Receives call added/removed/state events
- [x] **ConnectionService registration** - Registered as SimCallManager
- [x] **Call session tracking** - Proper state machine for call lifecycle
- [x] **RTP endpoint negotiation** - SDP parsing, port extraction working

### Not Working (Pending Issues)

#### 1. Outgoing GSM Calls (SIP → GSM) - CRITICAL
**Problem:** When PBX sends INVITE, the GSM call is never actually placed to the destination.

**Root Cause:**
- App is registered as `SimCallManager` (CAPABILITY_CONNECTION_MANAGER)
- When we call `TelecomManager.placeCall()`, it routes back to our `GatewayConnectionService.onCreateOutgoingConnection()`
- Our wrapper Connection is created but doesn't actually dial the GSM network
- The real `TelephonyConnectionService` is never invoked to place the call

**Attempted Solutions (Failed):**
- `ITelephony.call()` via reflection → Blocked by hidden API restrictions
- `TelecomManager.placeCall()` → Routes back to us (loop)
- Return `null` from `onCreateOutgoingConnection()` → Call fails with `IMPL_RETURNED_NULL_CONNECTION`
- Shell command `am start -a android.intent.action.CALL` → Background activity blocked or routes through us

**What's Needed:**
- Find a way to place GSM calls that bypasses our SimCallManager role
- Or: Don't register as SimCallManager (but then lose InCallService access)
- Or: Use low-level telephony APIs (RIL/modem commands) via root

#### 2. Incoming GSM Calls - Answer Not Working
**Problem:** When GSM call comes in and SIP side answers, the GSM call is not actually answered (caller keeps hearing ringing).

**What Logs Show:**
- `Call.answer(VideoProfile.STATE_AUDIO_ONLY)` is called successfully
- Telecom shows state: RINGING → ANSWERED → ACTIVE
- But GSM caller still hears ringing

**Root Cause (Suspected):**
- We're answering our wrapper `GatewayConnection`, not the underlying `TelephonyConnection`
- The answer command doesn't propagate to the actual GSM call
- Or: There's a timing issue with InCallService binding

**What's Needed:**
- Investigate if InCallService's `Call.answer()` actually answers the underlying call
- Try answering via the TelephonyConnection directly
- Check if there's a different API needed for VoLTE/IMS calls

#### 3. Audio Bridging - Not Tested
**Problem:** Cannot test audio bridging because calls aren't connecting properly.

**Architecture:**
- `NativePCMAudioBridge` - Captures from `VOICE_CALL` AudioSource, sends via RTP
- `RootAudioRouter` - Configures mixer paths for voice capture/injection
- Playback injects audio into `VOICE_COMMUNICATION` stream

**Potential Issues:**
- VOICE_CALL capture may not work even with CAPTURE_AUDIO_OUTPUT (device-specific)
- Mixer path configuration may be wrong for this device
- May need to use tinycap/tinyplay directly via root

## Technical Notes

### Why SimCallManager?

We register as `SimCallManager` because:
1. It allows us to receive call events via `InCallService`
2. It gives us `CONTROL_INCALL_EXPERIENCE` which is required for voice capture
3. Without it, we can't access the VOICE_CALL audio source

The downside is that ALL calls (incoming and outgoing) route through our ConnectionService.

### Device-Specific Considerations

- **Redmi Note 10** with MIUI
- VoLTE/IMS is used for calls (not legacy CS calls)
- MIUI has additional call management layers (MiuiCallsManager)
- Audio HAL is Qualcomm-based

### Alternative Approaches to Consider

1. **Don't be SimCallManager**: Register only as a regular ConnectionService, not as the call manager. Place calls directly via TelephonyConnectionService. Capture audio via root-level commands (tinycap).

2. **Use Xposed/LSPosed**: Hook into TelephonyConnectionService to intercept and control calls at a lower level.

3. **Direct RIL Commands**: Use root to send AT commands or RIL socket commands to the modem directly.

4. **Modified Phone App**: Fork AOSP Phone app and add gateway functionality directly.

## Building

```bash
# Build debug APK
./gradlew assembleDebug

# Build Magisk module
./build-magisk-module.sh

# Install via Magisk
adb push gsm-sip-gateway-magisk.zip /sdcard/Download/
# Then install via Magisk app and reboot
```

## Configuration

Edit `Config.java` to set:
- SIP listening port (default: 5080)
- RTP port range
- SIP usernames for each SIM

The PBX should:
1. Register to the gateway's IP:5080 as user `gsm1` or `gsm2`
2. Send INVITEs to dial out via GSM
3. Receive INVITEs for incoming GSM calls

## Logs

```bash
# All gateway logs
adb logcat -s GatewayService:* SIPClient:* GatewayConnService:* GatewayInCallService:*

# Call flow
adb logcat | grep -E "(INVITE|BYE|RINGING|ACTIVE|BRIDGED)"

# Telecom framework
adb logcat | grep Telecom
```

## License

[To be determined]

## Contributors

- Development assisted by Claude (Anthropic)
