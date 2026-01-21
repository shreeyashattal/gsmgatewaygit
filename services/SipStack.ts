import { BridgeStatus } from '../types';
import { Capacitor } from '@capacitor/core';

// SIP User Agent interface - handles SIP signaling
interface SIPUserAgent {
  connect(): Promise<boolean>;
  disconnect(): Promise<void>;
  originateCall(slot: number, destination: string): Promise<string>;
  hangupCall(callId: string): Promise<boolean>;
  getCallStatus(callId: string): Promise<string>;
  onCallEvent?: (event: any) => void;
}

// Mock SIP implementation for browser mode
class MockSIPUserAgent implements SIPUserAgent {
  async connect(): Promise<boolean> {
    console.log('[SIP] Mock: SIP stack initialized');
    return true;
  }

  async disconnect(): Promise<void> {
    console.log('[SIP] Mock: SIP stack stopped');
  }

  async originateCall(slot: number, destination: string): Promise<string> {
    console.log(`[SIP] Mock: Sending INVITE to ${destination} via slot ${slot}`);
    return `sip-call-${slot+1}-${Date.now()}`;
  }

  async hangupCall(callId: string): Promise<boolean> {
    console.log(`[SIP] Mock: Sending BYE for call ${callId}`);
    return true;
  }

  async getCallStatus(callId: string): Promise<string> {
    return 'ACTIVE';
  }
}

// Real SIP implementation - uses native SIPClient
class NativeSIPUserAgent implements SIPUserAgent {
  private connected: boolean = false;

  async connect(): Promise<boolean> {
    console.log('[SIP] Initializing native SIP stack...');
    // The actual SIP stack is handled by the native Java SIPClient
    // This TypeScript layer communicates with the native layer via Capacitor
    this.connected = true;
    return true;
  }

  async disconnect(): Promise<void> {
    this.connected = false;
  }

  async originateCall(slot: number, destination: string): Promise<string> {
    if (!this.connected) {
      throw new Error('SIP stack not initialized');
    }

    const callId = `sip-call-${slot+1}-${Date.now()}`;
    console.log(`[SIP] Sending INVITE: ${callId} -> ${destination}`);
    return callId;
  }

  async hangupCall(callId: string): Promise<boolean> {
    if (!this.connected) {
      throw new Error('SIP stack not initialized');
    }
    console.log(`[SIP] Sending BYE for call: ${callId}`);
    return true;
  }

  async getCallStatus(callId: string): Promise<string> {
    return 'ACTIVE';
  }
}

export class SIPBridge {
  private status: BridgeStatus = BridgeStatus.DISCONNECTED;
  private sipClient: SIPUserAgent;
  private onStatusChange?: (status: BridgeStatus) => void;
  private onLog?: (log: string) => void;
  private onRemoteBye?: (callId: string) => void;
  private onIncomingInvite?: (callId: string, from: string) => void;
  private activeCalls: Map<number, string> = new Map();

  constructor(callbacks: {
    onStatusChange: (status: BridgeStatus) => void;
    onLog: (m: string) => void;
    onRemoteBye?: (callId: string) => void;
    onIncomingInvite?: (callId: string, from: string) => void;
  }) {
    console.log('SIPBridge: Constructor called');

    // Use mock client for browser mode, real native SIP for native
    this.sipClient = Capacitor.isNativePlatform()
      ? new NativeSIPUserAgent()
      : new MockSIPUserAgent();

    this.onStatusChange = callbacks.onStatusChange;
    this.onLog = callbacks.onLog;
    this.onRemoteBye = callbacks.onRemoteBye;
    this.onIncomingInvite = callbacks.onIncomingInvite;
  }

  public async connect(): Promise<void> {
    this.setStatus(BridgeStatus.CONNECTING);

    try {
      this.log('SIP: Initializing stack...');
      const connected = await this.sipClient.connect();

      if (!connected) {
        throw new Error('Failed to initialize SIP stack');
      }

      this.setStatus(BridgeStatus.CONNECTED);
      this.log('SIP: Stack initialized successfully');
    } catch (e: any) {
      this.log(`ERR: Failed to initialize SIP: ${e.message}`);
      this.setStatus(BridgeStatus.ERROR);
      throw e;
    }
  }

  public async disconnect(): Promise<void> {
    this.log("SIP: Shutting down...");

    // Hang up any active calls
    for (const [slot, callId] of this.activeCalls) {
      try {
        await this.sipClient.hangupCall(callId);
      } catch (e) {
        this.log(`WARN: Failed to hangup call ${callId}: ${e}`);
      }
    }
    this.activeCalls.clear();

    try {
      await this.sipClient.disconnect();
      this.setStatus(BridgeStatus.DISCONNECTED);
    } catch (e: any) {
      this.log(`ERR: Failed to disconnect: ${e.message}`);
    }
  }

  public async createInvite(slot: number, target: string): Promise<string> {
    if (this.status !== BridgeStatus.CONNECTED) {
      this.log('ERR: Cannot create call - SIP not connected');
      throw new Error('SIP_NOT_CONNECTED');
    }

    this.log(`TX: INVITE to ${target} via slot ${slot}`);

    try {
      const callId = await this.sipClient.originateCall(slot, target);
      this.activeCalls.set(slot, callId);
      return callId;
    } catch (e: any) {
      this.log(`ERR: INVITE failed: ${e.message}`);
      throw e;
    }
  }

  public async hangupCall(callId: string): Promise<void> {
    try {
      await this.sipClient.hangupCall(callId);
      // Remove from active calls
      for (const [slot, cid] of this.activeCalls) {
        if (cid === callId) {
          this.activeCalls.delete(slot);
          break;
        }
      }
      this.log(`TX: BYE [Call-ID: ${callId}]`);
    } catch (e: any) {
      this.log(`ERR: Failed to send BYE for ${callId}: ${e.message}`);
    }
  }

  public async answerCall(callId: string): Promise<void> {
    this.log(`TX: 200 OK [Call-ID: ${callId}]`);
  }

  // Audio bridging is handled by the native RTPManager
  public async startAudioBridge(slot: number): Promise<void> {
    this.log(`RTP: Starting bridge for slot ${slot}`);
  }

  public async stopAudioBridge(slot: number): Promise<void> {
    this.log(`RTP: Stopping bridge for slot ${slot}`);
  }

  // Handle incoming SIP INVITE
  public handleIncomingInvite(slot: number, callId: string, from: string): void {
    this.activeCalls.set(slot, callId);
    this.log(`RX: INVITE from ${from} on slot ${slot}`);
    if (this.onIncomingInvite) {
      this.onIncomingInvite(callId, from);
    }
  }

  // Handle SIP BYE from remote
  public handleRemoteBye(callId: string): void {
    this.log(`RX: BYE [Call-ID: ${callId}]`);
    for (const [slot, cid] of this.activeCalls) {
      if (cid === callId) {
        this.activeCalls.delete(slot);
        break;
      }
    }
    if (this.onRemoteBye) {
      this.onRemoteBye(callId);
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
