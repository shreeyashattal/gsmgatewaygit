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

export enum SipStatus {
  UNREGISTERED = 'UNREGISTERED',
  REGISTERING = 'REGISTERING',
  REGISTERED = 'REGISTERED',
  LISTENING = 'LISTENING', // For Server Mode
  REJECTED = 'REJECTED',
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
export type GatewayRole = 'CLIENT' | 'SERVER';

export interface LogEntry {
  id: string;
  timestamp: string;
  level: 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';
  tag: string;
  message: string;
}

export interface TrunkConfig {
  mode: GatewayRole;
  sipServer: string; // Remote PBX IP or Local Listen Bind
  sipPort: number;
  sipUser: string;
  sipPass: string;
  sipTrunkName: string;
  codec: 'PCMU' | 'PCMA' | 'OPUS' | 'G722';
  regExpiry: number;
  enabled: boolean;
  serviceActive: boolean; // Per-SIM toggle
  stunServer: string;
  useIce: boolean;
}

export interface GatewayConfig {
  trunks: [TrunkConfig, TrunkConfig];
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
  sipAddress: string;
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
}

export interface BackendMetrics {
  cpuUsage: number;
  memUsage: number;
  temp: number;
  sims: [SimMetrics, SimMetrics];
  slotCount: number; // 1 or 2
  uptime: number;
  regTimeRemaining: [number, number];
  processor: ProcessorType;
  isRooted: boolean;
}
