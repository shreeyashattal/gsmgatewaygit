import { Capacitor } from '@capacitor/core';

export interface SIPRequest {
  action: string;
  slot?: number;
  phoneNumber?: string;
  callId?: string;
}

export interface SIPResponse {
  success: boolean;
  message?: string;
  callId?: string;
}

/**
 * SIPCallHandler: Handles SIP call coordination between the TypeScript layer
 * and the native SIP stack. In trunk mode, the PBX IP is automatically
 * derived from the SIP REGISTER/INVITE source address - no hardcoding needed.
 */
export class SIPCallHandler {
  private static instance: SIPCallHandler;
  private onGsmCallRequest?: (slot: number, phoneNumber: string, callId: string) => Promise<boolean>;
  private onCallEvent?: (event: string, callId: string, data?: any) => void;

  constructor() {
    if (Capacitor.isNativePlatform()) {
      this.initialize();
    }
  }

  static getInstance(): SIPCallHandler {
    if (!SIPCallHandler.instance) {
      SIPCallHandler.instance = new SIPCallHandler();
    }
    return SIPCallHandler.instance;
  }

  setGsmCallRequestHandler(handler: (slot: number, phoneNumber: string, callId: string) => Promise<boolean>) {
    this.onGsmCallRequest = handler;
  }

  setCallEventHandler(handler: (event: string, callId: string, data?: any) => void) {
    this.onCallEvent = handler;
  }

  private async initialize() {
    console.log('[SIP_HANDLER] Initializing SIP call handler');
    // The native SIP stack handles all SIP messaging
    // PBX address is derived from incoming REGISTER/INVITE source in trunk mode
  }

  // Handle incoming SIP INVITE from PBX
  async handleIncomingInvite(data: { slot: number; phoneNumber: string; callId: string; fromAddr: string }): Promise<SIPResponse> {
    console.log(`[SIP_HANDLER] Incoming INVITE from ${data.fromAddr}: ${data.phoneNumber}`);

    if (!this.onGsmCallRequest) {
      return { success: false, message: 'No GSM call handler configured' };
    }

    try {
      const success = await this.onGsmCallRequest(data.slot, data.phoneNumber, data.callId);
      return {
        success,
        message: success ? 'GSM call initiated' : 'GSM call failed',
        callId: data.callId
      };
    } catch (e: any) {
      return { success: false, message: `GSM call error: ${e.message}` };
    }
  }

  // Handle SIP events (BYE, ACK, etc.)
  handleSipEvent(data: { event: string; callId: string; data?: any }): SIPResponse {
    if (this.onCallEvent) {
      this.onCallEvent(data.event, data.callId, data.data);
    }

    return { success: true, message: `Event ${data.event} processed` };
  }

  // Notify of GSM call events to be relayed to PBX
  static async notifyGsmEvent(event: string, callId: string, data?: any): Promise<void> {
    const handler = SIPCallHandler.getInstance();
    handler.handleSipEvent({ event, callId, data });
  }
}
