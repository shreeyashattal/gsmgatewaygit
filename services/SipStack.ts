import { SipStatus, TrunkConfig } from '../types';
import { Capacitor } from '@capacitor/core';

// Simplified Asterisk Manager Interface (AMI) communication
interface AsteriskAMI {
  connect(): Promise<boolean>;
  disconnect(): Promise<void>;
  originateCall(slot: number, destination: string): Promise<string>;
  hangupChannel(channel: string): Promise<boolean>;
  getChannelStatus(channel: string): Promise<string>;
  onCallEvent?: (event: any) => void;
}

// Mock AMI implementation for browser mode and initial setup
class MockAsteriskAMI implements AsteriskAMI {
  async connect(): Promise<boolean> {
    console.log('[ASTERISK_AMI] Mock: Connected to Asterisk');
    return true;
  }

  async disconnect(): Promise<void> {
    console.log('[ASTERISK_AMI] Mock: Disconnected from Asterisk');
  }

  async originateCall(slot: number, destination: string): Promise<string> {
    console.log(`[ASTERISK_AMI] Mock: Originating call to ${destination} from slot ${slot}`);
    return `SIP/trunk${slot+1}-${Date.now()}`;
  }

  async hangupChannel(channel: string): Promise<boolean> {
    console.log(`[ASTERISK_AMI] Mock: Hanging up channel ${channel}`);
    return true;
  }

  async getChannelStatus(channel: string): Promise<string> {
    return 'Up';
  }
}

// Real AMI implementation using HTTP API calls to Asterisk
class AsteriskAMIClient implements AsteriskAMI {
  private connected: boolean = false;
  private amiHost: string = '127.0.0.1';
  private amiPort: number = 5038;
  private amiUser: string = 'admin';
  private amiPass: string = 'admin';

  async connect(): Promise<boolean> {
    console.log('[ASTERISK_AMI] Connecting to Asterisk AMI...');
    // TODO: Implement real AMI TCP connection
    // For now, assume Asterisk is running and accessible
    this.connected = true;
    return true;
  }

  async disconnect(): Promise<void> {
    this.connected = false;
  }

  async originateCall(slot: number, destination: string): Promise<string> {
    if (!this.connected) {
      throw new Error('Not connected to Asterisk AMI');
    }

    // Use Asterisk's HTTP API or AMI command to originate call
    // This could use curl commands via shell or direct HTTP calls
    const channelId = `SIP/trunk${slot+1}/${Date.now()}`;

    // TODO: Send actual AMI Originate action or HTTP API call
    console.log(`[ASTERISK_AMI] Originating call: ${channelId} -> ${destination}`);

    return channelId;
  }

  async hangupChannel(channel: string): Promise<boolean> {
    if (!this.connected) {
      throw new Error('Not connected to Asterisk AMI');
    }

    // TODO: Send AMI Hangup action
    console.log(`[ASTERISK_AMI] Hanging up channel: ${channel}`);
    return true;
  }

  async getChannelStatus(channel: string): Promise<string> {
    // TODO: Query channel status from Asterisk
    return 'Up';
  }
}

export class AsteriskBridge {
  private status: SipStatus = SipStatus.UNREGISTERED;
  private amiClient: AsteriskAMI;
  private onStatusChange?: (status: SipStatus, remaining: number) => void;
  private onLog?: (log: string) => void;
  private onRemoteBye?: (callId: string) => void;
  private onIncomingInvite?: (callId: string, from: string) => void;
  private currentConfig: TrunkConfig | null = null;
  private activeChannels: Map<number, string> = new Map(); // slot -> channel ID

  constructor(callbacks: {
    onStatusChange: (s: SipStatus, rem: number) => void;
    onLog: (m: string) => void;
    onRemoteBye?: (callId: string) => void;
    onIncomingInvite?: (callId: string, from: string) => void;
  }) {
    console.log('AsteriskBridge: Constructor called');

    // Use mock client for browser mode, real client for native
    this.amiClient = Capacitor.isNativePlatform()
      ? new AsteriskAMIClient()
      : new MockAsteriskAMI();

    this.onStatusChange = callbacks.onStatusChange;
    this.onLog = callbacks.onLog;
    this.onRemoteBye = callbacks.onRemoteBye;
    this.onIncomingInvite = callbacks.onIncomingInvite;
  }

  private async setupAsteriskListeners() {
    // Setup AMI event listeners for call events from Asterisk
    // This would listen for Newchannel, Hangup, etc. events from Asterisk
    this.log('Setting up Asterisk event listeners...');

    // In a real implementation, this would establish a persistent connection
    // to Asterisk's AMI and listen for events
    // For now, we'll poll or use HTTP API calls
  }

  public async startService(config: TrunkConfig) {
    this.currentConfig = { ...config };
    this.setStatus(SipStatus.REGISTERING, 0);

    try {
      this.log(`ASTERISK_BRIDGE: Starting service...`);

      // Connect to Asterisk AMI
      const connected = await this.amiClient.connect();
      if (!connected) {
        throw new Error('Failed to connect to Asterisk AMI');
      }

      // Setup event listeners
      await this.setupAsteriskListeners();

      this.setStatus(SipStatus.REGISTERED, 3600); // Asterisk connection is persistent
      this.log('ASTERISK_BRIDGE: Service started successfully');

    } catch (e: any) {
      this.log(`ERR: Failed to start Asterisk bridge service: ${e.message}`);
      this.setStatus(SipStatus.ERROR, 0);
    }
  }

  public async stopService() {
    this.log("ASTERISK_BRIDGE: Stopping service...");

    // Hang up any active channels
    for (const [slot, channelId] of this.activeChannels) {
      try {
        await this.amiClient.hangupChannel(channelId);
      } catch (e) {
        this.log(`WARN: Failed to hangup channel ${channelId}: ${e}`);
      }
    }
    this.activeChannels.clear();

    try {
      await this.amiClient.disconnect();
      this.setStatus(SipStatus.UNREGISTERED, 0);
    } catch (e: any) {
      this.log(`ERR: Failed to stop Asterisk bridge service: ${e.message}`);
    }
  }

  public async createInvite(slot: number, target: string): Promise<string> {
    if (this.status !== SipStatus.REGISTERED) {
      this.log('ERR: Cannot create call - Asterisk bridge not ready');
      throw new Error('ASTERISK_NOT_READY');
    }

    this.log(`TX: Originating call to ${target} via slot ${slot}`);

    try {
      const channelId = await this.amiClient.originateCall(slot, target);
      this.activeChannels.set(slot, channelId);
      return channelId;
    } catch (e: any) {
      this.log(`ERR: Call origination failed: ${e.message}`);
      throw e;
    }
  }

  public async hangupCall(channelId: string) {
    try {
      await this.amiClient.hangupChannel(channelId);
      // Remove from active channels
      for (const [slot, chId] of this.activeChannels) {
        if (chId === channelId) {
          this.activeChannels.delete(slot);
          break;
        }
      }
      this.log(`TX: Hangup [Channel: ${channelId}]`);
    } catch (e: any) {
      this.log(`ERR: Failed to hangup channel ${channelId}: ${e.message}`);
    }
  }

  public async answerCall(channelId: string): Promise<void> {
    // In Asterisk, calls are typically auto-answered or answered via dialplan
    // This method might be used to acknowledge that Asterisk has answered
    this.log(`ASTERISK: Call answered on channel ${channelId}`);
    // The actual answering is handled by Asterisk's dialplan
  }

  // Audio bridging is now handled by the separate AudioEngine service
  // These methods are kept for interface compatibility but delegate to AudioEngine
  public async startAudioBridge(slot: number): Promise<void> {
    this.log(`AUDIO: Audio bridge coordination for slot ${slot} (handled by AudioEngine)`);
  }

  public async stopAudioBridge(slot: number): Promise<void> {
    this.log(`AUDIO: Audio bridge stopped for slot ${slot} (handled by AudioEngine)`);
  }

  // Handle incoming call from Asterisk (triggered by AMI events)
  public handleIncomingCallFromAsterisk(slot: number, channelId: string, callerId: string) {
    this.activeChannels.set(slot, channelId);
    this.log(`RX: Incoming call from Asterisk: ${callerId} on slot ${slot}`);
    if (this.onIncomingInvite) {
      this.onIncomingInvite(channelId, callerId);
    }
  }

  // Handle call hangup from Asterisk
  public handleCallHangupFromAsterisk(channelId: string) {
    this.log(`RX: Call hangup from Asterisk: ${channelId}`);
    // Remove from active channels
    for (const [slot, chId] of this.activeChannels) {
      if (chId === channelId) {
        this.activeChannels.delete(slot);
        break;
      }
    }
    if (this.onRemoteBye) {
      this.onRemoteBye(channelId);
    }
  }

  private setStatus(s: SipStatus, rem: number) {
    this.status = s;
    this.onStatusChange?.(s, rem);
    this.log(`STATUS: ${s}`);
  }

  private log(m: string) { this.onLog?.(m); }
}
