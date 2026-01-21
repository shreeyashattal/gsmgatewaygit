import { CallState, BridgeStatus, GsmStatus, LogEntry, GatewayConfig, ActiveCall, BackendMetrics, ChannelConfig } from '../types';
import { NativeBridge } from './NativeBridge';
import { SIPBridge } from './SipStack';
import { PersistenceService } from './PersistenceService';
import { SIPCallHandler } from './SIPCallHandler';

type EventCallback = (data: any) => void;

class GatewayDaemon {
  private static instance: GatewayDaemon;
  private subscribers: Map<string, EventCallback[]> = new Map();
  private sipBridges: [SIPBridge, SIPBridge];
  private bootTime: number = Date.now();

  public state: {
    callStates: [CallState, CallState];
    bridgeStatus: BridgeStatus;
    activeCalls: [ActiveCall | null, ActiveCall | null];
    metrics: BackendMetrics;
    config: GatewayConfig;
  } = {
    callStates: [CallState.IDLE, CallState.IDLE],
    bridgeStatus: BridgeStatus.DISCONNECTED,
    activeCalls: [null, null],
    metrics: {
      cpuUsage: 0, memUsage: 0, temp: 0, uptime: 0,
      processor: 'GENERIC',
      isRooted: false,
      slotCount: 1,
      bridgeStatus: BridgeStatus.DISCONNECTED,
      sims: [
        { radioSignal: 0, carrier: 'Initializing...', status: GsmStatus.SEARCHING, phoneNumber: '', connectionType: 'Tower', networkType: 'Unknown' },
        { radioSignal: 0, carrier: 'Initializing...', status: GsmStatus.SEARCHING, phoneNumber: '', connectionType: 'Tower', networkType: 'Unknown' }
      ]
    },
    config: PersistenceService.loadConfig() || this.getDefaultConfig()
  };

  private getDefaultConfig(): GatewayConfig {
    return {
      channels: [this.createDefaultChannel(1), this.createDefaultChannel(2)],
      pbxHost: '',
      pbxPort: 5060,
      localSipPort: 5080,
      trunkMode: true,  // Default to trunk mode (PBX registers with us)
      autoAnswer: true,
      rootLevel: true,
      jitterBufferMs: 60,
      keepAliveInterval: 30,
      speakerphoneOn: false
    };
  }

  private createDefaultChannel(id: number): ChannelConfig {
    return {
      enabled: id === 1, // Only first channel enabled by default
      sipUsername: `sim${id}`,
      sipPassword: '',
      codec: 'PCMU',
      rtpPort: id === 1 ? 10000 : 10002
    };
  }

  private constructor() {
    this.sipBridges = [this.createSipBridge(0), this.createSipBridge(1)];
    this.initializeService();
  }

  private createSipBridge(slot: 0 | 1): SIPBridge {
    return new SIPBridge({
      onStatusChange: (status) => {
        this.state.bridgeStatus = status;
        this.state.metrics.bridgeStatus = status;
        this.notify('state_changed', this.state);
      },
      onLog: (m) => this.log('DEBUG', `SIP_CH${slot+1}`, m),
      onRemoteBye: (callId) => {
        if (this.state.activeCalls[slot]?.id === callId) {
          this.terminateCall(slot, 'SIP_HANGUP');
        }
      },
      onIncomingInvite: async (callId, from) => {
        this.log('INFO', 'SIP', `Incoming call from ${from} [Call-ID: ${callId}] - Processing...`);
        if (this.state.callStates[slot] !== CallState.IDLE) {
          this.log('WARN', 'SIP', `Rejecting call - slot ${slot} busy`);
          return;
        }

        try {
          this.log('INFO', 'SIP', `Processing incoming call ${callId}...`);
          await this.sipBridges[slot].answerCall(callId);
          this.log('INFO', 'SIP', `Call ${callId} answered successfully`);
          this.handleSipToGsmCall(slot, from, callId);
        } catch (e) {
          this.log('ERROR', 'SIP', `Failed to process incoming call ${callId}: ${e}`);
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

    // Initialize SIP stack
    this.initializeSip();

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

    // Setup SIP call handlers
    const sipHandler = SIPCallHandler.getInstance();
    sipHandler.setGsmCallRequestHandler(async (slot, phoneNumber, callId) => {
      return await this.makeGsmCallFromSip(slot as 0 | 1, phoneNumber, callId);
    });

    sipHandler.setCallEventHandler((event, callId, data) => {
      this.handleSipCallEvent(event, callId, data);
    });

    this.notify('state_changed', this.state);
  }

  private async initializeSip() {
    const { trunkMode, localSipPort, pbxHost, pbxPort } = this.state.config;
    this.log('INFO', 'SIP', `Initializing SIP stack on port ${localSipPort}...`);
    this.state.bridgeStatus = BridgeStatus.CONNECTING;
    this.state.metrics.bridgeStatus = BridgeStatus.CONNECTING;
    this.notify('state_changed', this.state);

    try {
      await this.sipBridges[0].connect();
      this.state.bridgeStatus = BridgeStatus.CONNECTED;
      this.state.metrics.bridgeStatus = BridgeStatus.CONNECTED;
      this.log('INFO', 'SIP', trunkMode ?
        `SIP stack listening on port ${localSipPort} (trunk mode)` :
        `SIP registered with PBX at ${pbxHost}:${pbxPort}`);
    } catch (e: any) {
      this.state.bridgeStatus = BridgeStatus.ERROR;
      this.state.metrics.bridgeStatus = BridgeStatus.ERROR;
      this.log('ERROR', 'SIP', `Failed to initialize SIP: ${e.message}`);
    }
    this.notify('state_changed', this.state);
  }

  public toggleChannel(slot: 0 | 1) {
    const channel = this.state.config.channels[slot];
    channel.enabled = !channel.enabled;

    if (channel.enabled) {
      this.log('INFO', 'CORE', `Channel ${slot+1} ENABLED`);
    } else {
      this.log('WARN', 'CORE', `Channel ${slot+1} DISABLED`);
      // Terminate any active call on this channel
      if (this.state.callStates[slot] !== CallState.IDLE) {
        this.terminateCall(slot, 'CHANNEL_DISABLED');
      }
    }
    this.updateConfig(this.state.config);
  }

  public updateConfig(newConfig: GatewayConfig) {
    this.state.config = { ...newConfig };
    PersistenceService.saveConfig(this.state.config);
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
                connectionType: simInfo[0].connectionType || this.state.metrics.sims[0].connectionType,
                networkType: simInfo[0].networkType || this.state.metrics.sims[0].networkType
              };
            }
            if (this.state.metrics.slotCount > 1 && simInfo.length > 1 && simInfo[1]) {
              this.state.metrics.sims[1] = {
                ...this.state.metrics.sims[1],
                ...simInfo[1],
                phoneNumber: simInfo[1].phoneNumber || this.state.metrics.sims[1].phoneNumber,
                radioSignal: simInfo[1].radioSignal || this.state.metrics.sims[1].radioSignal,
                connectionType: simInfo[1].connectionType || this.state.metrics.sims[1].connectionType,
                networkType: simInfo[1].networkType || this.state.metrics.sims[1].networkType
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

  // Method for SIP to request outgoing GSM call
  public async makeGsmCallFromSip(slot: 0 | 1, phoneNumber: string, sipCallId: string): Promise<boolean> {
    const channel = this.state.config.channels[slot];
    if (!channel.enabled || this.state.callStates[slot] !== CallState.IDLE) {
      this.log('WARN', 'SIP', `Cannot make GSM call - Channel disabled or slot ${slot} busy`);
      return false;
    }

    const otherSlot = slot === 0 ? 1 : 0;
    if (this.state.callStates[otherSlot] === CallState.BRIDGING) {
      this.log('WARN', 'SIP', `DSDS Radio Busy: Bridge on SIM${otherSlot+1} active. GSM call ignored.`);
      return false;
    }

    this.state.callStates[slot] = CallState.OUTGOING_GSM;
    this.log('INFO', 'SIP', `Outgoing GSM Call requested via SIP to ${phoneNumber}`);

    try {
      const success = await NativeBridge.makeGsmCallForSip(slot, phoneNumber);

      if (success) {
        this.state.activeCalls[slot] = {
          id: sipCallId,
          simSlot: slot,
          gsmNumber: phoneNumber,
          sipCallId: sipCallId,
          startTime: Date.now(),
          direction: 'SIP_TO_GSM',
          durationSeconds: 0,
          signaling: ['SIP_INVITE', 'GSM_MODEM_DIAL'],
          audioMetrics: { latency: 45, jitter: 5, rxPackets: 0, txPackets: 0, bufferDepth: 60 }
        };

        this.state.callStates[slot] = CallState.BRIDGING;
        await NativeBridge.setAudioRouting(slot, 'IN_CALL');
        this.notify('state_changed', this.state);
        return true;
      } else {
        this.state.callStates[slot] = CallState.IDLE;
        this.notify('state_changed', this.state);
        return false;
      }
    } catch (e: any) {
      this.log('ERROR', 'SIP', `Failed to make GSM call: ${e.message}`);
      this.state.callStates[slot] = CallState.IDLE;
      this.notify('state_changed', this.state);
      return false;
    }
  }

  public async handleIncomingGsm(slot: 0 | 1, phoneNumber: string) {
    const channel = this.state.config.channels[slot];
    if (!channel.enabled || this.state.callStates[slot] !== CallState.IDLE) {
      this.log('WARN', 'MODEM', `Incoming GSM Ignored (Channel disabled or Busy)`);
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
      // For incoming GSM calls, we send a SIP INVITE to the PBX
      const sipUsername = channel.sipUsername || `sim${slot + 1}`;

      const callId = await this.sipBridges[slot].createInvite(slot, sipUsername);
      this.state.activeCalls[slot] = {
        id: callId,
        simSlot: slot,
        gsmNumber: phoneNumber,
        sipCallId: callId,
        startTime: Date.now(),
        direction: 'GSM_TO_SIP',
        durationSeconds: 0,
        signaling: ['GSM_INCOMING', 'GSM_ANSWERED', 'SIP_INVITE'],
        audioMetrics: { latency: 32, jitter: 1, rxPackets: 0, txPackets: 0, bufferDepth: 60 }
      };

      this.state.callStates[slot] = CallState.BRIDGING;
      await NativeBridge.setAudioRouting(slot, 'IN_CALL');
      await this.sipBridges[slot].startAudioBridge(slot);
      this.notify('state_changed', this.state);
    } catch (e: any) {
      this.log('ERROR', 'CORE', `Failed to bridge call: ${e.message}`);
      await NativeBridge.hangupGsmPrivileged(slot);
      this.state.callStates[slot] = CallState.IDLE;
      this.notify('state_changed', this.state);
    }
  }

  public async handleSipToGsmCall(slot: 0 | 1, targetGsmNumber: string, sipCallId: string) {
    const channel = this.state.config.channels[slot];
    if (!channel.enabled || this.state.callStates[slot] !== CallState.IDLE) {
      this.log('WARN', 'SIP', `Incoming SIP call ignored (Channel disabled or Busy)`);
      return;
    }

    const otherSlot = slot === 0 ? 1 : 0;
    if (this.state.callStates[otherSlot] === CallState.BRIDGING) {
      this.log('WARN', 'SIP', `Inter-Slot Radio Conflict: Dropping call for SIM${slot+1}.`);
      return;
    }

    this.state.callStates[slot] = CallState.INCOMING_SIP;
    this.log('INFO', 'SIP', `Incoming SIP Call -> External GSM: ${targetGsmNumber}`);

    await NativeBridge.dialGsmSilently(slot, targetGsmNumber);

    this.state.activeCalls[slot] = {
      id: sipCallId,
      simSlot: slot,
      gsmNumber: targetGsmNumber,
      sipCallId: sipCallId,
      startTime: Date.now(),
      direction: 'SIP_TO_GSM',
      durationSeconds: 0,
      signaling: ['SIP_INVITE', 'GSM_MODEM_DIAL', 'WAIT_FOR_GSM_ANSWER'],
      audioMetrics: { latency: 45, jitter: 5, rxPackets: 0, txPackets: 0, bufferDepth: 60 }
    };

    this.state.callStates[slot] = CallState.BRIDGING;
    await NativeBridge.setAudioRouting(slot, 'IN_CALL');
    this.notify('state_changed', this.state);
  }

  public async terminateCall(slot: 0 | 1, reason: string) {
    if (this.state.callStates[slot] === CallState.IDLE) return;
    this.log('INFO', 'CORE', `Terminating Call on Channel ${slot+1}: ${reason}`);

    await this.sipBridges[slot].stopAudioBridge(slot);
    await NativeBridge.setAudioRouting(slot, 'COMMUNICATION');

    const activeCall = this.state.activeCalls[slot];
    if (activeCall) {
      await this.sipBridges[slot].hangupCall(activeCall.id);
    }

    await NativeBridge.hangupGsmPrivileged(slot);
    this.state.callStates[slot] = CallState.IDLE;
    this.state.activeCalls[slot] = null;
    this.notify('state_changed', this.state);
  }

  private handleSipCallEvent(event: string, callId: string, data?: any) {
    this.log('INFO', 'SIP', `Call event: ${event} for call ${callId}`);

    switch (event) {
      case 'answered':
        this.handleSipCallAnswered(callId);
        break;
      case 'hangup':
        this.handleSipCallHangup(callId);
        break;
      case 'bridge_established':
        this.log('INFO', 'SIP', `Audio bridge established for ${callId}`);
        break;
      default:
        this.log('DEBUG', 'SIP', `Unhandled event: ${event}`);
    }
  }

  private handleSipCallAnswered(callId: string) {
    for (let slot = 0; slot < 2; slot++) {
      const activeCall = this.state.activeCalls[slot];
      if (activeCall && activeCall.id === callId) {
        this.log('INFO', 'SIP', `Call ${callId} answered, starting audio bridge`);
        this.sipBridges[slot].startAudioBridge(slot);
        break;
      }
    }
  }

  private handleSipCallHangup(callId: string) {
    for (let slot = 0; slot < 2; slot++) {
      const activeCall = this.state.activeCalls[slot];
      if (activeCall && activeCall.id === callId) {
        this.log('INFO', 'SIP', `Call ${callId} hung up via SIP`);
        this.terminateCall(slot as 0 | 1, 'SIP_HANGUP');
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
