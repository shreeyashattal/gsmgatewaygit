<div align="center">
  <div style="background: #000; padding: 20px; border-radius: 20px; display: inline-block;">
    <h1 style="color: white; margin: 0;">SHREEYASH GSM-SIP GATEWAY</h1>
    <p style="color: #3b82f6; font-family: monospace; font-size: 1.2em;">V2.4.0-ROOT-STABLE</p>
  </div>
</div>

<br/>

## 📡 Project Overview

This application serves as a high-performance **Dual-SIM GSM-to-SIP Gateway**. It bridges cellular voice calls to VoIP networks (PBX) using rooted Android hardware.

### 🚀 Key Capabilities

*   **Dual-SIM Multiplexing**: Intelligent routing handling two active SIM cards simultaneously.
*   **Kernel-Level Audio Bridge**: Low-latency PCM audio routing via ALSA/TinyMix integration (`NativeBridge`).
*   **Native SIP Stack (PJSIP)**:
    *   **Robust Signaling**: Uses PJSIP (C++) for standards-compliant SIP handling (INVITE, REGISTER, AUTH).
    *   **Codec Support**: G.711 PCMU/PCMA, G.722 (Wideband), and OPUS.
    *   **Modes**:
        *   **Server Mode**: Listens for incoming trunk connections from PBX.
        *   **Client Mode**: Registers to an external SIP Proxy/PBX.
*   **Adaptive Jitter Buffer**: Real-time audio metrics monitoring (Latency, Jitter, Packet Loss).
*   **AI Diagnostics**: Integration with Google Gemini Flash for log analysis and heuristic troubleshooting.

---

## 🛠️ Architecture

The system is composed of several high-performance modules:

1.  **GatewayDaemon**: The central nervous system managing state, config persistence, and event dispatching.
2.  **SipStack**: TypeScript controller that bridges to the native PJSIP layer.
3.  **PjsipService (Native)**: Android Service running the PJSIP stack for reliable background signaling.
4.  **AudioEngine**: Manages real-time metrics and bridge status.
5.  **NativeBridge**: Hardware Abstraction Layer (HAL) interfacing with Qualcomm/MediaTek modem commands via `su`.

---

## 📦 Deployment & Build

This project is built using **Vite** + **React 19** + **Capacitor**.

### Prerequisites

*   Node.js v18+
*   Android Studio
*   Java JDK 17

### Installation

```bash
npm install
```

### Configuration

Create a `.env.local` file in the root directory for AI features:

```env
GEMINI_API_KEY=your_google_api_key_here
```

### Development Server

Run the gateway dashboard locally:

```bash
npm run dev
```

---

## 📱 Building the Android APK

This project uses **Capacitor** to wrap the web application into a native Android APK.

### Steps to Generate APK

1.  **Build the Web Assets**:
    ```bash
    npm run build
    ```

2.  **Sync Web Assets to Android**:
    ```bash
    npx cap sync
    ```

3.  **Open Android Studio**:
    ```bash
    npx cap open android
    ```

4.  **Build APK**:
    *   Wait for Gradle Sync to complete.
    *   Go to `Build` > `Build Bundle(s) / APK(s)` > `Build APK(s)`.
    *   The APK will be generated in `android/app/build/outputs/apk/debug/app-debug.apk`.

---

## ⚠️ Hardware Requirements

*   **Root Access**: **REQUIRED** for `tinymix` audio routing and `service call telephony` commands.
*   **SoC**: Optimized for Qualcomm Snapdragon (sdm/msm) chipsets.
*   **Network**: Static LAN IP recommended for SIP Server mode.

---

<div align="center">
  <sub>Engineered for High-Availability Telephony</sub>
</div>
