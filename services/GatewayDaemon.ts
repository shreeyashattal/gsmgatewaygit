import { CallState, BridgeStatus, GsmStatus, LogEntry, GatewayConfig, ActiveCall, BackendMetrics, ChannelConfig } from '../types';
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
      asteriskContext: `from-gsm${id}`,
      defaultExtension: 's',
      codec: 'PCMU',
      rtpPort: id === 1 ? 5004 : 5006
    };
  }

  private constructor() {
    this.asteriskBridges = [this.createAsteriskBridge(0), this.createAsteriskBridge(1)];
    this.initializeService();
  }

  private createAsteriskBridge(slot: 0 | 1): AsteriskBridge {
    return new AsteriskBridge({
      onStatusChange: (status) => {
        this.state.bridgeStatus = status;
        this.state.metrics.bridgeStatus = status;
        this.notify('state_changed', this.state);
      },
      onLog: (m) => this.log('DEBUG', `AMI_CH${slot+1}`, m),
      onRemoteBye: (callId) => {
        if (this.state.activeCalls[slot]?.id === callId) {
          this.terminateCall(slot, 'ASTERISK_HANGUP');
        }
      },
      onIncomingInvite: async (callId, from) => {
        this.log('INFO', 'AMI', `Incoming call from ${from} [Channel: ${callId}] - Processing...`);
        if (this.state.callStates[slot] !== CallState.IDLE) {
          this.log('WARN', 'AMI', `Rejecting call - slot ${slot} busy`);
          return;
        }

        try {
          this.log('INFO', 'AMI', `Processing incoming call ${callId}...`);
          await this.asteriskBridges[slot].answerCall(callId);
          this.log('INFO', 'AMI', `Call ${callId} answered successfully`);
          this.handleAsteriskToGsmCall(slot, from, callId);
        } catch (e) {
          this.log('ERROR', 'AMI', `Failed to process incoming call ${callId}: ${e}`);
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

    // Auto-connect to Asterisk AMI
    this.connectToAsterisk();

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

  private async connectToAsterisk() {
    this.log('INFO', 'AMI', 'Connecting to Asterisk AMI at 127.0.0.1:5038...');
    this.state.bridgeStatus = BridgeStatus.CONNECTING;
    this.state.metrics.bridgeStatus = BridgeStatus.CONNECTING;
    this.notify('state_changed', this.state);

    try {
      // Both bridges share the same AMI connection in practice
      await this.asteriskBridges[0].connect();
      this.state.bridgeStatus = BridgeStatus.CONNECTED;
      this.state.metrics.bridgeStatus = BridgeStatus.CONNECTED;
      this.log('INFO', 'AMI', 'Connected to Asterisk AMI successfully');
    } catch (e: any) {
      this.state.bridgeStatus = BridgeStatus.ERROR;
      this.state.metrics.bridgeStatus = BridgeStatus.ERROR;
      this.log('ERROR', 'AMI', `Failed to connect to Asterisk: ${e.message}`);
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

  // Method for Asterisk to request outgoing GSM call
  public async makeGsmCallFromAsterisk(slot: 0 | 1, phoneNumber: string, asteriskChannelId: string): Promise<boolean> {
    const channel = this.state.config.channels[slot];
    if (!channel.enabled || this.state.callStates[slot] !== CallState.IDLE) {
      this.log('WARN', 'AMI', `Cannot make GSM call - Channel disabled or slot ${slot} busy`);
      return false;
    }

    const otherSlot = slot === 0 ? 1 : 0;
    if (this.state.callStates[otherSlot] === CallState.BRIDGING) {
      this.log('WARN', 'AMI', `DSDS Radio Busy: Bridge on SIM${otherSlot+1} active. GSM call ignored.`);
      return false;
    }

    this.state.callStates[slot] = CallState.OUTGOING_GSM;
    this.log('INFO', 'AMI', `Outgoing GSM Call requested by Asterisk to ${phoneNumber}`);

    try {
      const success = await NativeBridge.makeGsmCallForAsterisk(slot, phoneNumber);

      if (success) {
        this.state.activeCalls[slot] = {
          id: asteriskChannelId,
          simSlot: slot,
          gsmNumber: phoneNumber,
          asteriskChannel: asteriskChannelId,
          startTime: Date.now(),
          direction: 'ASTERISK_TO_GSM',
          durationSeconds: 0,
          signaling: ['ASTERISK_REQUEST', 'GSM_MODEM_DIAL'],
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
      this.log('ERROR', 'AMI', `Failed to make GSM call: ${e.message}`);
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
      // For incoming GSM calls, we tell Asterisk to originate a call
      const destination = channel.defaultExtension;
      const context = channel.asteriskContext;

      const cid = await this.asteriskBridges[slot].createInvite(slot, destination);
      this.state.activeCalls[slot] = {
        id: cid,
        simSlot: slot,
        gsmNumber: phoneNumber,
        asteriskChannel: cid,
        startTime: Date.now(),
        direction: 'GSM_TO_ASTERISK',
        durationSeconds: 0,
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

  public async handleAsteriskToGsmCall(slot: 0 | 1, targetGsmNumber: string, asteriskChannelId: string) {
    const channel = this.state.config.channels[slot];
    if (!channel.enabled || this.state.callStates[slot] !== CallState.IDLE) {
      this.log('WARN', 'AMI', `Incoming Asterisk call ignored (Channel disabled or Busy)`);
      return;
    }

    const otherSlot = slot === 0 ? 1 : 0;
    if (this.state.callStates[otherSlot] === CallState.BRIDGING) {
      this.log('WARN', 'AMI', `Inter-Slot Radio Conflict: Dropping call for SIM${slot+1}.`);
      return;
    }

    this.state.callStates[slot] = CallState.INCOMING_ASTERISK;
    this.log('INFO', 'AMI', `Incoming Asterisk Call -> External GSM: ${targetGsmNumber}`);

    await NativeBridge.dialGsmSilently(slot, targetGsmNumber);

    this.state.activeCalls[slot] = {
      id: asteriskChannelId,
      simSlot: slot,
      gsmNumber: targetGsmNumber,
      asteriskChannel: asteriskChannelId,
      startTime: Date.now(),
      direction: 'ASTERISK_TO_GSM',
      durationSeconds: 0,
      signaling: ['ASTERISK_INCOMING', 'GSM_MODEM_DIAL', 'WAIT_FOR_GSM_ANSWER'],
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
    this.log('INFO', 'AMI', `Call event: ${event} for channel ${channelId}`);

    switch (event) {
      case 'answered':
        this.handleAsteriskCallAnswered(channelId);
        break;
      case 'hangup':
        this.handleAsteriskCallHangup(channelId);
        break;
      case 'bridge_established':
        this.log('INFO', 'AMI', `Audio bridge established for ${channelId}`);
        break;
      default:
        this.log('DEBUG', 'AMI', `Unhandled event: ${event}`);
    }
  }

  private handleAsteriskCallAnswered(channelId: string) {
    for (let slot = 0; slot < 2; slot++) {
      const activeCall = this.state.activeCalls[slot];
      if (activeCall && activeCall.id === channelId) {
        this.log('INFO', 'AMI', `Call ${channelId} answered, starting audio bridge`);
        this.asteriskBridges[slot].startAudioBridge(slot);
        break;
      }
    }
  }

  private handleAsteriskCallHangup(channelId: string) {
    for (let slot = 0; slot < 2; slot++) {
      const activeCall = this.state.activeCalls[slot];
      if (activeCall && activeCall.id === channelId) {
        this.log('INFO', 'AMI', `Call ${channelId} hung up by Asterisk`);
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
