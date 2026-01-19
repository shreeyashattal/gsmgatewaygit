import { CallState, SipStatus, GsmStatus, LogEntry, GatewayConfig, ActiveCall, BackendMetrics, TrunkConfig } from '../types';
import { NativeBridge } from './NativeBridge';
import { AsteriskBridge } from './SipStack';
import { PersistenceService } from './PersistenceService';
import { AsteriskAPI } from './AsteriskAPI';

type EventCallback = (data: any) => void;

class GatewayDaemon {
  private static instance: GatewayDaemon;
  private subscribers: Map<string, EventCallback[]> = new Map();
  private asteriskBridges: [AsteriskBridge, AsteriskBridge];
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
      processor: 'Unknown',  // FIXED: Changed from 'DETECTING...' to valid ProcessorType
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
    if (id === 1) {
      return {
        mode: 'SERVER',
        sipServer: '0.0.0.0',
        sipPort: 5080,
        sipUser: 'test',
        sipPass: 'test1234',
        sipTrunkName: `SIM${id}_GW`,
        codec: 'PCMU',
        regExpiry: 3600,
        enabled: true,
        serviceActive: false,
        stunServer: '',
        useIce: false
      };
    }

    return {
      mode: 'SERVER',
      sipServer: '0.0.0.0',
      sipPort: 5082,
      sipUser: `test`,
      sipPass: 'test1234',
      sipTrunkName: `SIM${id}_GW`,
      codec: 'PCMU',
      regExpiry: 3600,
      enabled: false,
      serviceActive: false,
      stunServer: '',
      useIce: false
    };
  }

  private constructor() {
    this.asteriskBridges = [this.createAsteriskBridge(0), this.createAsteriskBridge(1)];
    this.initializeService();
  }

  private createAsteriskBridge(slot: 0 | 1): AsteriskBridge {
    return new AsteriskBridge({
      onStatusChange: (s, rem) => {
        this.state.sipStatuses[slot] = s;
        this.state.metrics.regTimeRemaining[slot] = rem;
        this.notify('state_changed', this.state);
      },
      onLog: (m) => this.log('DEBUG', `ASTERISK_CH${slot+1}`, m),
      onRemoteBye: (callId) => {
          if (this.state.activeCalls[slot]?.id === callId) {
             this.terminateCall(slot, 'REMOTE_HANGUP');
          }
      },
      onIncomingInvite: async (callId, from) => {
          this.log('INFO', 'ASTERISK', `Incoming call from ${from} [Channel: ${callId}] - Processing...`);
          if (this.state.callStates[slot] !== CallState.IDLE) {
             this.log('WARN', 'ASTERISK', `Rejecting call - slot ${slot} busy`);
             return;
          }

          try {
              this.log('INFO', 'ASTERISK', `Processing incoming call ${callId}...`);
              // Answer the call in Asterisk (this might be auto-handled by dialplan)
              await this.asteriskBridges[slot].answerCall(callId);
              this.log('INFO', 'ASTERISK', `Call ${callId} answered successfully`);
              // Then handle the GSM side - dial out to the target number
              this.handleSipToGsmCall(slot, from, callId);
          } catch (e) {
              this.log('ERROR', 'ASTERISK', `Failed to process incoming call ${callId}: ${e}`);
          }
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
    
    if (hw.simInfo && hw.simInfo.length > 0) {
        this.state.metrics.sims[0] = hw.simInfo[0];
        if (hw.slotCount > 1 && hw.simInfo.length > 1) {
            this.state.metrics.sims[1] = hw.simInfo[1];
        } else {
             this.state.metrics.sims[1].status = GsmStatus.NOT_DETECTED;
             this.state.metrics.sims[1].carrier = "No SIM";
        }
    } else {
        this.state.metrics.sims[0].carrier = "Driver Error";
        this.state.metrics.sims[0].status = GsmStatus.NOT_DETECTED;
        this.state.metrics.sims[1].carrier = "Driver Error";
        this.state.metrics.sims[1].status = GsmStatus.NOT_DETECTED;
    }
    
    if (!hw.isRooted) {
        this.log('WARN', 'ROOT', 'Root access denied. Hardware control disabled.');
    }

    this.startSystemMetrics();
    
    this.state.config.trunks.forEach((trunk, index) => {
        if (trunk.serviceActive && trunk.enabled) {
            this.log('INFO', 'BOOT', `Auto-starting SIM${index+1} Gateway Service...`);
            this.asteriskBridges[index].startService(trunk);
        }
    });

    NativeBridge.setGsmDisconnectListener((slot) => {
        if (this.state.callStates[slot] !== CallState.IDLE) {
            this.log('INFO', 'MODEM', `GSM Call Disconnected on Slot ${slot+1}`);
            this.terminateCall(slot, 'GSM_REMOTE_HANGUP');
        }
    });

    NativeBridge.setGsmIncomingCallListener(async (slot, phoneNumber) => {
        this.log('INFO', 'MODEM', `Incoming GSM Call on Slot ${slot+1} from ${phoneNumber}`);
        await this.handleIncomingGsm(slot, phoneNumber);
    });

    // Setup Asterisk API handlers
    const asteriskAPI = AsteriskAPI.getInstance();
    asteriskAPI.setGsmCallRequestHandler(async (slot, phoneNumber, channelId) => {
        return await this.makeGsmCallFromAsterisk(slot as 0 | 1, phoneNumber, channelId);
    });

    asteriskAPI.setCallEventHandler((event, channelId, data) => {
        this.handleAsteriskCallEvent(event, channelId, data);
    });

    this.notify('state_changed', this.state);
  }

  public toggleService(slot: 0 | 1) {
    const trunk = this.state.config.trunks[slot];
    trunk.serviceActive = !trunk.serviceActive;
    
    if (trunk.serviceActive) {
      this.log('INFO', 'CORE', `SIM${slot+1} Gateway Service STARTING...`);
      this.asteriskBridges[slot].startService(trunk);
    } else {
      this.log('WARN', 'CORE', `SIM${slot+1} Gateway Service STOPPED.`);
      this.asteriskBridges[slot].stopService();
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
                this.asteriskBridges[slot].stopService();
                setTimeout(() => {
                    this.asteriskBridges[slot].startService(newTrunk);
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
    
    setInterval(async () => {
      if (this.state.metrics.isRooted) {
        try {
          const simInfo = await NativeBridge.fetchSimDetails();
          if (simInfo && simInfo.length > 0) {
            if (simInfo[0]) {
              this.state.metrics.sims[0] = { 
                ...this.state.metrics.sims[0], 
                ...simInfo[0],
                phoneNumber: simInfo[0].phoneNumber || this.state.metrics.sims[0].phoneNumber,
                radioSignal: simInfo[0].radioSignal || this.state.metrics.sims[0].radioSignal,
                connectionType: simInfo[0].connectionType || this.state.metrics.sims[0].connectionType
              };
            }
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

  // Method for Asterisk to request outgoing GSM call
  public async makeGsmCallFromAsterisk(slot: 0 | 1, phoneNumber: string, asteriskChannelId: string): Promise<boolean> {
    const trunk = this.state.config.trunks[slot];
    if (!trunk.serviceActive || this.state.callStates[slot] !== CallState.IDLE) {
        this.log('WARN', 'ASTERISK', `Cannot make GSM call - Service inactive or slot ${slot} busy`);
        return false;
    }

    const otherSlot = slot === 0 ? 1 : 0;
    if (this.state.callStates[otherSlot] === CallState.BRIDGING) {
      this.log('WARN', 'ASTERISK', `DSDS Radio Busy: Bridge on SIM${otherSlot+1} active. GSM call ignored.`);
      return false;
    }

    this.state.callStates[slot] = CallState.OUTGOING_GSM;
    this.log('INFO', 'ASTERISK', `Outgoing GSM Call requested by Asterisk to ${phoneNumber}`);

    try {
        // Make the GSM call
        const success = await NativeBridge.makeGsmCallForAsterisk(slot, phoneNumber);

        if (success) {
            this.state.activeCalls[slot] = {
                id: asteriskChannelId,
                simSlot: slot,
                gsmNumber: phoneNumber,
                sipAddress: trunk.sipServer,
                startTime: Date.now(),
                direction: 'SIP_TO_GSM',
                durationSeconds: 0,
                signaling: ['ASTERISK_REQUEST', 'GSM_MODEM_DIAL'],
                audioMetrics: { latency: 45, jitter: 5, rxPackets: 0, txPackets: 0, bufferDepth: 60 }
            };

            this.state.callStates[slot] = CallState.BRIDGING;
            await NativeBridge.setAudioRouting(slot, 'IN_CALL');
            // Audio bridge will be started when GSM call is answered
            this.notify('state_changed', this.state);
            return true;
        } else {
            this.state.callStates[slot] = CallState.IDLE;
            this.notify('state_changed', this.state);
            return false;
        }
    } catch (e: any) {
        this.log('ERROR', 'ASTERISK', `Failed to make GSM call: ${e.message}`);
        this.state.callStates[slot] = CallState.IDLE;
        this.notify('state_changed', this.state);
        return false;
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
        // For incoming GSM calls, we tell Asterisk to originate a call
        // The destination could be configured or derived from the GSM number
        const destination = trunk.sipUser; // Or could be a mapping based on phoneNumber

        const cid = await this.asteriskBridges[slot].createInvite(slot, destination);
        this.state.activeCalls[slot] = {
            id: cid, simSlot: slot, gsmNumber: phoneNumber, sipAddress: trunk.sipServer,
            startTime: Date.now(), direction: 'GSM_TO_SIP', durationSeconds: 0,
            signaling: ['GSM_INCOMING', 'GSM_ANSWERED', 'ASTERISK_ORIGINATE'],
            audioMetrics: { latency: 32, jitter: 1, rxPackets: 0, txPackets: 0, bufferDepth: 60 }
        };

        this.state.callStates[slot] = CallState.BRIDGING;
        await NativeBridge.setAudioRouting(slot, 'IN_CALL');
        await this.asteriskBridges[slot].startAudioBridge(slot);
        this.notify('state_changed', this.state);
    } catch (e: any) {
        this.log('ERROR', 'CORE', `Failed to bridge call: ${e.message}`);
        await NativeBridge.hangupGsmPrivileged(slot);
        this.state.callStates[slot] = CallState.IDLE;
        this.notify('state_changed', this.state);
    }
  }

  public async handleSipToGsmCall(slot: 0 | 1, targetGsmNumber: string, asteriskChannelId: string) {
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
    this.notify('state_changed', this.state);
  }

  public async terminateCall(slot: 0 | 1, reason: string) {
    if (this.state.callStates[slot] === CallState.IDLE) return;
    this.log('INFO', 'CORE', `Terminating Call on Channel ${slot+1}: ${reason}`);

    await this.asteriskBridges[slot].stopAudioBridge(slot);
    await NativeBridge.setAudioRouting(slot, 'COMMUNICATION');

    const activeCall = this.state.activeCalls[slot];
    if (activeCall) {
        await this.asteriskBridges[slot].hangupCall(activeCall.id);
    }

    await NativeBridge.hangupGsmPrivileged(slot);
    this.state.callStates[slot] = CallState.IDLE;
    this.state.activeCalls[slot] = null;
    this.notify('state_changed', this.state);
  }

  private handleAsteriskCallEvent(event: string, channelId: string, data?: any) {
    this.log('INFO', 'ASTERISK', `Call event: ${event} for channel ${channelId}`);

    switch (event) {
      case 'answered':
        // Asterisk has answered the call, now we can start audio bridging
        this.handleAsteriskCallAnswered(channelId);
        break;

      case 'hangup':
        // Asterisk has hung up the call
        this.handleAsteriskCallHangup(channelId);
        break;

      case 'bridge_established':
        // Audio bridge between SIP and GSM is now active
        this.log('INFO', 'ASTERISK', `Audio bridge established for ${channelId}`);
        break;

      default:
        this.log('DEBUG', 'ASTERISK', `Unhandled event: ${event}`);
    }
  }

  private handleAsteriskCallAnswered(channelId: string) {
    // Find the active call with this channel ID
    for (let slot = 0; slot < 2; slot++) {
      const activeCall = this.state.activeCalls[slot];
      if (activeCall && activeCall.id === channelId) {
        this.log('INFO', 'ASTERISK', `Call ${channelId} answered, starting audio bridge`);

        // Start audio bridging now that both sides are connected
        this.asteriskBridges[slot].startAudioBridge(slot);
        break;
      }
    }
  }

  private handleAsteriskCallHangup(channelId: string) {
    // Find and terminate the call
    for (let slot = 0; slot < 2; slot++) {
      const activeCall = this.state.activeCalls[slot];
      if (activeCall && activeCall.id === channelId) {
        this.log('INFO', 'ASTERISK', `Call ${channelId} hung up by Asterisk`);
        this.terminateCall(slot as 0 | 1, 'ASTERISK_HANGUP');
        break;
      }
    }
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