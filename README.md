<div align="center">
  <div style="background: #000; padding: 20px; border-radius: 20px; display: inline-block;">
    <h1 style="color: white; margin: 0;">SHREEYASH GSM-SIP GATEWAY</h1>
    <p style="color: #3b82f6; font-family: monospace; font-size: 1.2em;">V2.4.0-ROOT-STABLE</p>
  </div>
</div>

<br/>

## 📡 Project Overview

This application serves as a high-performance **Dual-SIM GSM-to-Asterisk Bridge**. It bridges cellular voice calls to Asterisk PBX running locally on rooted Android hardware.

### 🚀 Key Capabilities

*   **Dual-SIM Multiplexing**: Intelligent routing handling two active SIM cards simultaneously.
*   **Kernel-Level Audio Bridge**: Low-latency PCM audio routing via ALSA/TinyMix integration between GSM modem and Asterisk.
*   **Asterisk Integration**:
    *   **HTTP API Communication**: RESTful API for Asterisk dialplan integration.
    *   **Event-Driven Architecture**: Real-time call state synchronization between GSM and Asterisk.
    *   **Local PBX Support**: Optimized for Asterisk running on the same Android device.
*   **Intelligent Call Routing**:
    *   **Incoming GSM**: Auto-answer and bridge to Asterisk dialplan.
    *   **Outgoing GSM**: Asterisk-triggered GSM calls via API.
*   **Real-Time Audio Bridging**: Direct ALSA loopback between GSM baseband and Asterisk audio streams.
*   **AI Diagnostics**: Integration with Google Gemini Flash for log analysis and troubleshooting.

---

## 🛠️ Architecture

The system is composed of several high-performance modules:

1.  **GatewayDaemon**: The central nervous system managing state, config persistence, and event dispatching.
2.  **AsteriskBridge**: TypeScript service communicating with local Asterisk PBX via HTTP API.
3.  **AsteriskAPI**: RESTful HTTP server for Asterisk dialplan integration and call control.
4.  **AudioEngine**: Manages real-time audio bridging metrics between GSM and Asterisk streams.
5.  **NativeBridge**: Hardware Abstraction Layer (HAL) interfacing with Qualcomm/MediaTek modem commands via `su`.
6.  **Asterisk PBX**: Runs locally on Android device handling all VoIP signaling and routing.

---

## 📦 Deployment & Build

This project is built using **Vite** + **React 19** + **Capacitor**.

### Prerequisites

*   Node.js v18+
*   Android Studio
*   Java JDK 17
*   **Asterisk PBX** installed and running on the Android device
*   Root access for hardware control

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
*   **Asterisk**: Must be installed and configured on the Android device.
*   **SoC**: Optimized for Qualcomm Snapdragon (sdm/msm) chipsets with ALSA audio support.
*   **Network**: Local loopback communication with Asterisk (127.0.0.1).

---

<div align="center">
  <sub>Engineered for High-Availability Telephony</sub>
</div>
