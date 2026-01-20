import { ProcessorType, GsmStatus, SimMetrics } from '../types';
import { registerPlugin, Capacitor } from '@capacitor/core';

interface ShellPlugin {
  execute(options: { command: string, asRoot: boolean }): Promise<{ output: string, exitCode: number, error?: string }>;
}

interface TelephonyPlugin {
  getSimInfo(): Promise<{ sims: any[]; slotCount: number }>;
}

// Plugin registration with fallback for browser mode
let Shell: ShellPlugin;
let Telephony: TelephonyPlugin;

try {
  Shell = registerPlugin<ShellPlugin>('Shell');
} catch (e) {
  console.warn('[NATIVE_BRIDGE] Shell plugin not available, using mock');
  Shell = {
    execute: async () => ({ output: '', exitCode: 1, error: 'Shell plugin not available' })
  };
}

try {
  Telephony = registerPlugin<TelephonyPlugin>('Telephony');
} catch (e) {
  console.warn('[NATIVE_BRIDGE] Telephony plugin not available, using mock');
  Telephony = {
    getSimInfo: async () => ({ sims: [], slotCount: 0 })
  };
}

export class NativeBridge {
  private static onGsmDisconnectCallback?: (slot: 0 | 1) => void;
  private static onGsmIncomingCallCallback?: (slot: 0 | 1, phoneNumber: string) => void;
  private static currentSoC: ProcessorType = 'QUALCOMM';
  private static slotCount: number = 2;
  private static rootStatusCached: boolean | null = null;
  private static hardwareInitialized: boolean = false;

  /**
   * Initialize hardware - called ONCE at app startup
   * This is the only place that should check root access
   */
  static async initHardware(): Promise<{ isRooted: boolean; soc: ProcessorType; slotCount: number; simInfo: SimMetrics[] }> {
    // Only initialize once
    if (this.hardwareInitialized && this.rootStatusCached !== null) {
      console.log("[NATIVE_BRIDGE] Hardware already initialized, returning cached data");
      const simInfo = await this.fetchSimDetails();
      return {
        isRooted: this.rootStatusCached,
        soc: this.currentSoC,
        slotCount: this.slotCount,
        simInfo
      };
    }

    try {
      const isBrowserMode = !Capacitor.isNativePlatform();

      if (isBrowserMode) {
        console.log("[NATIVE_BRIDGE] Running in browser mode - returning mock data");
        this.hardwareInitialized = true;
        this.rootStatusCached = true;
        return {
          isRooted: true,
          soc: 'GENERIC',
          slotCount: 2,
          simInfo: [
            { carrier: 'Mock Carrier 1', status: GsmStatus.READY, radioSignal: -75, phoneNumber: '+1234567890', connectionType: 'Tower', networkType: 'LTE' },
            { carrier: 'Mock Carrier 2', status: GsmStatus.READY, radioSignal: -82, phoneNumber: '+0987654321', connectionType: 'Tower', networkType: '5G' }
          ]
        };
      }

      // Check root status ONLY ONCE
      console.log("[NATIVE_BRIDGE] Checking Root Access (one-time)...");
      const idResult = await this.executeRootCommand('id');
      const isRooted = idResult.includes('uid=0');
      this.rootStatusCached = isRooted;

      if (!isRooted) {
        console.warn("[NATIVE_BRIDGE] Root check failed. App will run in RESTRICTED mode.");
        this.hardwareInitialized = true;
        return { isRooted: false, soc: 'GENERIC', slotCount: 1, simInfo: [] };
      }

      // Identify SoC (one-time)
      const platform = await this.executeRootCommand('getprop ro.board.platform');
      if (platform.includes('msm') || platform.includes('sdm') || platform.includes('qcom') || platform.includes('bengal')) {
        this.currentSoC = 'QUALCOMM';
      } else if (platform.includes('mt') || platform.includes('mediatek') || platform.includes('helio')) {
        this.currentSoC = 'MEDIATEK';
      } else if (platform.includes('exynos')) {
        this.currentSoC = 'EXYNOS';
      }

      // Detect Slot Count (one-time)
      const simCountProp = await this.executeRootCommand('getprop ro.telephony.default_network');
      this.slotCount = simCountProp.split(',').length >= 2 ? 2 : 1;

      // Fetch SIM info using Telephony plugin (no root needed)
      const simInfo = await this.fetchSimDetails();

      this.hardwareInitialized = true;
      return { isRooted, soc: this.currentSoC, slotCount: this.slotCount, simInfo };
    } catch (e: any) {
      console.error("[NATIVE_BRIDGE] Init Failed:", e);
      this.hardwareInitialized = true;
      this.rootStatusCached = false;
      return { isRooted: false, soc: 'GENERIC', slotCount: 1, simInfo: [] };
    }
  }

  /**
   * Fetch SIM details - uses Telephony plugin (NO ROOT REQUIRED)
   * This can be called periodically without triggering root prompts
   */
  static async fetchSimDetails(): Promise<SimMetrics[]> {
    // In browser mode, return mock data
    if (!Capacitor.isNativePlatform()) {
      return [
        { carrier: 'Mock Carrier 1', status: GsmStatus.READY, radioSignal: -75, phoneNumber: '+1234567890', connectionType: 'Tower', networkType: 'LTE' },
        { carrier: 'Mock Carrier 2', status: GsmStatus.READY, radioSignal: -82, phoneNumber: '+0987654321', connectionType: 'Tower', networkType: '5G' }
      ];
    }

    try {
      // Use Telephony plugin - this uses Android APIs, NO ROOT NEEDED
      const result = await Telephony.getSimInfo();
      if (result && result.sims && result.sims.length > 0) {
        console.log("[NATIVE_BRIDGE] Got SIM info from Telephony plugin");
        const sims: SimMetrics[] = result.sims.map((sim: any) => ({
          carrier: sim.carrier || 'Unknown',
          status: sim.status === 'READY' ? GsmStatus.READY :
                  sim.status === 'NOT_DETECTED' ? GsmStatus.NOT_DETECTED : GsmStatus.SEARCHING,
          radioSignal: sim.radioSignal || -110,
          phoneNumber: sim.phoneNumber || '',
          connectionType: (sim.connectionType as 'Tower' | 'VoWiFi') || 'Tower',
          networkType: sim.networkType || 'Unknown'
        }));

        if (result.slotCount) {
          this.slotCount = result.slotCount;
        }

        return sims;
      }

      console.warn('[NATIVE_BRIDGE] Telephony plugin returned empty data');
      return [];
    } catch (e) {
      console.error('[NATIVE_BRIDGE] Failed to fetch SIM details:', e);
      return [];
    }
  }

  /**
   * Execute a root command - ONLY use for audio routing and call control
   * Do NOT use for periodic SIM info fetching
   */
  static async executeRootCommand(cmd: string): Promise<string> {
    // If running in browser mode, return mock data
    if (!Capacitor.isNativePlatform()) {
      console.log(`[MOCK_ROOT_SHELL] Browser mode: simulating '${cmd}'`);
      if (cmd === 'id') return "uid=0(root) gid=0(root)";
      if (cmd.includes('ro.telephony.default_network')) return "9,9";
      if (cmd.includes('ro.board.platform')) return "generic";
      return "OK";
    }

    // If we know root is not available, don't try
    if (this.rootStatusCached === false) {
      console.warn('[ROOT_SHELL] Root not available, skipping command');
      return "";
    }

    try {
      const result = await Shell.execute({ command: cmd, asRoot: true });

      if (result.exitCode !== 0 && result.error) {
        const errorMsg = result.error.toLowerCase();
        if (errorMsg.includes('permission denied') || errorMsg.includes('not allowed')) {
          console.error(`[ROOT_SHELL] Root permission denied for '${cmd}'`);
          this.rootStatusCached = false;
          return "";
        }
      }

      return result.output ? result.output.trim() : "";
    } catch (e: any) {
      console.error(`[ROOT_SHELL] Execution Failed for '${cmd}':`, e);
      return "";
    }
  }

  static async setAudioRouting(slot: 0 | 1, mode: 'COMMUNICATION' | 'IN_CALL'): Promise<void> {
    if (!this.rootStatusCached) {
      console.warn('[AUDIO_HAL] Root not available, cannot set audio routing');
      return;
    }

    const simId = slot + 1;
    if (mode === 'IN_CALL') {
      console.log(`[AUDIO_HAL] Engaging Audio Bridge [SoC: ${this.currentSoC}, Slot: ${simId}]`);
      if (this.currentSoC === 'QUALCOMM') {
        await this.executeRootCommand(`tinymix 'Voice Rx' 'AFE_LOOPBACK_TX'`);
        await this.executeRootCommand(`tinymix 'AFE_LOOPBACK_RX' 'Voice Tx'`);
        await this.executeRootCommand(`tinymix 'Voice Call' '1'`);
      } else if (this.currentSoC === 'MEDIATEK') {
        await this.executeRootCommand(`tinymix 'DL1_2' '1'`);
        await this.executeRootCommand(`tinymix 'UL1_2' '1'`);
      }
      await this.executeRootCommand(`chmod 0666 /dev/snd/pcmC0D*`);
    } else {
      console.log(`[AUDIO_HAL] Resetting SIM${simId} audio path.`);
      if (this.currentSoC === 'QUALCOMM') {
        await this.executeRootCommand(`tinymix 'Voice Rx' 'NONE'`);
        await this.executeRootCommand(`tinymix 'Voice Call' '0'`);
      }
    }
  }

  static async answerGsmCallPrivileged(slot: 0 | 1): Promise<boolean> {
    if (!this.rootStatusCached) return false;
    await this.executeRootCommand(`input keyevent KEYCODE_CALL`);
    return true;
  }

  static async dialGsmSilently(slot: 0 | 1, number: string): Promise<void> {
    if (!this.rootStatusCached) return;
    // Use am command to dial
    await this.executeRootCommand(`am start -a android.intent.action.CALL -d tel:${number}`);
  }

  static async hangupGsmPrivileged(slot: 0 | 1): Promise<void> {
    if (!this.rootStatusCached) return;
    await this.executeRootCommand(`input keyevent KEYCODE_ENDCALL`);
  }

  static setGsmDisconnectListener(callback: (slot: 0 | 1) => void) {
    this.onGsmDisconnectCallback = callback;
  }

  static setGsmIncomingCallListener(callback: (slot: 0 | 1, phoneNumber: string) => void) {
    this.onGsmIncomingCallCallback = callback;
  }

  static async makeGsmCallForAsterisk(slot: 0 | 1, phoneNumber: string): Promise<boolean> {
    console.log(`[GSM_OUTBOUND] Asterisk requested GSM call to ${phoneNumber} on slot ${slot}`);
    try {
      await this.dialGsmSilently(slot, phoneNumber);
      return true;
    } catch (e) {
      console.error(`[GSM_OUTBOUND] Failed to dial ${phoneNumber}:`, e);
      return false;
    }
  }

  static isRooted(): boolean {
    return this.rootStatusCached === true;
  }
}
