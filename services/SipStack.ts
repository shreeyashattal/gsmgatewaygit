import { SipStatus, TrunkConfig } from '../types';
import { registerPlugin, PluginListenerHandle } from '@capacitor/core';

interface SipPluginInterface {
  start(options: { 
    mode: string; 
    sipServer: string; 
    sipPort: number; 
    sipUser: string; 
    sipPass: string; 
    regExpiry: number;
    codec: string;
  }): Promise<{ success: boolean; sipUri: string; localIp: string }>;
  
  stop(): Promise<{ success: boolean }>;
  
  makeCall(options: { destination: string }): Promise<{ success: boolean; destination: string }>;
  
  getLocalIp(): Promise<{ ip: string }>;
  
  addListener(
    eventName: 'sipCallReceived', 
    listenerFunc: (data: { type: string; caller: string; called: string }) => void
  ): Promise<PluginListenerHandle> & PluginListenerHandle;
}

const SipPlugin = registerPlugin<SipPluginInterface>('Sip');

export class SipStack {
  private status: SipStatus = SipStatus.UNREGISTERED;
  private regTimer: any = null;
  private onStatusChange?: (status: SipStatus, remaining: number) => void;
  private onLog?: (log: string) => void;
  private onRemoteBye?: (callId: string) => void;
  private onIncomingInvite?: (callId: string, from: string) => void;
  private currentConfig: TrunkConfig | null = null;
  private listenerHandle: PluginListenerHandle | null = null;

  constructor(callbacks: { 
    onStatusChange: (s: SipStatus, rem: number) => void; 
    onLog: (m: string) => void;
    onRemoteBye?: (callId: string) => void;
    onIncomingInvite?: (callId: string, from: string) => void;
  }) {
    this.onStatusChange = callbacks.onStatusChange;
    this.onLog = callbacks.onLog;
    this.onRemoteBye = callbacks.onRemoteBye;
    this.onIncomingInvite = callbacks.onIncomingInvite;
    
    this.setupListeners();
  }

  private async setupListeners() {
    try {
      this.listenerHandle = await SipPlugin.addListener('sipCallReceived', (data) => {
        this.log(`RX: Incoming Call from ${data.caller} to ${data.called}`);
        if (this.onIncomingInvite) {
          // Generate a temporary Call ID since Native didn't provide one in the event yet
          const callId = `inc_${Math.random().toString(36).substr(2, 8)}`;
          this.onIncomingInvite(callId, data.caller);
        }
      });
    } catch (e) {
      this.log('WARN: Failed to setup SIP listeners');
    }
  }

  public async startService(config: TrunkConfig) {
    this.currentConfig = { ...config };
    this.setStatus(SipStatus.REGISTERING, 0);

    try {
      this.log(`SIP_CORE: Starting Service in ${config.mode} mode [Codec: ${config.codec}]...`);
      
      const result = await SipPlugin.start({
        mode: config.mode,
        sipServer: config.sipServer,
        sipPort: config.sipPort,
        sipUser: config.sipUser,
        sipPass: config.sipPass,
        regExpiry: config.regExpiry,
        codec: config.codec
      });

      if (result.success) {
        this.log(`SIP_CORE: Service Started. URI: ${result.sipUri}, IP: ${result.localIp}`);
        
        if (config.mode === 'SERVER') {
          this.setStatus(SipStatus.LISTENING, 3600);
        } else {
          this.setStatus(SipStatus.REGISTERED, config.regExpiry);
          this.startKeepAliveTimer();
        }
      }
    } catch (e: any) {
      this.log(`ERR: Failed to start SIP service: ${e.message}`);
      this.setStatus(SipStatus.ERROR, 0);
    }
  }

  public async stopService() {
    this.log("SIP_CORE: Stopping service...");
    this.stopTimers();
    try {
      await SipPlugin.stop();
      this.setStatus(SipStatus.UNREGISTERED, 0);
    } catch (e: any) {
      this.log(`ERR: Failed to stop SIP service: ${e.message}`);
    }
  }

  public async createInvite(target: string, codec: string): Promise<string> {
    if (this.status !== SipStatus.REGISTERED && this.status !== SipStatus.LISTENING) {
      this.log('ERR: Cannot create INVITE - SIP not ready');
      throw new Error('SIP_NOT_READY');
    }

    // Construct SIP URI if only number provided
    let destination = target;
    if (!destination.includes('sip:')) {
      if (this.currentConfig?.mode === 'CLIENT') {
        destination = `sip:${target}@${this.currentConfig.sipServer}`;
      } else {
        // For trunk, we might need the IP of the PBX if we know it, or just send to the peer
        // Assuming target is a full URI or we append the server
        destination = `sip:${target}@${this.currentConfig?.sipServer || '127.0.0.1'}`;
      }
    }

    this.log(`TX: INVITE to ${destination} [Codec: ${codec}]`);
    
    try {
      const result = await SipPlugin.makeCall({ destination });
      if (result.success) {
        return `out_${Math.random().toString(36).substr(2, 8)}`;
      } else {
        throw new Error('Native call failed');
      }
    } catch (e: any) {
      this.log(`ERR: Call failed: ${e.message}`);
      throw e;
    }
  }

  public sendBye(callId: string) {
    // TODO: Implement native hangup
    this.log(`TX: BYE [Call-ID: ${callId}] (Native hangup not yet implemented)`);
  }

  public async simulateRemoteInvite(from: string): Promise<string> {
    // Kept for testing purposes if needed, but normally triggered by Native Event
    const callId = `sim_${Math.random().toString(36).substr(2, 8)}`;
    if (this.onIncomingInvite) {
      this.onIncomingInvite(callId, from);
    }
    return callId;
  }

  public async acceptIncomingCall(callId: string) {
    this.log(`SIP: Accepting call ${callId} (Native auto-answered)`);
    // Native plugin currently auto-answers, so we just acknowledge logic state here
  }
  
  public rejectIncomingCall(callId: string) {
    this.log(`SIP: Rejecting call ${callId} (Native hangup TODO)`);
    // TODO: Implement native reject
  }

  private setStatus(s: SipStatus, rem: number) {
    this.status = s;
    this.onStatusChange?.(s, rem);
  }

  private startKeepAliveTimer() {
    this.stopTimers();
    // Just a visual countdown for UI, actual registration handled by PJSIP
    let remaining = this.currentConfig?.regExpiry || 3600;
    
    this.regTimer = setInterval(() => {
      remaining--;
      if (remaining <= 0) remaining = this.currentConfig?.regExpiry || 3600;
      this.onStatusChange?.(this.status, remaining);
    }, 1000);
  }

  private stopTimers() {
    if (this.regTimer) clearInterval(this.regTimer);
    this.regTimer = null;
  }

  private log(m: string) { this.onLog?.(m); }
  
  private async getLocalIp(): Promise<string> {
    try {
      const result = await SipPlugin.getLocalIp();
      return result.ip;
    } catch (e) {
      return '127.0.0.1';
    }
  }
}
