import { CallState, SipStatus, GsmStatus, LogEntry, GatewayConfig, ActiveCall, BackendMetrics, TrunkConfig } from '../types';
import { NativeBridge } from './NativeBridge';
import { SipStack } from './SipStack';
import { AudioEngine } from './AudioEngine';
import { PersistenceService } from './PersistenceService';

type EventCallback = (data: any) => void;

class GatewayDaemon {
  private static instance: GatewayDaemon;
  private subscribers: Map<string, EventCallback[]> = new Map();
  private sipStacks: [SipStack, SipStack];
  private audioEngine: AudioEngine;
  private bootTime: number = Date.now();
  
  public state: {
    callStates: [CallState, CallState];
    sipStatuses: [SipStatus, SipStatus];
    activeCalls: [ActiveCall | null, ActiveCall | null];
    metrics: BackendMetrics;
    config: GatewayConfig;
  } = {
    callStates: [CallState.IDLE, CallState.IDLE],
    sipStatuses: [SipStatus.UNREGISTERED, SipStatus.UNREGISTERED],
    activeCalls: [null, null],
    metrics: {
      cpuUsage: 0, memUsage: 0, temp: 0, uptime: 0,
      processor: 'DETECTING...',
      isRooted: false,
      slotCount: 1,
      sims: [
        { radioSignal: 0, carrier: 'Initializing...', status: GsmStatus.SEARCHING, phoneNumber: '', connectionType: 'Tower' },
        { radioSignal: 0, carrier: 'Initializing...', status: GsmStatus.SEARCHING, phoneNumber: '', connectionType: 'Tower' }
      ],
      regTimeRemaining: [0, 0]
    },
    config: PersistenceService.loadConfig() || this.getDefaultConfig()
  };

  private getDefaultConfig(): GatewayConfig {
    return {
      trunks: [this.createDefaultTrunk(1), this.createDefaultTrunk(2)],
      autoAnswer: true, rootLevel: true, jitterBufferMs: 60, keepAliveInterval: 30, speakerphoneOn: false
    };
  }

  private createDefaultTrunk(id: number): TrunkConfig {
    return {
      mode: 'SERVER', 
      sipServer: '0.0.0.0', 
      sipPort: 5060 + (id - 1), 
      sipUser: `gsm_trunk_${id}`, 
      sipPass: 'admin', 
      sipTrunkName: `SIM${id}_GW`, 
      codec: 'G722',
      regExpiry: 3600, 
      enabled: true, 
      serviceActive: false, 
      stunServer: '', 
      useIce: false
    };
  }

  private constructor() {
    this.sipStacks = [this.createSipStack(0), this.createSipStack(1)];
    this.audioEngine = new AudioEngine((slot, stats) => {
      const activeCall = this.state.activeCalls[slot];
      if (activeCall) {
        activeCall.audioMetrics = { ...activeCall.audioMetrics, ...stats };
      }
      this.notify('metrics_updated', this.state.metrics);
    });
    this.initializeService();
  }

  private createSipStack(slot: 0 | 1): SipStack {
    return new SipStack({
      onStatusChange: (s, rem) => {
        this.state.sipStatuses[slot] = s;
        this.state.metrics.regTimeRemaining[slot] = rem;
        this.notify('state_changed', this.state);
      },
      onLog: (m) => this.log('DEBUG', `SIP_CH${slot+1}`, m),
      onRemoteBye: (callId) => {
          if (this.state.activeCalls[slot]?.id === callId) {
             this.terminateCall(slot, 'REMOTE_SIP_BYE');
          }
      },
      onIncomingInvite: (callId, from) => {
          this.log('INFO', 'SIP', `Received INVITE from ${from} [Call-ID: ${callId}]`);
          if (this.state.callStates[slot] !== CallState.IDLE) {
             this.sipStacks[slot].rejectIncomingCall(callId);
             return;
          }
          this.sipStacks[slot].acceptIncomingCall(callId);
          this.handleIncomingSip(slot, "0000000000", callId);
      }
    });
  }

  public static getInstance(): GatewayDaemon {
    if (!GatewayDaemon.instance) GatewayDaemon.instance = new GatewayDaemon();
    return GatewayDaemon.instance;
  }

  private async initializeService() {
    this.log('INFO', 'BOOT', 'Initializing Native Bridge...');
    const hw = await NativeBridge.initHardware();
    
    this.state.metrics.isRooted = hw.isRooted;
    this.state.metrics.processor = hw.soc;
    this.state.metrics.slotCount = hw.slotCount;
    
    // Update SIM Info from Hardware Scan
    if (hw.simInfo && hw.simInfo.length > 0) {
        this.state.metrics.sims[0] = hw.simInfo[0];
        if (hw.slotCount > 1 && hw.simInfo.length > 1) {
            this.state.metrics.sims[1] = hw.simInfo[1];
        } else {
             this.state.metrics.sims[1].status = GsmStatus.NOT_DETECTED;
             this.state.metrics.sims[1].carrier = "No SIM";
        }
    } else {
        // Initialization failed or no root - set status to reflect that
        this.state.metrics.sims[0].carrier = "Driver Error";
        this.state.metrics.sims[0].status = GsmStatus.NOT_DETECTED;
        this.state.metrics.sims[1].carrier = "Driver Error";
        this.state.metrics.sims[1].status = GsmStatus.NOT_DETECTED;
    }
    
    if (!hw.isRooted) {
        this.log('WARN', 'ROOT', 'Root access denied. Hardware control disabled.');
    }

    this.startSystemMetrics();
    
    // Auto-start active services on boot
    this.state.config.trunks.forEach((trunk, index) => {
        if (trunk.serviceActive && trunk.enabled) {
            this.log('INFO', 'BOOT', `Auto-starting SIM${index+1} Gateway Service...`);
            this.sipStacks[index].startService(trunk);
        }
    });

    NativeBridge.setGsmDisconnectListener((slot) => {
        if (this.state.callStates[slot] !== CallState.IDLE) {
            this.log('INFO', 'MODEM', `GSM Call Disconnected on Slot ${slot+1}`);
            this.terminateCall(slot, 'GSM_REMOTE_HANGUP');
        }
    });
    
    this.notify('state_changed', this.state);
  }

  public toggleService(slot: 0 | 1) {
    const trunk = this.state.config.trunks[slot];
    trunk.serviceActive = !trunk.serviceActive;
    
    if (trunk.serviceActive) {
      this.log('INFO', 'CORE', `SIM${slot+1} Gateway Service STARTING...`);
      this.sipStacks[slot].startService(trunk);
    } else {
      this.log('WARN', 'CORE', `SIM${slot+1} Gateway Service STOPPED.`);
      this.sipStacks[slot].stopService();
    }
    this.updateConfig(this.state.config);
  }

  public updateConfig(newConfig: GatewayConfig) {
    const oldConfig = this.state.config;
    this.state.config = { ...newConfig };
    PersistenceService.saveConfig(this.state.config);
    
    [0, 1].forEach((i) => {
        const slot = i as 0 | 1;
        const oldTrunk = oldConfig.trunks[slot];
        const newTrunk = this.state.config.trunks[slot];
        
        if (newTrunk.serviceActive) {
            if (oldTrunk.sipServer !== newTrunk.sipServer || 
                oldTrunk.sipPort !== newTrunk.sipPort || 
                oldTrunk.sipUser !== newTrunk.sipUser || 
                oldTrunk.sipPass !== newTrunk.sipPass || 
                oldTrunk.mode !== newTrunk.mode ||
                oldTrunk.codec !== newTrunk.codec) {
                
                this.log('INFO', 'CONFIG', `Critical Config Change on Trunk ${slot+1}. Restarting Service...`);
                this.sipStacks[slot].stopService();
                setTimeout(() => {
                    this.sipStacks[slot].startService(newTrunk);
                }, 500);
            }
        }
    });

    this.notify('state_changed', this.state);
  }

  private startSystemMetrics() {
    setInterval(() => {
      this.state.metrics.uptime = Math.floor((Date.now() - this.bootTime) / 1000);
      this.state.metrics.temp = 36 + Math.random() * 4;
      this.state.metrics.cpuUsage = 0.5 + Math.random() * 5;
      
      this.notify('metrics_updated', this.state.metrics);
    }, 2000);
    
    // Periodically refresh SIM metrics (every 10 seconds)
    setInterval(async () => {
      if (this.state.metrics.isRooted) {
        try {
          const simInfo = await NativeBridge.fetchSimDetails();
          if (simInfo && simInfo.length > 0) {
            // Update slot 0
            if (simInfo[0]) {
              this.state.metrics.sims[0] = { 
                ...this.state.metrics.sims[0], 
                ...simInfo[0],
                phoneNumber: simInfo[0].phoneNumber || this.state.metrics.sims[0].phoneNumber,
                radioSignal: simInfo[0].radioSignal || this.state.metrics.sims[0].radioSignal,
                connectionType: simInfo[0].connectionType || this.state.metrics.sims[0].connectionType
              };
            }
            // Update slot 1 only if it exists and is detected
            if (this.state.metrics.slotCount > 1 && simInfo.length > 1 && simInfo[1]) {
              this.state.metrics.sims[1] = { 
                ...this.state.metrics.sims[1], 
                ...simInfo[1],
                phoneNumber: simInfo[1].phoneNumber || this.state.metrics.sims[1].phoneNumber,
                radioSignal: simInfo[1].radioSignal || this.state.metrics.sims[1].radioSignal,
                connectionType: simInfo[1].connectionType || this.state.metrics.sims[1].connectionType
              };
            }
            this.notify('metrics_updated', this.state.metrics);
          }
        } catch (e) {
          console.warn('[GATEWAY_DAEMON] Failed to refresh SIM metrics:', e);
        }
      }
    }, 10000);
  }

  public async testSipInvite(slot: 0 | 1) {
      if (this.state.config.trunks[slot].serviceActive) {
          await this.sipStacks[slot].simulateRemoteInvite("sip:100@pbx.local");
      } else {
          this.log('WARN', 'TEST', `Cannot simulate SIP Invite: SIM${slot+1} Service Inactive`);
      }
  }

  public async handleIncomingGsm(slot: 0 | 1, phoneNumber: string) {
    const trunk = this.state.config.trunks[slot];
    if (!trunk.serviceActive || this.state.callStates[slot] !== CallState.IDLE) {
        this.log('WARN', 'MODEM', `Incoming GSM Ignored (Service Inactive or Busy)`);
        return;
    }

    const otherSlot = slot === 0 ? 1 : 0;
    if (this.state.callStates[otherSlot] === CallState.BRIDGING) {
      this.log('WARN', 'MODEM', `DSDS Radio Busy: Bridge on SIM${otherSlot+1} active. SIM${slot+1} call ignored.`);
      return;
    }

    this.state.callStates[slot] = CallState.INCOMING_GSM;
    this.log('INFO', 'MODEM', `Incoming GSM Call from ${phoneNumber}`);
    
    await NativeBridge.answerGsmCallPrivileged(slot);
    
    try {
        const cid = await this.sipStacks[slot].createInvite(trunk.sipUser, trunk.codec);
        this.state.activeCalls[slot] = {
            id: cid, simSlot: slot, gsmNumber: phoneNumber, sipAddress: trunk.sipServer,
            startTime: Date.now(), direction: 'GSM_TO_SIP', durationSeconds: 0,
            signaling: ['SIM_HOOK_UP', 'INVITE_SENT', 'SDP_NEGOTIATED'],
            audioMetrics: { latency: 32, jitter: 1, rxPackets: 0, txPackets: 0, bufferDepth: 60 }
        };

        this.state.callStates[slot] = CallState.BRIDGING;
        await NativeBridge.setAudioRouting(slot, 'IN_CALL');
        this.audioEngine.startBridge(slot, this.state.config.jitterBufferMs);
        this.notify('state_changed', this.state);
    } catch (e: any) {
        this.log('ERROR', 'CORE', `Failed to bridge call: ${e.message}`);
        await NativeBridge.hangupGsmPrivileged(slot);
        this.state.callStates[slot] = CallState.IDLE;
        this.notify('state_changed', this.state);
    }
  }

  public async handleIncomingSip(slot: 0 | 1, targetGsmNumber: string, existingCallId?: string) {
    const trunk = this.state.config.trunks[slot];
    if (!trunk.serviceActive || this.state.callStates[slot] !== CallState.IDLE) {
        this.log('WARN', 'SIP', `Incoming SIP Ignored (Service Inactive or Busy)`);
        return;
    }

    const otherSlot = slot === 0 ? 1 : 0;
    if (this.state.callStates[otherSlot] === CallState.BRIDGING) {
      this.log('WARN', 'SIP', `Inter-Slot Radio Conflict: Dropping SIP INVITE for SIM${slot+1}.`);
      return;
    }

    this.state.callStates[slot] = CallState.INCOMING_SIP;
    this.log('INFO', 'SIP', `Incoming Trunk Call -> External GSM: ${targetGsmNumber}`);

    await NativeBridge.dialGsmSilently(slot, targetGsmNumber);

    this.state.activeCalls[slot] = {
      id: existingCallId || `sip_leg_${Math.random().toString(36).substr(2, 6)}`,
      simSlot: slot,
      gsmNumber: targetGsmNumber,
      sipAddress: trunk.sipServer,
      startTime: Date.now(),
      direction: 'SIP_TO_GSM',
      durationSeconds: 0,
      signaling: ['RX_INVITE', 'GSM_MODEM_DIAL', 'WAIT_FOR_GSM_ANSWER'],
      audioMetrics: { latency: 45, jitter: 5, rxPackets: 0, txPackets: 0, bufferDepth: 60 }
    };

    this.state.callStates[slot] = CallState.BRIDGING;
    await NativeBridge.setAudioRouting(slot, 'IN_CALL');
    this.audioEngine.startBridge(slot, this.state.config.jitterBufferMs);
    this.notify('state_changed', this.state);
  }

  public async terminateCall(slot: 0 | 1, reason: string) {
    if (this.state.callStates[slot] === CallState.IDLE) return;
    this.log('INFO', 'CORE', `Terminating Call on Channel ${slot+1}: ${reason}`);
    this.audioEngine.stopBridge(slot);
    await NativeBridge.setAudioRouting(slot, 'COMMUNICATION');
    
    const activeCall = this.state.activeCalls[slot];
    if (activeCall) {
        this.sipStacks[slot].sendBye(activeCall.id);
    }
    
    await NativeBridge.hangupGsmPrivileged(slot);
    this.state.callStates[slot] = CallState.IDLE;
    this.state.activeCalls[slot] = null;
    this.notify('state_changed', this.state);
  }

  private notify(event: string, data: any) {
    this.subscribers.get(event)?.forEach(cb => cb(data));
  }

  public subscribe(event: string, callback: EventCallback) {
    if (!this.subscribers.has(event)) this.subscribers.set(event, []);
    this.subscribers.get(event)?.push(callback);
  }

  private log(level: LogEntry['level'], tag: string, message: string) {
    const entry: LogEntry = {
      id: Math.random().toString(36).substr(2, 5),
      timestamp: new Date().toLocaleTimeString('en-GB', { hour12: false }),
      level, tag, message
    };
    this.notify('new_log', entry);
  }
}

export const daemon = GatewayDaemon.getInstance();
