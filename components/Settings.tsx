import React, { useState } from 'react';
import { GatewayConfig, ChannelConfig, SIPRegistrationState } from '../types';
import { daemon } from '../services/GatewayDaemon';
import { StatusBadge } from './shared';
import { ICONS } from '../constants';

interface SettingsProps {
  config: GatewayConfig;
  setConfig: React.Dispatch<React.SetStateAction<GatewayConfig>>;
}

/**
 * SIP Trunk Configuration Settings
 * Configure per-SIM trunk endpoints, credentials, and audio settings
 */
const Settings: React.FC<SettingsProps> = ({ config, setConfig }) => {
  const [activeSimTab, setActiveSimTab] = useState<0 | 1>(0);
  const [saveStatus, setSaveStatus] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle');
  
  const { metrics } = daemon.state;

  const handleChannelChange = (slot: 0 | 1, field: keyof ChannelConfig, value: any) => {
    const nextChannels = [...config.channels] as [ChannelConfig, ChannelConfig];
    nextChannels[slot] = { ...nextChannels[slot], [field]: value };
    setConfig({ ...config, channels: nextChannels });
  };

  const handleSave = async () => {
    setSaveStatus('saving');
    try {
      await daemon.updateConfig(config);
      setSaveStatus('saved');
      setTimeout(() => setSaveStatus('idle'), 2000);
    } catch (err) {
      setSaveStatus('error');
      setTimeout(() => setSaveStatus('idle'), 3000);
    }
  };

  const channel = config.channels[activeSimTab];
  const simMetrics = metrics.sims[activeSimTab];

  return (
    <div className="space-y-6 pb-32">
      {/* Header */}
      <div className="flex justify-between items-start">
        <div>
          <h1 className="text-2xl font-black text-white tracking-tight">Trunk Configuration</h1>
          <p className="text-xs text-gray-400 mt-1">Configure SIP endpoints and credentials for each SIM</p>
        </div>
        <button
          onClick={handleSave}
          disabled={saveStatus === 'saving'}
          className={`px-4 py-2 rounded-xl text-xs font-bold uppercase tracking-widest transition-all ${
            saveStatus === 'saved' ? 'bg-green-500 text-white' :
            saveStatus === 'error' ? 'bg-red-500 text-white' :
            saveStatus === 'saving' ? 'bg-amber-500 text-white opacity-50 cursor-not-allowed' :
            'bg-blue-600 text-white hover:bg-blue-500'
          }`}
        >
          {saveStatus === 'saving' && '⏳ Saving...'}
          {saveStatus === 'saved' && '✓ Saved'}
          {saveStatus === 'error' && '✕ Error'}
          {saveStatus === 'idle' && 'Save Changes'}
        </button>
      </div>

      {/* SIM Selection Tabs */}
      <div className="flex gap-3 border-b border-white/10">
        {[0, 1].map((slot) => {
          const simMetrics = metrics.sims[slot];
          const isDetected = simMetrics.carrier && simMetrics.carrier !== 'Empty';

          if (slot === 1 && (!isDetected || metrics.slotCount < 2)) {
            return null;
          }

          return (
            <button
              key={slot}
              onClick={() => setActiveSimTab(slot as 0 | 1)}
              className={`px-4 py-3 border-b-2 transition-all text-[10px] font-bold uppercase tracking-widest ${
                activeSimTab === slot
                  ? 'border-blue-500 text-blue-400'
                  : 'border-transparent text-gray-400 hover:text-gray-300'
              }`}
            >
              SIM {slot + 1}: {simMetrics.carrier || 'Not Detected'}
            </button>
          );
        })}
      </div>

      {/* Registration Status Banner */}
      <div className="bg-[#080808] border border-white/10 rounded-2xl p-4 space-y-3">
        <div className="flex items-center justify-between">
          <h3 className="text-xs font-bold uppercase tracking-widest text-gray-300 flex items-center gap-2">
            <ICONS.Tower className="w-4 h-4 text-blue-400" />
            SIP Registration Status
          </h3>
          <StatusBadge 
            state={simMetrics.sipRegistrationState || SIPRegistrationState.NOT_REGISTERED}
            showLabel={true}
            size="md"
          />
        </div>
        <div className="grid grid-cols-2 gap-4 text-[9px]">
          <div className="bg-black/30 rounded-lg p-2">
            <p className="text-gray-500 font-bold uppercase tracking-widest mb-1">Last Registered</p>
            <p className="font-mono text-gray-300">
              {simMetrics.lastRegisteredTime 
                ? new Date(simMetrics.lastRegisteredTime).toLocaleTimeString()
                : 'Never'
              }
            </p>
          </div>
          <div className="bg-black/30 rounded-lg p-2">
            <p className="text-gray-500 font-bold uppercase tracking-widest mb-1">Next Register</p>
            <p className="font-mono text-gray-300">
              {simMetrics.nextRegisterTime
                ? new Date(simMetrics.nextRegisterTime).toLocaleTimeString()
                : 'Pending'
              }
            </p>
          </div>
        </div>
      </div>

      {/* PBX Connection Settings */}
      <div className="bg-[#080808] border border-white/10 rounded-2xl p-6 space-y-5">
        <h3 className="text-xs font-bold uppercase tracking-widest text-gray-300 flex items-center gap-2">
          <ICONS.Tower className="w-4 h-4 text-green-400" />
          PBX Connection
        </h3>

        <div className="grid grid-cols-2 gap-4">
          <InputGroup label="PBX Host/IP">
            <input
              type="text"
              value={channel.pbxHost || ''}
              onChange={(e) => handleChannelChange(activeSimTab, 'pbxHost', e.target.value)}
              placeholder="192.168.1.50"
              className="w-full bg-black/50 border border-white/10 rounded-lg p-2.5 text-xs font-mono text-blue-400 outline-none focus:border-blue-500"
            />
            <p className="text-[8px] text-gray-500 mt-1">IP address or hostname of your PBX</p>
          </InputGroup>

          <InputGroup label="PBX SIP Port">
            <input
              type="number"
              value={channel.pbxPort || 5060}
              onChange={(e) => handleChannelChange(activeSimTab, 'pbxPort', parseInt(e.target.value))}
              placeholder="5060"
              className="w-full bg-black/50 border border-white/10 rounded-lg p-2.5 text-xs font-mono text-gray-400 outline-none focus:border-blue-500"
            />
            <p className="text-[8px] text-gray-500 mt-1">Default: 5060 (standard SIP)</p>
          </InputGroup>
        </div>

        <div className="grid grid-cols-2 gap-4">
          <InputGroup label="Trunk Username">
            <input
              type="text"
              value={channel.sipUsername || ''}
              onChange={(e) => handleChannelChange(activeSimTab, 'sipUsername', e.target.value)}
              placeholder={`gsm_sim${activeSimTab + 1}`}
              className="w-full bg-black/50 border border-white/10 rounded-lg p-2.5 text-xs font-mono text-purple-400 outline-none focus:border-purple-500"
            />
            <p className="text-[8px] text-gray-500 mt-1">SIP trunk username for authentication</p>
          </InputGroup>

          <InputGroup label="Trunk Password">
            <input
              type="password"
              value={channel.sipPassword || ''}
              onChange={(e) => handleChannelChange(activeSimTab, 'sipPassword', e.target.value)}
              placeholder="••••••••"
              className="w-full bg-black/50 border border-white/10 rounded-lg p-2.5 text-xs font-mono text-purple-400 outline-none focus:border-purple-500"
            />
            <p className="text-[8px] text-gray-500 mt-1">Keep secure, used for authentication</p>
          </InputGroup>
        </div>

        <div className="grid grid-cols-2 gap-4">
          <InputGroup label="Local SIP Port">
            <input
              type="number"
              value={channel.localSipPort || (5061 + activeSimTab)}
              onChange={(e) => handleChannelChange(activeSimTab, 'localSipPort', parseInt(e.target.value))}
              placeholder={`${5061 + activeSimTab}`}
              className="w-full bg-black/50 border border-white/10 rounded-lg p-2.5 text-xs font-mono text-gray-400 outline-none focus:border-blue-500"
            />
            <p className="text-[8px] text-gray-500 mt-1">Port this gateway listens on</p>
          </InputGroup>

          <InputGroup label="Enable TLS">
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="checkbox"
                checked={channel.enableTLS || false}
                onChange={(e) => handleChannelChange(activeSimTab, 'enableTLS', e.target.checked)}
                className="w-4 h-4 rounded cursor-pointer accent-blue-500"
              />
              <span className="text-xs text-gray-400">Secure SIP connection</span>
            </label>
            <p className="text-[8px] text-gray-500 mt-2">Use TLS for encrypted trunk connection</p>
          </InputGroup>
        </div>
      </div>

      {/* Audio Settings */}
      <div className="bg-[#080808] border border-white/10 rounded-2xl p-6 space-y-4">
        <h3 className="text-xs font-bold uppercase tracking-widest text-gray-300 flex items-center gap-2">
          <ICONS.Signal className="w-4 h-4 text-amber-400" />
          Audio Settings
        </h3>

        <div className="grid grid-cols-2 gap-4">
          <InputGroup label="Codec">
            <select
              value={channel.codec || 'PCMU'}
              onChange={(e) => handleChannelChange(activeSimTab, 'codec', e.target.value)}
              className="w-full bg-black/50 border border-white/10 rounded-lg p-2.5 text-xs font-mono text-gray-400 outline-none focus:border-blue-500"
            >
              <option value="PCMU">G.711 µ-law (PCMU) - Standard</option>
              <option value="PCMA">G.711 A-law (PCMA)</option>
              <option value="OPUS">Opus - Modern</option>
              <option value="G722">G.722 - Wideband</option>
            </select>
            <p className="text-[8px] text-gray-500 mt-1">Audio codec for RTP streams</p>
          </InputGroup>

          <InputGroup label="RTP Port">
            <input
              type="number"
              value={channel.rtpPort || (10000 + activeSimTab * 2)}
              onChange={(e) => handleChannelChange(activeSimTab, 'rtpPort', parseInt(e.target.value))}
              placeholder={`${10000 + activeSimTab * 2}`}
              className="w-full bg-black/50 border border-white/10 rounded-lg p-2.5 text-xs font-mono text-gray-400 outline-none focus:border-blue-500"
            />
            <p className="text-[8px] text-gray-500 mt-1">Port for RTP audio stream</p>
          </InputGroup>
        </div>
      </div>

      {/* Registration Settings */}
      <div className="bg-[#080808] border border-white/10 rounded-2xl p-6 space-y-4">
        <h3 className="text-xs font-bold uppercase tracking-widest text-gray-300 flex items-center gap-2">
          <ICONS.Clock className="w-4 h-4 text-cyan-400" />
          Registration Settings
        </h3>

        <div className="grid grid-cols-2 gap-4">
          <InputGroup label="Register Interval (seconds)">
            <input
              type="number"
              value={channel.registrationInterval || 3600}
              onChange={(e) => handleChannelChange(activeSimTab, 'registrationInterval', parseInt(e.target.value))}
              placeholder="3600"
              className="w-full bg-black/50 border border-white/10 rounded-lg p-2.5 text-xs font-mono text-gray-400 outline-none focus:border-blue-500"
            />
            <p className="text-[8px] text-gray-500 mt-1">How often to re-register (default: 1 hour)</p>
          </InputGroup>

          <InputGroup label="Register Timeout (seconds)">
            <input
              type="number"
              value={channel.registerTimeout || 30}
              onChange={(e) => handleChannelChange(activeSimTab, 'registerTimeout', parseInt(e.target.value))}
              placeholder="30"
              className="w-full bg-black/50 border border-white/10 rounded-lg p-2.5 text-xs font-mono text-gray-400 outline-none focus:border-blue-500"
            />
            <p className="text-[8px] text-gray-500 mt-1">Timeout for registration attempts</p>
          </InputGroup>
        </div>
      </div>

      {/* Global Settings */}
      <div className="bg-[#080808] border border-white/10 rounded-2xl p-6 space-y-4">
        <h3 className="text-xs font-bold uppercase tracking-widest text-gray-300">Global Settings</h3>

        <div className="space-y-3">
          <label className="flex items-center gap-3 cursor-pointer">
            <input
              type="checkbox"
              checked={config.autoAnswer || false}
              onChange={(e) => setConfig({ ...config, autoAnswer: e.target.checked })}
              className="w-4 h-4 rounded cursor-pointer accent-blue-500"
            />
            <div className="flex-1">
              <span className="text-xs font-bold text-gray-300">Auto-Answer Calls</span>
              <p className="text-[8px] text-gray-500">Automatically answer incoming GSM calls</p>
            </div>
          </label>

          <label className="flex items-center gap-3 cursor-pointer">
            <input
              type="checkbox"
              checked={config.speakerphoneOn || false}
              onChange={(e) => setConfig({ ...config, speakerphoneOn: e.target.checked })}
              className="w-4 h-4 rounded cursor-pointer accent-blue-500"
            />
            <div className="flex-1">
              <span className="text-xs font-bold text-gray-300">Enable Speaker During Calls</span>
              <p className="text-[8px] text-gray-500">Route audio to speaker (for testing)</p>
            </div>
          </label>

          <InputGroup label="Jitter Buffer (ms)">
            <input
              type="number"
              value={config.jitterBufferMs || 50}
              onChange={(e) => setConfig({ ...config, jitterBufferMs: parseInt(e.target.value) })}
              placeholder="50"
              className="w-full bg-black/50 border border-white/10 rounded-lg p-2.5 text-xs font-mono text-gray-400 outline-none focus:border-blue-500"
            />
            <p className="text-[8px] text-gray-500 mt-1">Buffer for handling network jitter</p>
          </InputGroup>

          <div className="pt-2 px-3 py-2 bg-black/30 rounded-lg border border-white/5">
            <div className="flex items-center justify-between">
              <span className="text-xs font-bold text-gray-300">Root Access</span>
              <span className={`text-[10px] font-mono font-bold ${config.rootLevel ? 'text-green-400' : 'text-red-400'}`}>
                {config.rootLevel ? '✓ Granted' : '✗ Denied'}
              </span>
            </div>
            <p className="text-[8px] text-gray-500 mt-1">Required for audio routing via tinycap/tinyplay</p>
          </div>
        </div>
      </div>

      {/* Save Button (Bottom) */}
      <div className="sticky bottom-0 bg-[#050505] border-t border-white/10 p-4 flex gap-3">
        <button
          onClick={handleSave}
          className="flex-1 px-4 py-3 bg-blue-600 hover:bg-blue-500 text-white rounded-xl text-xs font-bold uppercase tracking-widest transition-all"
        >
          💾 Save All Changes
        </button>
        <button
          onClick={() => daemon.refreshTrunkRegistration(activeSimTab)}
          className="flex-1 px-4 py-3 bg-green-600/20 hover:bg-green-600/30 border border-green-500/30 text-green-400 rounded-xl text-xs font-bold uppercase tracking-widest transition-all"
        >
          ↻ Refresh Registration
        </button>
      </div>
    </div>
  );
};

interface InputGroupProps {
  label: string;
  children: React.ReactNode;
}

const InputGroup: React.FC<InputGroupProps> = ({ label, children }) => (
  <div className="space-y-2">
    <label className="block text-xs font-bold uppercase tracking-widest text-gray-400">
      {label}
    </label>
    {children}
  </div>
);

export default Settings;
