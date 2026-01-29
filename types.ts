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

export enum SIPRegistrationState {
  NOT_REGISTERED = 'NOT_REGISTERED',
  REGISTERING = 'REGISTERING',
  REGISTERED = 'REGISTERED',
  REGISTRATION_FAILED = 'REGISTRATION_FAILED',
  UNREGISTERING = 'UNREGISTERING'
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
  sipUsername: string;          // SIP username for this SIM (e.g., "gsm_sim1")
  sipPassword: string;          // SIP password for authentication
  pbxHost: string;              // PBX IP/hostname for this SIM
  pbxPort: number;              // PBX SIP port (default 5060)
  localSipPort: number;         // Local SIP listening port (e.g., 5061 for SIM1)
  codec: 'PCMU' | 'PCMA' | 'OPUS' | 'G722';
  rtpPort: number;              // RTP port for this channel (10000 or 10002)
  registrationInterval: number; // REGISTER interval in seconds (default 3600)
  registerTimeout: number;      // Registration timeout in seconds (default 30)
  enableTLS: boolean;           // Use TLS for SIP
}

export interface GatewayConfig {
  channels: [ChannelConfig, ChannelConfig];
  autoAnswer: boolean;
  rootLevel: boolean;
  jitterBufferMs: number;
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
  sipRegistrationState: SIPRegistrationState;
  lastRegisteredTime?: number;
  nextRegisterTime?: number;
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
