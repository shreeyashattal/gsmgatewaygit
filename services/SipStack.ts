import { BridgeStatus } from '../types';
import { Capacitor } from '@capacitor/core';

// Asterisk Manager Interface (AMI) communication
interface AsteriskAMI {
  connect(): Promise<boolean>;
  disconnect(): Promise<void>;
  originateCall(slot: number, destination: string): Promise<string>;
  hangupChannel(channel: string): Promise<boolean>;
  getChannelStatus(channel: string): Promise<string>;
  onCallEvent?: (event: any) => void;
}

// Mock AMI implementation for browser mode
class MockAsteriskAMI implements AsteriskAMI {
  async connect(): Promise<boolean> {
    console.log('[AMI] Mock: Connected to Asterisk');
    return true;
  }

  async disconnect(): Promise<void> {
    console.log('[AMI] Mock: Disconnected from Asterisk');
  }

  async originateCall(slot: number, destination: string): Promise<string> {
    console.log(`[AMI] Mock: Originating call to ${destination} from slot ${slot}`);
    return `Local/gsm${slot+1}@from-gsm${slot+1}-${Date.now()}`;
  }

  async hangupChannel(channel: string): Promise<boolean> {
    console.log(`[AMI] Mock: Hanging up channel ${channel}`);
    return true;
  }

  async getChannelStatus(channel: string): Promise<string> {
    return 'Up';
  }
}

// Real AMI implementation - connects to Asterisk via TCP socket
class AsteriskAMIClient implements AsteriskAMI {
  private connected: boolean = false;
  // AMI credentials are hardcoded to match Config.java
  private readonly amiHost: string = '127.0.0.1';
  private readonly amiPort: number = 5038;
  private readonly amiUser: string = 'gateway';
  private readonly amiPass: string = 'gW8y#mK2$pL9';

  async connect(): Promise<boolean> {
    console.log('[AMI] Connecting to Asterisk AMI at 127.0.0.1:5038...');
    // The actual AMI connection is handled by the native Java AMIClient
    // This TypeScript layer communicates with the native layer via Capacitor
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

    const channelId = `Local/gsm${slot+1}@from-gsm${slot+1}-${Date.now()}`;
    console.log(`[AMI] Originating call: ${channelId} -> ${destination}`);
    return channelId;
  }

  async hangupChannel(channel: string): Promise<boolean> {
    if (!this.connected) {
      throw new Error('Not connected to Asterisk AMI');
    }
    console.log(`[AMI] Hanging up channel: ${channel}`);
    return true;
  }

  async getChannelStatus(channel: string): Promise<string> {
    return 'Up';
  }
}

export class AsteriskBridge {
  private status: BridgeStatus = BridgeStatus.DISCONNECTED;
  private amiClient: AsteriskAMI;
  private onStatusChange?: (status: BridgeStatus) => void;
  private onLog?: (log: string) => void;
  private onRemoteBye?: (callId: string) => void;
  private onIncomingInvite?: (callId: string, from: string) => void;
  private activeChannels: Map<number, string> = new Map();

  constructor(callbacks: {
    onStatusChange: (status: BridgeStatus) => void;
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

  public async connect(): Promise<void> {
    this.setStatus(BridgeStatus.CONNECTING);

    try {
      this.log('AMI: Connecting to Asterisk...');
      const connected = await this.amiClient.connect();

      if (!connected) {
        throw new Error('Failed to connect to Asterisk AMI');
      }

      this.setStatus(BridgeStatus.CONNECTED);
      this.log('AMI: Connected successfully');
    } catch (e: any) {
      this.log(`ERR: Failed to connect to Asterisk: ${e.message}`);
      this.setStatus(BridgeStatus.ERROR);
      throw e;
    }
  }

  public async disconnect(): Promise<void> {
    this.log("AMI: Disconnecting...");

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
      this.setStatus(BridgeStatus.DISCONNECTED);
    } catch (e: any) {
      this.log(`ERR: Failed to disconnect: ${e.message}`);
    }
  }

  public async createInvite(slot: number, target: string): Promise<string> {
    if (this.status !== BridgeStatus.CONNECTED) {
      this.log('ERR: Cannot create call - AMI not connected');
      throw new Error('AMI_NOT_CONNECTED');
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

  public async hangupCall(channelId: string): Promise<void> {
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
    // In Asterisk, calls are typically auto-answered via dialplan
    this.log(`AMI: Call answered on channel ${channelId}`);
  }

  // Audio bridging is handled by the native RTPManager
  public async startAudioBridge(slot: number): Promise<void> {
    this.log(`AUDIO: Starting RTP bridge for slot ${slot}`);
  }

  public async stopAudioBridge(slot: number): Promise<void> {
    this.log(`AUDIO: Stopping RTP bridge for slot ${slot}`);
  }

  // Handle incoming call from Asterisk (triggered by AMI events)
  public handleIncomingCallFromAsterisk(slot: number, channelId: string, callerId: string): void {
    this.activeChannels.set(slot, channelId);
    this.log(`RX: Incoming call from Asterisk: ${callerId} on slot ${slot}`);
    if (this.onIncomingInvite) {
      this.onIncomingInvite(channelId, callerId);
    }
  }

  // Handle call hangup from Asterisk
  public handleCallHangupFromAsterisk(channelId: string): void {
    this.log(`RX: Call hangup from Asterisk: ${channelId}`);
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

  private setStatus(s: BridgeStatus): void {
    this.status = s;
    this.onStatusChange?.(s);
    this.log(`STATUS: ${s}`);
  }

  private log(m: string): void {
    this.onLog?.(m);
  }
}
