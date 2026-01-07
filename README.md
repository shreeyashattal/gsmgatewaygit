<div align="center">
  <div style="background: #000; padding: 20px; border-radius: 20px; display: inline-block;">
    <h1 style="color: white; margin: 0;">SHREEYASH GSM-SIP GATEWAY</h1>
    <p style="color: #3b82f6; font-family: monospace; font-size: 1.2em;">V2.4.0-ROOT-STABLE</p>
  </div>
</div>

<br/>

## 📡 Project Overview

This application serves as a high-performance **Dual-SIM GSM-to-SIP Gateway**. It is designed to run on rooted Android hardware (simulated environment) to bridge cellular voice calls to VoIP networks (PBX).

### 🚀 Key Capabilities

*   **Dual-SIM Multiplexing**: Intelligent routing handling two active SIM cards simultaneously.
*   **Kernel-Level Audio Bridge**: Simulates low-latency PCM audio routing via ALSA/TinyMix integration (`NativeBridge`).
*   **SIP Trunking**:
    *   **Server Mode**: Listens on a local port for PBX registrations (Static IP Trunking).
    *   **Client Mode**: Registers explicitly to a remote SIP Proxy.
*   **PJSIP Signaling Stack**: Custom TypeScript implementation of SIP state machine (INVITE, REGISTER, BYE, ACK, 401 Auth).
*   **Adaptive Jitter Buffer**: Real-time audio metrics monitoring (Latency, Jitter, Packet Loss).
*   **AI Diagnostics**: Integration with Google Gemini Flash for log analysis and heuristic troubleshooting.

---

## 🛠️ Architecture

The system is composed of several high-performance modules:

1.  **GatewayDaemon**: The central nervous system managing state, config persistence, and event dispatching.
2.  **SipStack**: Handles complex SIP signaling scenarios including authentication challenges and re-registration.
3.  **AudioEngine**: Simulates the real-time RTP stream processing loop with jitter correction.
4.  **NativeBridge**: Hardware Abstraction Layer (HAL) interfacing with Qualcomm/MediaTek modem commands via `su`.

---

## 📦 Deployment & Build

This project is built using **Vite** + **React 19**.

### Prerequisites

*   Node.js v18+
*   NPM

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

### Production Build

To generate the optimized production artifacts:

```bash
npm run build
```

The output will be in the `dist/` directory, ready to be deployed to a web server or wrapped in a WebView container.

---

## 📱 Building the Android APK

This project uses **Capacitor** to wrap the web application into a native Android APK.

### Prerequisites for APK Build
*   **Android Studio** installed on your machine.
*   **Java/JDK 17** installed.

### Steps to Generate APK

1.  **Build the Web Assets**:
    ```bash
    npm run build
    ```

2.  **Initialize Android Platform**:
    ```bash
    npx cap add android
    ```

3.  **Sync Web Assets to Android**:
    ```bash
    npx cap sync
    ```

4.  **Open Android Studio**:
    ```bash
    npx cap open android
    ```

5.  **Build APK**:
    *   **Wait for Gradle Sync**: When Android Studio opens, look at the bottom status bar. Wait until the indexing and Gradle Sync processes are complete. The "Build" menu will be incomplete until this finishes.
    *   **If Sync Fails/Doesn't Start**: Go to `File` > `Sync Project with Gradle Files`.
    *   Once synced: Go to `Build` > `Build Bundle(s) / APK(s)` > `Build APK(s)`.
    *   The APK will be generated in `android/app/build/outputs/apk/debug/app-debug.apk`.

---

## ⚠️ Hardware Requirements (Simulation)

While this dashboard runs in a browser, the underlying logic assumes:

*   **Root Access**: Required for `tinymix` and `service call telephony` commands.
*   **SoC**: Optimized for Qualcomm Snapdragon (sdm/msm) chipsets.
*   **Network**: Static LAN IP recommended for SIP Server mode.

---

<div align="center">
  <sub>Engineered for High-Availability Telephony</sub>
</div>
