import { Capacitor } from '@capacitor/core';

export interface AsteriskAPIRequest {
  action: string;
  slot?: number;
  phoneNumber?: string;
  channelId?: string;
  callId?: string;
}

export interface AsteriskAPIResponse {
  success: boolean;
  message?: string;
  callId?: string;
}

/**
 * AsteriskAPI: HTTP API server that Asterisk can call to communicate with the GSM bridge
 * Runs on a local port that Asterisk can reach
 */
export class AsteriskAPI {
  private static instance: AsteriskAPI;
  private server: any = null;
  private port: number = 8080;
  private onGsmCallRequest?: (slot: number, phoneNumber: string, channelId: string) => Promise<boolean>;
  private onCallEvent?: (event: string, channelId: string, data?: any) => void;

  constructor() {
    if (Capacitor.isNativePlatform()) {
      this.startServer();
    }
  }

  static getInstance(): AsteriskAPI {
    if (!AsteriskAPI.instance) {
      AsteriskAPI.instance = new AsteriskAPI();
    }
    return AsteriskAPI.instance;
  }

  setGsmCallRequestHandler(handler: (slot: number, phoneNumber: string, channelId: string) => Promise<boolean>) {
    this.onGsmCallRequest = handler;
  }

  setCallEventHandler(handler: (event: string, channelId: string, data?: any) => void) {
    this.onCallEvent = handler;
  }

  private async startServer() {
    // In a real Capacitor plugin, this would start an HTTP server
    // For now, we'll simulate the API endpoints
    console.log(`[ASTERISK_API] Starting HTTP server on port ${this.port}`);

    // TODO: Implement actual HTTP server using a Capacitor plugin
    // For now, this is a placeholder for the API structure
  }

  // Simulate API endpoints (would be called by Asterisk via HTTP requests)
  async handleRequest(endpoint: string, data: any): Promise<AsteriskAPIResponse> {
    console.log(`[ASTERISK_API] Received ${endpoint}:`, data);

    switch (endpoint) {
      case '/make-gsm-call':
        return await this.handleMakeGsmCall(data);

      case '/call-event':
        return this.handleCallEvent(data);

      case '/bridge-status':
        return this.handleBridgeStatus(data);

      default:
        return { success: false, message: 'Unknown endpoint' };
    }
  }

  private async handleMakeGsmCall(data: { slot: number; phoneNumber: string; channelId: string }): Promise<AsteriskAPIResponse> {
    if (!this.onGsmCallRequest) {
      return { success: false, message: 'No GSM call handler configured' };
    }

    try {
      const success = await this.onGsmCallRequest(data.slot, data.phoneNumber, data.channelId);
      return {
        success,
        message: success ? 'GSM call initiated' : 'GSM call failed',
        callId: data.channelId
      };
    } catch (e: any) {
      return { success: false, message: `GSM call error: ${e.message}` };
    }
  }

  private handleCallEvent(data: { event: string; channelId: string; data?: any }): AsteriskAPIResponse {
    if (this.onCallEvent) {
      this.onCallEvent(data.event, data.channelId, data.data);
    }

    return { success: true, message: `Event ${data.event} processed` };
  }

  private handleBridgeStatus(data: any): AsteriskAPIResponse {
    // Return current bridge status
    return {
      success: true,
      message: 'Bridge status query',
      // TODO: Return actual status
    };
  }

  // Methods that Asterisk dialplan can call via curl or similar
  static async makeGsmCall(slot: number, phoneNumber: string, channelId: string): Promise<boolean> {
    // This would be called from Asterisk dialplan like:
    // curl "http://localhost:8080/make-gsm-call" -d '{"slot":0,"phoneNumber":"1234567890","channelId":"SIP/trunk-123"}'

    const api = AsteriskAPI.getInstance();
    const response = await api.handleRequest('/make-gsm-call', {
      slot,
      phoneNumber,
      channelId
    });

    return response.success;
  }

  static async reportCallEvent(event: string, channelId: string, data?: any): Promise<void> {
    // This would be called from Asterisk dialplan for events like:
    // - Call answered
    // - Call hangup
    // - Bridge established

    const api = AsteriskAPI.getInstance();
    await api.handleRequest('/call-event', {
      event,
      channelId,
      data
    });
  }
}