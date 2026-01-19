import React from 'react';
import { GatewayConfig, TrunkConfig } from '../types';
import { daemon } from '../services/GatewayDaemon';

interface SettingsProps {
  config: GatewayConfig;
  setConfig: React.Dispatch<React.SetStateAction<GatewayConfig>>;
}

const Settings: React.FC<SettingsProps> = ({ config, setConfig }) => {
  const handleTrunkChange = (slot: 0 | 1, field: keyof TrunkConfig, value: any) => {
    const nextTrunks = [...config.trunks] as [TrunkConfig, TrunkConfig];
    nextTrunks[slot] = { ...nextTrunks[slot], [field]: value };
    setConfig({ ...config, trunks: nextTrunks });
  };

  const handleApply = () => {
    daemon.updateConfig(config);
  };

  return (
    <div className="space-y-8 pb-32">
      <div className="bg-green-500/5 border border-green-500/20 rounded-3xl p-6">
        <h2 className="text-xs font-black text-green-400 uppercase tracking-widest mb-2">GSM ↔ Asterisk Bridge</h2>
        <p className="text-[10px] text-gray-500 leading-relaxed italic">
          This bridge connects GSM calls to <strong>Asterisk PBX</strong> running locally on your Android device. Configure Asterisk dialplan to call the bridge API for outbound GSM calls. Ensure Asterisk is properly configured with appropriate codecs and trunks.
        </p>
      </div>

      {[0, 1].map((slot) => {
        // Hide channel 2 if SIM2 is not populated
        if (slot === 1) {
          const sim2 = daemon.state.metrics.sims[1];
          const isSim2Detected = sim2 && 
                                 sim2.status !== 'NOT_DETECTED' && 
                                 sim2.carrier && 
                                 sim2.carrier !== 'Empty' && 
                                 sim2.carrier !== 'No SIM' &&
                                 sim2.carrier !== 'Unknown';
          if (!isSim2Detected || daemon.state.metrics.slotCount < 2) {
            return null;
          }
        }
        const trunk = config.trunks[slot];

        return (
          <div key={slot} className="bg-[#0d0d0d] border border-white/5 rounded-3xl overflow-hidden shadow-2xl">
            <div className="p-5 bg-white/5 border-b border-white/5">
              <h3 className="text-[10px] font-black text-white tracking-[0.2em] uppercase">SIP Configuration: Slot {slot + 1}</h3>
            </div>
            
            <div className="p-6 space-y-6">
              
              <div className="grid grid-cols-3 gap-6">
                <div className="col-span-2">
                  <InputGroup label="Default SIP Destination">
                    <input 
                      type="text" 
                      value={trunk.sipServer}
                      onChange={(e) => handleTrunkChange(slot as 0 | 1, 'sipServer', e.target.value)}
                      className="w-full bg-black border border-white/10 rounded-2xl p-3 text-xs font-mono text-blue-400 outline-none focus:border-blue-500"
                    />
                  </InputGroup>
                </div>
                <InputGroup label="Bridge API Port">
                  <input 
                    type="number" 
                    value={trunk.sipPort}
                    onChange={(e) => handleTrunkChange(slot as 0 | 1, 'sipPort', parseInt(e.target.value))}
                    className="w-full bg-black border border-white/10 rounded-2xl p-3 text-xs font-mono text-blue-400 outline-none focus:border-blue-500"
                  />
                </InputGroup>
              </div>
              
              <div className="grid grid-cols-2 gap-6">
                <InputGroup label="Asterisk Context">
                  <input 
                    type="text" 
                    value={trunk.sipUser}
                    onChange={(e) => handleTrunkChange(slot as 0 | 1, 'sipUser', e.target.value)}
                    className="w-full bg-black border border-white/10 rounded-2xl p-3 text-xs font-mono outline-none focus:border-blue-500"
                  />
                </InputGrop>
                <InputGroup label="Auth Password">
                  <input 
                    type="password" 
                    value={trunk.sipPass}
                    onChange={(e) => handleTrunkChange(slot as 0 | 1, 'sipPass', e.target.value)}
                    className="w-full bg-black border border-white/10 rounded-2xl p-3 text-xs font-mono outline-none focus:border-blue-500"
                  />
                </InputGroup>
              </div>

              <div className="flex items-center justify-between pt-4 border-t border-white/5">
                <span className="text-[9px] text-gray-500 font-bold uppercase">Audio Codec Preference</span>
                <select 
                   value={trunk.codec}
                   onChange={(e) => handleTrunkChange(slot as 0 | 1, 'codec', e.target.value as any)}
                   className="bg-black border border-white/10 rounded-lg text-[9px] font-black p-2 outline-none text-white"
                >
                  <option value="PCMU">G.711u (ulaw)</option>
                  <option value="PCMA">G.711a (alaw)</option>
                  <option value="G722">G.722 (Wideband HD)</option>
                  <option value="OPUS">Opus (Multi-Rate)</option>
                </select>
              </div>
            </div>
          </div>
        );
      })}

      <button 
        onClick={handleApply}
        className="w-full py-5 bg-white text-black rounded-3xl font-black text-xs uppercase tracking-[0.3em] shadow-2xl active:scale-95 transition-all hover:bg-gray-100"
      >
        Sync System Registry
      </button>
    </div>
  );
};

const InputGroup: React.FC<{ label: string; children: React.ReactNode }> = ({ label, children }) => (
  <div className="space-y-2">
    <label className="text-[9px] text-gray-600 font-black uppercase tracking-widest ml-1">{label}</label>
    {children}
  </div>
);

export default Settings;