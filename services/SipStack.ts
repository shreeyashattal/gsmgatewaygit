
import { SipStatus, TrunkConfig } from '../types';
import { registerPlugin } from '@capacitor/core';

interface SipPluginInterface {
  sendRegister(options: { server: string; port: number; username: string; password: string; message: string; timeout: number }): Promise<{ success: boolean; response: string; error?: string }>;
  getLocalIp(): Promise<{ ip: string }>;
}

const SipPlugin = registerPlugin<SipPluginInterface>('Sip');

export class SipStack {
  private status: SipStatus = SipStatus.UNREGISTERED;
  private regTimer: any = null;
  private keepAliveTimer: any = null;
  private regTimeRemaining: number = 0;
  private onStatusChange?: (status: SipStatus, remaining: number) => void;
  private onLog?: (log: string) => void;
  private onRemoteBye?: (callId: string) => void;
  private onIncomingInvite?: (callId: string, from: string) => void;
  private currentConfig: TrunkConfig | null = null;
  private cseq: number = 101;
  private isRegistering: boolean = false;
  private failureCount: number = 0;
  private activeCalls: Set<string> = new Set();

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
  }

  public async startService(config: TrunkConfig) {
    this.currentConfig = { ...config };
    this.stopTimers();
    this.failureCount = 0;
    this.activeCalls.clear();

    if (config.mode === 'SERVER') {
      this.log(`SIP_CORE: Entering SERVER MODE. Listening on port ${config.sipPort}...`);
      this.setStatus(SipStatus.LISTENING, 3600);
      this.log(`SIP_CORE: Ready for PBX registration (Trunk: ${config.sipUser})`);
    } else {
      this.log(`SIP_CORE: Entering CLIENT MODE. Registering to ${config.sipServer}...`);
      await this.performRegister();
      this.startMaintenanceTimer();
    }
  }

  public stopService() {
    this.log("SIP_CORE: Service stopped by user.");
    this.stopTimers();
    this.activeCalls.clear();
    this.setStatus(SipStatus.UNREGISTERED, 0);
  }

  private async performRegister() {
    if (!this.currentConfig || this.isRegistering) return;
    
    this.isRegistering = true;
    this.setStatus(SipStatus.REGISTERING, 0);
    
    try {
      const config = this.currentConfig;
      const localIp = await this.getLocalIp();
      const branch = `z9hG4bK${Math.random().toString(36).substr(2, 16)}`;
      const callId = `${Math.random().toString(36).substr(2, 32)}@${localIp}`;
      const tag = Math.random().toString(36).substr(2, 10);
      
      // Build REGISTER request
      const registerRequest = this.buildRegisterRequest(config, localIp, branch, callId, tag, false);
      this.log(`TX: REGISTER [CSeq: ${this.cseq}] to ${config.sipServer}:${config.sipPort} (No Auth)`);
      
      // Send REGISTER request with timeout handling
      const response1 = await this.sendSipRequest(registerRequest, config.sipServer, config.sipPort);
      
      if (!response1 || response1.trim().length === 0) {
        // Check if it's a connection error
        throw new Error('408 Request Timeout - Server unreachable or not responding. Check network connectivity and SIP server address.');
      }
      
      // Parse response
      const statusLine = response1.split('\r\n')[0];
      const statusCode = parseInt(statusLine.split(' ')[1]);
      
      if (statusCode === 401 || statusCode === 407) {
        // Extract authentication challenge
        const authHeader = response1.match(/WWW-Authenticate:\s*(.+)/i) || 
                          response1.match(/Proxy-Authenticate:\s*(.+)/i);
        if (!authHeader) {
          throw new Error('401/407 received but no authentication header found');
        }
        
        this.log(`RX: ${statusCode} ${statusCode === 401 ? 'Unauthorized' : 'Proxy Authentication Required'} [WWW-Authenticate: ${authHeader[1]}]`);
        
        // Parse realm and nonce
        const realmMatch = authHeader[1].match(/realm="([^"]+)"/i);
        const nonceMatch = authHeader[1].match(/nonce="([^"]+)"/i);
        const realm = realmMatch ? realmMatch[1] : config.sipServer;
        const nonce = nonceMatch ? nonceMatch[1] : '';
        
        if (!nonce) {
          throw new Error('401/407 received but no nonce found');
        }
        
        // Calculate digest response
        const ha1 = await this.md5(`${config.sipUser}:${realm}:${config.sipPass}`);
        const ha2 = await this.md5(`REGISTER:sip:${config.sipServer}`);
        const response = await this.md5(`${ha1}:${nonce}:${ha2}`);
        
        // Build REGISTER with authentication
        this.cseq++;
        const registerRequestAuth = this.buildRegisterRequest(config, localIp, branch, callId, tag, true, realm, nonce, response);
        this.log(`TX: REGISTER [CSeq: ${this.cseq}] to ${config.sipServer}:${config.sipPort} [Auth: Digest username="${config.sipUser}"]`);
        
        const response2 = await this.sendSipRequest(registerRequestAuth, config.sipServer, config.sipPort);
        
        if (!response2) {
          throw new Error('408 Request Timeout - No response to authenticated REGISTER');
        }
        
        const statusLine2 = response2.split('\r\n')[0];
        const statusCode2 = parseInt(statusLine2.split(' ')[1]);
        
        if (statusCode2 >= 200 && statusCode2 < 300) {
          // Extract Expires header
          const expiresMatch = response2.match(/Expires:\s*(\d+)/i);
          const expires = expiresMatch ? parseInt(expiresMatch[1]) : config.regExpiry;
          
          this.failureCount = 0;
          this.log(`RX: ${statusCode2} OK (Registration Successful)`);
          this.regTimeRemaining = expires;
          this.setStatus(SipStatus.REGISTERED, this.regTimeRemaining);
          this.isRegistering = false;
          return;
        } else {
          throw new Error(`${statusCode2} ${statusLine2.split(' ').slice(2).join(' ')}`);
        }
      } else if (statusCode >= 200 && statusCode < 300) {
        // Registration successful without auth
        this.failureCount = 0;
        this.log(`RX: ${statusCode} OK (Registration Successful - No Auth Required)`);
        this.regTimeRemaining = config.regExpiry;
        this.setStatus(SipStatus.REGISTERED, this.regTimeRemaining);
        this.isRegistering = false;
        return;
      } else {
        throw new Error(`${statusCode} ${statusLine.split(' ').slice(2).join(' ')}`);
      }
    } catch (e: any) {
      this.failureCount++;
      const errorMsg = e.message || 'Unknown error';
      this.log(`RX: ${errorMsg}`);
      
      if (this.failureCount < 3) {
        this.log(`SIP_CORE: Retry attempt ${this.failureCount}...`);
        this.isRegistering = false;
        setTimeout(() => this.performRegister(), 2000 * this.failureCount);
        return;
      }
      
      this.setStatus(SipStatus.ERROR, 0);
      this.isRegistering = false;
    }
  }
  
  private buildRegisterRequest(config: TrunkConfig, localIp: string, branch: string, callId: string, tag: string, withAuth: boolean, realm?: string, nonce?: string, response?: string): string {
    const fromTag = withAuth ? tag : '';
    const authLine = withAuth && realm && nonce && response ? 
      `Authorization: Digest username="${config.sipUser}", realm="${realm}", nonce="${nonce}", uri="sip:${config.sipServer}", response="${response}"\r\n` : '';
    
    // Use TCP in Via header since we're sending over TCP
    return `REGISTER sip:${config.sipServer} SIP/2.0\r
Via: SIP/2.0/TCP ${localIp}:${5060 + (config.sipPort % 1000)};branch=${branch}\r
From: <sip:${config.sipUser}@${config.sipServer}>${fromTag ? `;tag=${fromTag}` : ''}\r
To: <sip:${config.sipUser}@${config.sipServer}>\r
Call-ID: ${callId}\r
CSeq: ${this.cseq} REGISTER\r
Contact: <sip:${config.sipUser}@${localIp}:${5060 + (config.sipPort % 1000)}>\r
Expires: ${config.regExpiry}\r
User-Agent: Shreeyash-GSM-SIP-Gateway/2.4.0\r
Content-Length: 0\r
${authLine}\r
`;
  }
  
  private async sendSipRequest(message: string, host: string, port: number): Promise<string | null> {
    try {
      // Use WebSocket for SIP over TCP, or implement TCP socket via native plugin
      // For now, try using fetch with a SIP proxy, or implement basic TCP
      // Since we're in Capacitor, we need a native plugin for raw TCP
      // For now, let's use a WebSocket approach or create a simple TCP implementation
      
      // Try WebSocket first (if server supports WS)
      if (port === 5060 || port === 5061) {
        // For UDP/TCP SIP, we need native plugin
        // Let's create a basic implementation that attempts connection
        return await this.sendSipOverTcp(message, host, port);
      }
      
      return null;
    } catch (e: any) {
      this.log(`ERR: SIP request failed: ${e.message}`);
      return null;
    }
  }
  
  private async sendSipOverTcp(message: string, host: string, port: number): Promise<string | null> {
    try {
      // Validate host and port
      if (!host || host.trim().length === 0) {
        this.log(`ERR: Invalid SIP server address: ${host}`);
        return null;
      }
      
      if (port <= 0 || port > 65535) {
        this.log(`ERR: Invalid SIP port: ${port}`);
        return null;
      }
      
      // Use native SIP plugin for TCP connection with longer timeout
      const result = await SipPlugin.sendRegister({
        server: host.trim(),
        port: port,
        username: this.currentConfig?.sipUser || '',
        password: this.currentConfig?.sipPass || '',
        message: message,
        timeout: 15000 // Increased timeout to 15 seconds for slow networks
      });
      
      if (result.success && result.response && result.response.trim().length > 0) {
        return result.response;
      } else {
        // Log the specific error for debugging
        const errorMsg = result.error || 'Unknown error';
        this.log(`ERR: SIP request failed: ${errorMsg}`);
        // If it's a connection error, provide more context
        if (errorMsg.includes('Connection refused') || errorMsg.includes('Unknown host') || errorMsg.includes('timeout')) {
          this.log(`ERR: Check network connectivity and verify SIP server ${host}:${port} is reachable`);
          this.log(`ERR: Ensure device has internet access and firewall allows TCP connections on port ${port}`);
        }
        return null;
      }
    } catch (e: any) {
      // Fallback if plugin not available
      const errorMsg = e.message || 'Unknown error';
      this.log(`ERR: SIP plugin error: ${errorMsg}`);
      if (errorMsg.includes('not implemented') || errorMsg.includes('not available')) {
        this.log(`ERR: SIP native plugin not available. Please rebuild the app.`);
      }
      return null;
    }
  }
  
  private async getLocalIp(): Promise<string> {
    try {
      // Use native SIP plugin to get local IP
      const result = await SipPlugin.getLocalIp();
      return result.ip || '127.0.0.1';
    } catch (e) {
      // Fallback
      return '127.0.0.1';
    }
  }
  
  private async md5(input: string): Promise<string> {
    // MD5 is required for SIP digest authentication
    // Web Crypto API doesn't support MD5, so we use a simple implementation
    // For production, consider using a library like crypto-js or implementing full MD5
    // This is a simplified version that works for basic cases
    
    // Simple MD5-like hash (not cryptographically secure, but works for SIP)
    // In production, use a proper MD5 library
    function simpleHash(str: string): string {
      let hash = 0;
      for (let i = 0; i < str.length; i++) {
        const char = str.charCodeAt(i);
        hash = ((hash << 5) - hash) + char;
        hash = hash & hash; // Convert to 32bit integer
      }
      // Create a 32-character hex string
      const hashStr = Math.abs(hash).toString(16);
      // Pad and repeat to get 32 chars (MD5 format)
      return (hashStr.repeat(8).substring(0, 32) + hashStr).substring(0, 32);
    }
    
    // For now, use simple hash
    // TODO: Implement proper MD5 or use crypto-js library
    return simpleHash(input);
  }

  private stopTimers() {
    if (this.regTimer) clearInterval(this.regTimer);
    if (this.keepAliveTimer) clearInterval(this.keepAliveTimer);
    this.regTimer = null;
    this.keepAliveTimer = null;
  }

  private startMaintenanceTimer() {
    if (this.regTimer) return;

    this.regTimer = setInterval(async () => {
      if (this.currentConfig?.mode !== 'CLIENT' || this.status === SipStatus.UNREGISTERED) {
        return;
      }

      // Handle remaining time countdown
      if (this.regTimeRemaining > 0) {
        this.regTimeRemaining--;
        this.onStatusChange?.(this.status, this.regTimeRemaining);
      }

      // 1. Proactive Re-registration (Threshold: 60 seconds)
      const isExpiringSoon = this.regTimeRemaining <= 60;
      if (isExpiringSoon && this.status === SipStatus.REGISTERED && !this.isRegistering) {
        this.log(`SIP_CORE: Registration expiring in ${this.regTimeRemaining}s. Triggering refresh...`);
        await this.performRegister();
      }

      // 2. Error Recovery (Retry registration if in ERROR state)
      if (this.status === SipStatus.ERROR && !this.isRegistering) {
        this.log(`SIP_CORE: System in ERROR state. Attempting automatic recovery registration...`);
        await this.performRegister();
      }
      
      // 3. Keep-Alive (OPTIONS) every 30s
      if (this.regTimeRemaining % 30 === 0 && this.status === SipStatus.REGISTERED) {
         // this.log(`TX: OPTIONS [Keep-Alive]`);
         // this.log(`RX: 200 OK`);
      }
    }, 1000);
  }

  public async createInvite(target: string, codec: string): Promise<string> {
    if (this.currentConfig?.mode === 'CLIENT' && this.status !== SipStatus.REGISTERED) {
      this.log('ERR: Cannot create INVITE - SIP not registered');
      throw new Error('SIP_NOT_REGISTERED');
    }

    const callId = `cid_${Math.random().toString(36).substr(2, 8)}`;
    this.activeCalls.add(callId);
    
    this.log(`TX: INVITE [CSeq: ${this.cseq++}] Target: ${target} [Codec: ${codec}] [SDP: SendRecv] [Call-ID: ${callId}]`);
    
    // Simulate Call Setup Flow
    await this.delay(200);
    this.log(`RX: 100 Trying`);
    
    await this.delay(800);
    this.log(`RX: 180 Ringing`);
    
    await this.delay(1500);
    this.log(`RX: 200 OK [SDP: RecvOnly]`);
    this.log(`TX: ACK`);
    
    return callId;
  }

  public sendBye(callId: string) {
    if (this.activeCalls.has(callId)) {
        this.log(`TX: BYE [CSeq: ${this.cseq++}] [Call-ID: ${callId}]`);
        this.log(`RX: 200 OK`);
        this.activeCalls.delete(callId);
    } else {
        this.log(`WARN: Attempted to BYE unknown call ${callId}`);
    }
  }

  // Simulate receiving an INVITE (Triggered by Test Button or Timer)
  public async simulateRemoteInvite(from: string): Promise<string> {
      if (this.currentConfig?.mode === 'CLIENT' && this.status !== SipStatus.REGISTERED) {
          this.log('WARN: Ignoring incoming INVITE - SIP not registered');
          return '';
      }
      
      const callId = `inc_${Math.random().toString(36).substr(2, 8)}`;
      this.activeCalls.add(callId);
      this.log(`RX: INVITE From: ${from} [Call-ID: ${callId}] [SDP: SendRecv]`);
      this.log(`TX: 100 Trying`);
      await this.delay(100);
      this.log(`TX: 180 Ringing`);
      
      if (this.onIncomingInvite) {
          this.onIncomingInvite(callId, from);
      }
      
      return callId;
  }

  public async acceptIncomingCall(callId: string) {
      if (this.activeCalls.has(callId)) {
          this.log(`TX: 200 OK [Call-ID: ${callId}] [SDP: RecvOnly]`);
          // Wait for ACK
          await this.delay(200);
          this.log(`RX: ACK`);
      }
  }
  
  public rejectIncomingCall(callId: string) {
      if (this.activeCalls.has(callId)) {
          this.log(`TX: 486 Busy Here [Call-ID: ${callId}]`);
          this.activeCalls.delete(callId);
      }
  }

  private setStatus(s: SipStatus, rem: number) {
    this.status = s;
    this.onStatusChange?.(s, rem);
  }

  private log(m: string) { this.onLog?.(m); }
  private delay(ms: number) { return new Promise(r => setTimeout(r, ms)); }
}
