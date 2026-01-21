export enum CallState {
  IDLE = 'IDLE',
  INCOMING_GSM = 'INCOMING_GSM',
  OUTGOING_GSM = 'OUTGOING_GSM',
  INCOMING_SIP = 'INCOMING_SIP',
  OUTGOING_SIP = 'OUTGOING_SIP',
  BRIDGING = 'BRIDGING',
  TERMINATING = 'TERMINATING',
  ERROR = 'ERROR'
}

export enum BridgeStatus {
  DISCONNECTED = 'DISCONNECTED',
  CONNECTING = 'CONNECTING',
  CONNECTED = 'CONNECTED',
  ERROR = 'ERROR'
}

export enum GsmStatus {
  NOT_DETECTED = 'NOT_DETECTED',
  NO_SIM = 'NO_SIM',
  SEARCHING = 'SEARCHING',
  READY = 'READY',
  BUSY = 'BUSY'
}

export type ProcessorType = 'QUALCOMM' | 'MEDIATEK' | 'EXYNOS' | 'GENERIC';

export interface LogEntry {
  id: string;
  timestamp: string;
  level: 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';
  tag: string;
  message: string;
}

export interface ChannelConfig {
  enabled: boolean;
  sipUsername: string;          // SIP username for this SIM (e.g., "sim1")
  sipPassword: string;          // SIP password (optional for trunk mode)
  codec: 'PCMU' | 'PCMA' | 'OPUS' | 'G722';
  rtpPort: number;              // RTP port for this channel (10000 or 10002)
}

export interface GatewayConfig {
  channels: [ChannelConfig, ChannelConfig];
  pbxHost: string;              // PBX IP address (leave empty for trunk mode)
  pbxPort: number;              // SIP port (default 5060)
  localSipPort: number;         // Local SIP listen port (default 5080)
  trunkMode: boolean;           // If true, PBX registers with us instead
  autoAnswer: boolean;
  rootLevel: boolean;
  jitterBufferMs: number;
  keepAliveInterval: number;
  speakerphoneOn: boolean;
}

export interface ActiveCall {
  id: string;
  simSlot: 0 | 1;
  gsmNumber: string;
  sipCallId: string;
  startTime: number;
  direction: 'GSM_TO_SIP' | 'SIP_TO_GSM';
  durationSeconds: number;
  signaling: string[];
  audioMetrics: {
    latency: number;
    jitter: number;
    rxPackets: number;
    txPackets: number;
    bufferDepth: number;
    packetLoss?: number;
    underruns?: number;
    isClipping?: boolean;
  };
}

export interface SimMetrics {
  radioSignal: number;
  carrier: string;
  status: GsmStatus;
  phoneNumber: string;
  connectionType: 'Tower' | 'VoWiFi';
  networkType: string;  // '5G', 'LTE', '3G+', '3G', '2G', 'WiFi', 'Unknown'
}

export interface BackendMetrics {
  cpuUsage: number;
  memUsage: number;
  temp: number;
  sims: [SimMetrics, SimMetrics];
  slotCount: number; // 1 or 2
  uptime: number;
  processor: ProcessorType;
  isRooted: boolean;
  bridgeStatus: BridgeStatus;
}
