export enum CallState {
  IDLE = 'IDLE',
  INCOMING_GSM = 'INCOMING_GSM',
  OUTGOING_GSM = 'OUTGOING_GSM',
  INCOMING_ASTERISK = 'INCOMING_ASTERISK',
  OUTGOING_ASTERISK = 'OUTGOING_ASTERISK',
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
  asteriskContext: string;      // Dialplan context for incoming GSM calls
  defaultExtension: string;     // Default extension to dial
  codec: 'PCMU' | 'PCMA' | 'OPUS' | 'G722';
  rtpPort: number;              // RTP port for this channel (5004 or 5006)
}

export interface GatewayConfig {
  channels: [ChannelConfig, ChannelConfig];
  autoAnswer: boolean;
  rootLevel: boolean;
  jitterBufferMs: number;
  keepAliveInterval: number;
  speakerphoneOn: boolean;
  // AMI is hardcoded to 127.0.0.1:5038 - no config needed
}

export interface ActiveCall {
  id: string;
  simSlot: 0 | 1;
  gsmNumber: string;
  asteriskChannel: string;
  startTime: number;
  direction: 'GSM_TO_ASTERISK' | 'ASTERISK_TO_GSM';
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
