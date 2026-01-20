import React from 'react';
import { GatewayConfig, ChannelConfig } from '../types';
import { daemon } from '../services/GatewayDaemon';

interface SettingsProps {
  config: GatewayConfig;
  setConfig: React.Dispatch<React.SetStateAction<GatewayConfig>>;
}

const Settings: React.FC<SettingsProps> = ({ config, setConfig }) => {
  const handleChannelChange = (slot: 0 | 1, field: keyof ChannelConfig, value: any) => {
    const nextChannels = [...config.channels] as [ChannelConfig, ChannelConfig];
    nextChannels[slot] = { ...nextChannels[slot], [field]: value };
    setConfig({ ...config, channels: nextChannels });
  };

  const handleApply = () => {
    daemon.updateConfig(config);
  };

  return (
    <div className="space-y-8 pb-32">
      {/* Info Banner */}
      <div className="bg-green-500/5 border border-green-500/20 rounded-3xl p-6">
        <h2 className="text-xs font-black text-green-400 uppercase tracking-widest mb-2">Asterisk AMI Bridge</h2>
        <p className="text-[10px] text-gray-500 leading-relaxed">
          This gateway bridges GSM calls to <strong>Asterisk PBX</strong> via the Manager Interface (AMI) on <code className="text-blue-400">127.0.0.1:5038</code>.
          AMI credentials are pre-configured. Configure the dialplan context and extension for each SIM channel below.
        </p>
      </div>

      {/* AMI Connection Info */}
      <div className="bg-[#0d0d0d] border border-white/5 rounded-3xl p-5">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-[9px] font-black text-gray-500 uppercase tracking-widest">AMI Connection</p>
            <p className="text-sm font-mono text-blue-400 mt-1">127.0.0.1:5038</p>
          </div>
          <div className="text-right">
            <p className="text-[9px] font-black text-gray-500 uppercase tracking-widest">Status</p>
            <p className="text-sm font-black text-green-400 mt-1">
              {daemon.state.bridgeStatus === 'CONNECTED' ? 'CONNECTED' :
               daemon.state.bridgeStatus === 'CONNECTING' ? 'CONNECTING...' :
               daemon.state.bridgeStatus === 'ERROR' ? 'ERROR' : 'DISCONNECTED'}
            </p>
          </div>
        </div>
      </div>

      {/* Channel Configuration */}
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
        const channel = config.channels[slot];

        return (
          <div key={slot} className="bg-[#0d0d0d] border border-white/5 rounded-3xl overflow-hidden shadow-2xl">
            <div className="p-5 bg-white/5 border-b border-white/5 flex items-center justify-between">
              <h3 className="text-[10px] font-black text-white tracking-[0.2em] uppercase">
                SIM {slot + 1} Channel Configuration
              </h3>
              <label className="flex items-center gap-2 cursor-pointer">
                <span className="text-[9px] font-bold text-gray-500 uppercase">
                  {channel.enabled ? 'Enabled' : 'Disabled'}
                </span>
                <div
                  onClick={() => handleChannelChange(slot as 0 | 1, 'enabled', !channel.enabled)}
                  className={`w-10 h-5 rounded-full transition-colors ${channel.enabled ? 'bg-green-500' : 'bg-gray-700'} relative`}
                >
                  <div className={`absolute top-0.5 w-4 h-4 rounded-full bg-white transition-all ${channel.enabled ? 'left-5' : 'left-0.5'}`} />
                </div>
              </label>
            </div>

            <div className="p-6 space-y-6">
              <div className="grid grid-cols-2 gap-6">
                <InputGroup label="Dialplan Context">
                  <input
                    type="text"
                    value={channel.asteriskContext}
                    onChange={(e) => handleChannelChange(slot as 0 | 1, 'asteriskContext', e.target.value)}
                    placeholder="from-gsm1"
                    className="w-full bg-black border border-white/10 rounded-2xl p-3 text-xs font-mono text-blue-400 outline-none focus:border-blue-500"
                  />
                </InputGroup>
                <InputGroup label="Default Extension">
                  <input
                    type="text"
                    value={channel.defaultExtension}
                    onChange={(e) => handleChannelChange(slot as 0 | 1, 'defaultExtension', e.target.value)}
                    placeholder="s"
                    className="w-full bg-black border border-white/10 rounded-2xl p-3 text-xs font-mono text-blue-400 outline-none focus:border-blue-500"
                  />
                </InputGroup>
              </div>

              <div className="grid grid-cols-2 gap-6">
                <InputGroup label="RTP Port">
                  <input
                    type="number"
                    value={channel.rtpPort}
                    onChange={(e) => handleChannelChange(slot as 0 | 1, 'rtpPort', parseInt(e.target.value))}
                    className="w-full bg-black border border-white/10 rounded-2xl p-3 text-xs font-mono text-gray-400 outline-none focus:border-blue-500"
                    disabled
                  />
                  <p className="text-[8px] text-gray-600 mt-1 ml-1">Fixed per channel</p>
                </InputGroup>
                <InputGroup label="Audio Codec">
                  <select
                    value={channel.codec}
                    onChange={(e) => handleChannelChange(slot as 0 | 1, 'codec', e.target.value as any)}
                    className="w-full bg-black border border-white/10 rounded-2xl p-3 text-xs font-mono text-white outline-none focus:border-blue-500"
                  >
                    <option value="PCMU">G.711u (ulaw)</option>
                    <option value="PCMA">G.711a (alaw)</option>
                    <option value="G722">G.722 (Wideband)</option>
                    <option value="OPUS">Opus</option>
                  </select>
                </InputGroup>
              </div>

              {/* Channel Info */}
              <div className="bg-black/50 rounded-2xl p-4 border border-white/5">
                <p className="text-[9px] text-gray-500 font-bold uppercase tracking-wider mb-2">Channel Info</p>
                <div className="grid grid-cols-2 gap-4 text-[10px]">
                  <div>
                    <span className="text-gray-600">Incoming GSM → </span>
                    <span className="text-green-400 font-mono">{channel.asteriskContext}:{channel.defaultExtension}</span>
                  </div>
                  <div>
                    <span className="text-gray-600">RTP Stream → </span>
                    <span className="text-blue-400 font-mono">127.0.0.1:{channel.rtpPort}</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        );
      })}

      {/* Global Settings */}
      <div className="bg-[#0d0d0d] border border-white/5 rounded-3xl overflow-hidden">
        <div className="p-5 bg-white/5 border-b border-white/5">
          <h3 className="text-[10px] font-black text-white tracking-[0.2em] uppercase">Global Settings</h3>
        </div>
        <div className="p-6 space-y-4">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-xs font-bold text-white">Auto-Answer GSM Calls</p>
              <p className="text-[9px] text-gray-500 mt-1">Automatically answer incoming GSM calls and bridge to Asterisk</p>
            </div>
            <div
              onClick={() => setConfig({ ...config, autoAnswer: !config.autoAnswer })}
              className={`w-10 h-5 rounded-full transition-colors cursor-pointer ${config.autoAnswer ? 'bg-green-500' : 'bg-gray-700'} relative`}
            >
              <div className={`absolute top-0.5 w-4 h-4 rounded-full bg-white transition-all ${config.autoAnswer ? 'left-5' : 'left-0.5'}`} />
            </div>
          </div>

          <div className="flex items-center justify-between pt-4 border-t border-white/5">
            <div>
              <p className="text-xs font-bold text-white">Speakerphone Mode</p>
              <p className="text-[9px] text-gray-500 mt-1">Route audio through device speaker (for debugging)</p>
            </div>
            <div
              onClick={() => setConfig({ ...config, speakerphoneOn: !config.speakerphoneOn })}
              className={`w-10 h-5 rounded-full transition-colors cursor-pointer ${config.speakerphoneOn ? 'bg-green-500' : 'bg-gray-700'} relative`}
            >
              <div className={`absolute top-0.5 w-4 h-4 rounded-full bg-white transition-all ${config.speakerphoneOn ? 'left-5' : 'left-0.5'}`} />
            </div>
          </div>
        </div>
      </div>

      <button
        onClick={handleApply}
        className="w-full py-5 bg-white text-black rounded-3xl font-black text-xs uppercase tracking-[0.3em] shadow-2xl active:scale-95 transition-all hover:bg-gray-100"
      >
        Apply Configuration
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
