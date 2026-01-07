import { ProcessorType, GsmStatus } from '../types';
import { registerPlugin } from '@capacitor/core';

interface ShellPlugin {
  execute(options: { command: string, asRoot: boolean }): Promise<{ output: string, exitCode: number, error?: string }>;
}

interface TelephonyPlugin {
  getSimInfo(): Promise<{ sims: any[]; slotCount: number }>;
  requestPermissions(): Promise<{ granted: boolean }>;
}

const Shell = registerPlugin<ShellPlugin>('Shell');
const Telephony = registerPlugin<TelephonyPlugin>('Telephony');

export class NativeBridge {
  private static onGsmDisconnectCallback?: (slot: 0 | 1) => void;
  private static currentSoC: ProcessorType = 'QUALCOMM';
  private static slotCount: number = 2;
  private static rootStatusCached: boolean | null = null; // Cache root status to avoid repeated checks

  static async initHardware(): Promise<{ isRooted: boolean; soc: ProcessorType; slotCount: number; simInfo: any[] }> {
    try {
      // Check root status only once and cache it
      if (this.rootStatusCached === null) {
        console.log("[NATIVE_BRIDGE] Checking Root Access (one-time check)...");
        const idResult = await this.executeRootCommand('id', true); // true = initial check
        const isRooted = idResult.includes('uid=0');
        this.rootStatusCached = isRooted;
        
        if (!isRooted) {
            console.warn("[NATIVE_BRIDGE] Root check failed. App will run in RESTRICTED mode.");
            console.warn("[NATIVE_BRIDGE] If Magisk prompt appeared, please grant SuperUser access and reload the app.");
            // Do NOT return fake root. Return actual status.
            return { isRooted: false, soc: 'GENERIC', slotCount: 1, simInfo: [] }; 
        }
      }
      
      const isRooted = this.rootStatusCached;

      // 2. Identify SoC
      const platform = await this.executeRootCommand('getprop ro.board.platform');
      if (platform.includes('msm') || platform.includes('sdm') || platform.includes('qcom') || platform.includes('bengal')) {
        this.currentSoC = 'QUALCOMM';
      } else if (platform.includes('mt') || platform.includes('mediatek') || platform.includes('helio')) {
        this.currentSoC = 'MEDIATEK';
      } else if (platform.includes('exynos')) {
        this.currentSoC = 'EXYNOS';
      }

      // 3. Detect Slot Count & SIM Info
      const simCountProp = await this.executeRootCommand('getprop ro.telephony.default_network');
      this.slotCount = simCountProp.split(',').length >= 2 ? 2 : 1;
      
      const simInfo = await this.fetchSimDetails();

      return { isRooted, soc: this.currentSoC, slotCount: this.slotCount, simInfo };
    } catch (e: any) {
      console.error("[NATIVE_BRIDGE] Init Failed:", e);
      // Check if it's a root permission issue
      if (e.message && (e.message.includes('ROOT_PERMISSION_DENIED') || 
                       e.message.includes('Root permission denied') ||
                       e.message.includes('Root access unavailable'))) {
          console.error("[NATIVE_BRIDGE] Root permission was denied. Please grant SuperUser access in Magisk and reload the app.");
      }
      return { isRooted: false, soc: 'GENERIC', slotCount: 1, simInfo: [] };
    }
  }

  static async fetchSimDetails() {
      try {
          // First, request permissions if needed
          try {
              await Telephony.requestPermissions();
          } catch (e) {
              console.warn('[NATIVE_BRIDGE] Permission request failed, continuing with fallback:', e);
          }
          
          // Use TelephonyManager plugin for proper SIM data access
          try {
              const result = await Telephony.getSimInfo();
              if (result && result.sims && result.sims.length > 0) {
                  // Convert plugin response to our format
                  const sims = result.sims.map((sim: any) => ({
                      carrier: sim.carrier || 'Unknown',
                      status: sim.status === 'READY' ? GsmStatus.READY : GsmStatus.NOT_DETECTED,
                      radioSignal: sim.radioSignal || -110,
                      phoneNumber: sim.phoneNumber || '',
                      connectionType: sim.connectionType || 'Tower'
                  }));
                  
                  // Update slot count
                  if (result.slotCount) {
                      this.slotCount = result.slotCount;
                  }
                  
                  return sims;
              }
          } catch (e) {
              console.warn('[NATIVE_BRIDGE] Telephony plugin failed, falling back to shell commands:', e);
          }
          
          // Fallback to shell commands if plugin fails
          const carriersStr = await this.executeRootCommand('getprop gsm.sim.operator.alpha');
          const statesStr = await this.executeRootCommand('getprop gsm.sim.state');
          const carriers = (carriersStr || '').split(',').map(c => c.trim());
          const states = (statesStr || '').split(',').map(s => s.trim());
          
          const sims = [];
          for (let i = 0; i < 2; i++) {
              const slot = i;
              const carrier = carriers[i] && carriers[i].length > 0 ? carriers[i] : (i === 0 ? "No SIM" : "Empty");
              const state = states[i] && states[i].includes('READY') ? GsmStatus.READY : GsmStatus.NOT_DETECTED;
              
              // Skip if SIM is not detected and it's slot 2
              if (i === 1 && state === GsmStatus.NOT_DETECTED && (!carriers[i] || carriers[i].length === 0)) {
                  continue; // Skip empty slot 2
              }
              
              // Fetch phone number using shell commands (fallback)
              let phoneNumber = '';
              try {
                  const dumpsysResult = await this.executeRootCommand(`dumpsys telephony.registry 2>/dev/null | grep -E "mLine1Number|phoneNumber" | grep -oP "\\+?[0-9]{10,}" | head -${slot + 1} | tail -1`);
                  if (dumpsysResult && dumpsysResult.length > 5) {
                      phoneNumber = dumpsysResult.trim();
                  }
              } catch (e) {
                  console.warn(`[NATIVE_BRIDGE] Could not fetch phone number for slot ${slot + 1}:`, e);
              }
              
              // Fetch signal strength (fallback)
              let radioSignal = -110;
              try {
                  const signalResult = await this.executeRootCommand(`dumpsys telephony.registry 2>/dev/null | grep -A 20 "slotIndex=${slot}" | grep -oP "mDbm=\\K-?\\d+" | head -1`);
                  if (signalResult) {
                      const signalValue = parseInt(signalResult.trim());
                      if (signalValue > -150 && signalValue < 0) {
                          radioSignal = signalValue;
                      }
                  }
              } catch (e) {
                  console.warn(`[NATIVE_BRIDGE] Could not fetch signal strength for slot ${slot + 1}:`, e);
              }
              
              // Determine connection type (fallback)
              let connectionType: 'Tower' | 'VoWiFi' = 'Tower';
              try {
                  const wifiState = await this.executeRootCommand(`getprop wlan.driver.status 2>/dev/null`);
                  const imsRegResult = await this.executeRootCommand(`dumpsys telephony.registry 2>/dev/null | grep -i "ims" | grep -i "registered" | head -1`);
                  if ((wifiState && wifiState.includes('ok')) && (imsRegResult && imsRegResult.length > 0)) {
                      connectionType = 'VoWiFi';
                  }
              } catch (e) {
                  console.warn(`[NATIVE_BRIDGE] Could not determine connection type for slot ${slot + 1}:`, e);
              }
              
              sims.push({
                  carrier: carrier,
                  status: state,
                  radioSignal: radioSignal,
                  phoneNumber: phoneNumber || '',
                  connectionType: connectionType
              });
          }
          return sims;
      } catch (e) {
          console.error(`[NATIVE_BRIDGE] Failed to fetch SIM details:`, e);
          return [];
      }
  }

  static async executeRootCommand(cmd: string, isInitialCheck: boolean = false): Promise<string> {
    try {
        // If root is already cached as false and this is not the initial check, skip root commands
        if (!isInitialCheck && this.rootStatusCached === false) {
            return "";
        }
        
        const result = await Shell.execute({ command: cmd, asRoot: true });
        
        // Check if root permission was denied
        if (result.exitCode !== 0) {
            const errorMsg = result.error || '';
            if (errorMsg.toLowerCase().includes('permission denied') || 
                errorMsg.toLowerCase().includes('not allowed') ||
                errorMsg.toLowerCase().includes('root permission denied')) {
                if (isInitialCheck) {
                    this.rootStatusCached = false;
                }
                console.error(`[ROOT_SHELL] Root permission denied for '${cmd}'. Please grant access in Magisk.`);
                throw new Error('ROOT_PERMISSION_DENIED');
            }
        }
        
        // If initial check succeeded, cache it
        if (isInitialCheck && cmd === 'id' && result.output.includes('uid=0')) {
            this.rootStatusCached = true;
        }
        
        return result.output ? result.output.trim() : "";
    } catch (e: any) {
        // If it's a root permission denial, re-throw it so initHardware can handle it
        if (e.message === 'ROOT_PERMISSION_DENIED' || 
            (e.message && (e.message.includes('Root permission denied') || 
                          e.message.includes('Root access unavailable')))) {
            if (isInitialCheck) {
                this.rootStatusCached = false;
            }
            throw e;
        }
        
        console.error(`[ROOT_SHELL] Execution Failed for '${cmd}': ${e.message}`);
        // If the plugin is missing (Browser), fallback to simulation.
        // If on Device (Native), return empty string to indicate failure.
        if (e.message && e.message.includes("not implemented")) {
             console.warn("[SIMULATION] Browser detected, returning mock data.");
             if (cmd === 'id') return "uid=0(root) gid=0(root)";
             if (cmd.includes('ro.telephony.default_network')) return "9,9"; 
             if (cmd.includes('gsm.sim.operator.alpha')) return "Jio 5G,Airtel";
             if (cmd.includes('gsm.sim.state')) return "READY,READY";
             return "OK";
        }
        return ""; // Real device failure -> Return empty to fail checks gracefully
    }
  }

  static async setAudioRouting(slot: 0 | 1, mode: 'COMMUNICATION' | 'IN_CALL'): Promise<void> {
    const simId = slot + 1;
    if (mode === 'IN_CALL') {
      console.log(`[AUDIO_HAL] Engaging High-Priority Bridge [SoC: ${this.currentSoC}, Slot: ${simId}]`);
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
    await this.executeRootCommand(`service call telephony ${slot === 0 ? 1 : 101}`); 
    return true;
  }

  static async dialGsmSilently(slot: 0 | 1, number: string): Promise<void> {
    await this.executeRootCommand(`service call telephony ${slot === 0 ? 2 : 102} s16 "${number}"`);
  }

  static async hangupGsmPrivileged(slot: 0 | 1): Promise<void> {
    await this.executeRootCommand(`service call telephony ${slot === 0 ? 5 : 105}`);
  }

  static setGsmDisconnectListener(callback: (slot: 0 | 1) => void) {
    this.onGsmDisconnectCallback = callback;
  }
}
