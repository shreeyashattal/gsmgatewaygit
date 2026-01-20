import React from 'react';
import { daemon } from '../services/GatewayDaemon';
import { GsmStatus, BridgeStatus } from '../types';
import { ICONS } from '../constants';

const ModemDebugger: React.FC = () => {
  const { metrics, bridgeStatus, config } = daemon.state;

  const formatUptime = (seconds: number) => {
    const hours = Math.floor(seconds / 3600);
    const mins = Math.floor((seconds % 3600) / 60);
    const secs = seconds % 60;
    return `${hours.toString().padStart(2, '0')}:${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
  };

  return (
    <div className="space-y-6">
      {/* System Stats */}
      <div className="bg-[#0a0a0a] border border-white/10 rounded-2xl p-5 shadow-2xl">
        <div className="flex items-center gap-2 mb-4">
          <div className="w-2 h-2 rounded-full bg-blue-500 shadow-[0_0_10px_rgba(59,130,246,0.6)]" />
          <h2 className="text-[10px] font-black text-gray-400 uppercase tracking-widest">System Statistics</h2>
        </div>

        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <StatCard label="Uptime" value={formatUptime(Math.floor(metrics.uptime))} />
          <StatCard label="Processor" value={metrics.processor} />
          <StatCard label="Dual SIM" value={metrics.slotCount === 2 ? 'Active' : 'Single'} />
          <StatCard
            label="Root Access"
            value={metrics.isRooted ? 'Granted' : 'Denied'}
            valueColor={metrics.isRooted ? 'text-green-400' : 'text-red-400'}
          />
        </div>
      </div>

      {/* AMI Connection Info */}
      <div className="bg-[#0a0a0a] border border-white/10 rounded-2xl p-5 shadow-2xl">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <div className={`w-2 h-2 rounded-full ${
              bridgeStatus === BridgeStatus.CONNECTED ? 'bg-green-500 shadow-[0_0_10px_rgba(34,197,94,0.6)]' :
              bridgeStatus === BridgeStatus.CONNECTING ? 'bg-yellow-500 animate-pulse' :
              'bg-red-500'
            }`} />
            <h2 className="text-[10px] font-black text-gray-400 uppercase tracking-widest">Asterisk AMI</h2>
          </div>
          <span className={`text-[9px] font-black ${
            bridgeStatus === BridgeStatus.CONNECTED ? 'text-green-400' :
            bridgeStatus === BridgeStatus.CONNECTING ? 'text-yellow-400' :
            'text-red-400'
          }`}>
            {bridgeStatus}
          </span>
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div className="bg-black/40 border border-white/5 rounded-xl p-3">
            <p className="text-[8px] text-gray-500 font-bold uppercase mb-1">Host</p>
            <p className="text-xs font-mono text-blue-400">127.0.0.1</p>
          </div>
          <div className="bg-black/40 border border-white/5 rounded-xl p-3">
            <p className="text-[8px] text-gray-500 font-bold uppercase mb-1">Port</p>
            <p className="text-xs font-mono text-blue-400">5038</p>
          </div>
        </div>
      </div>

      {/* Channel Configuration Summary */}
      <div className="bg-[#0a0a0a] border border-white/10 rounded-2xl p-5 shadow-2xl">
        <div className="flex items-center gap-2 mb-4">
          <ICONS.Cog className="w-3 h-3 text-gray-500" />
          <h2 className="text-[10px] font-black text-gray-400 uppercase tracking-widest">Channel Configuration</h2>
        </div>

        <div className="space-y-3">
          {[0, 1].map((slot) => {
            const sim = metrics.sims[slot];
            const channel = config.channels[slot];
            const isDetected = sim.status !== GsmStatus.NOT_DETECTED && sim.carrier !== 'No SIM' && sim.carrier !== 'Empty';

            if (slot === 1 && !isDetected) return null;

            return (
              <div key={slot} className={`bg-black/40 border rounded-xl p-4 ${
                channel.enabled ? 'border-blue-500/20' : 'border-white/5 opacity-60'
              }`}>
                <div className="flex items-center justify-between mb-3">
                  <div className="flex items-center gap-2">
                    <div className={`w-2 h-2 rounded-full ${
                      channel.enabled ? 'bg-blue-500 shadow-[0_0_8px_rgba(59,130,246,0.6)]' : 'bg-gray-700'
                    }`} />
                    <span className="text-[10px] font-black text-white">SIM {slot + 1}</span>
                    <span className="text-[9px] text-gray-500 font-mono">{sim.carrier}</span>
                  </div>
                  <span className={`text-[8px] font-black px-2 py-0.5 rounded ${
                    channel.enabled ? 'bg-green-500/10 text-green-400' : 'bg-gray-500/10 text-gray-400'
                  }`}>
                    {channel.enabled ? 'ENABLED' : 'DISABLED'}
                  </span>
                </div>

                <div className="grid grid-cols-4 gap-2 text-[9px]">
                  <div>
                    <p className="text-gray-600 uppercase mb-0.5">Context</p>
                    <p className="text-gray-300 font-mono">{channel.asteriskContext}</p>
                  </div>
                  <div>
                    <p className="text-gray-600 uppercase mb-0.5">Extension</p>
                    <p className="text-gray-300 font-mono">{channel.defaultExtension}</p>
                  </div>
                  <div>
                    <p className="text-gray-600 uppercase mb-0.5">Codec</p>
                    <p className="text-gray-300 font-mono">{channel.codec}</p>
                  </div>
                  <div>
                    <p className="text-gray-600 uppercase mb-0.5">RTP Port</p>
                    <p className="text-gray-300 font-mono">{channel.rtpPort}</p>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Global Settings */}
      <div className="bg-[#0a0a0a] border border-white/10 rounded-2xl p-5 shadow-2xl">
        <div className="flex items-center gap-2 mb-4">
          <ICONS.Activity className="w-3 h-3 text-gray-500" />
          <h2 className="text-[10px] font-black text-gray-400 uppercase tracking-widest">Active Settings</h2>
        </div>

        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          <SettingBadge label="Auto Answer" active={config.autoAnswer} />
          <SettingBadge label="Speakerphone" active={config.speakerphoneOn} />
          <SettingBadge label="Root Mode" active={config.rootLevel} />
          <div className="bg-black/40 border border-white/5 rounded-xl p-3">
            <p className="text-[8px] text-gray-600 uppercase mb-1">Jitter Buffer</p>
            <p className="text-xs font-mono text-gray-300">{config.jitterBufferMs}ms</p>
          </div>
        </div>
      </div>

      {/* Help Info */}
      <div className="bg-blue-500/5 border border-blue-500/20 rounded-xl p-4">
        <p className="text-[9px] font-bold text-blue-400 uppercase tracking-widest mb-2">Quick Reference</p>
        <ul className="text-[10px] text-gray-400 space-y-1">
          <li>• <span className="text-gray-300">Context</span>: Asterisk dialplan context for incoming GSM calls</li>
          <li>• <span className="text-gray-300">Extension 's'</span>: Standard Asterisk start extension (default entry point)</li>
          <li>• <span className="text-gray-300">PCMU</span>: G.711 μ-law codec, <span className="text-gray-300">G722</span>: HD voice codec</li>
          <li>• <span className="text-gray-300">RTP Port</span>: UDP port for real-time audio transport</li>
        </ul>
      </div>
    </div>
  );
};

const StatCard: React.FC<{ label: string; value: string; valueColor?: string }> = ({ label, value, valueColor = 'text-white' }) => (
  <div className="bg-black/40 border border-white/5 rounded-xl p-3">
    <p className="text-[8px] text-gray-500 font-bold uppercase mb-1">{label}</p>
    <p className={`text-sm font-black ${valueColor}`}>{value}</p>
  </div>
);

const SettingBadge: React.FC<{ label: string; active: boolean }> = ({ label, active }) => (
  <div className={`border rounded-xl p-3 ${
    active ? 'bg-green-500/5 border-green-500/20' : 'bg-black/40 border-white/5'
  }`}>
    <p className="text-[8px] text-gray-600 uppercase mb-1">{label}</p>
    <div className="flex items-center gap-1.5">
      <div className={`w-1.5 h-1.5 rounded-full ${active ? 'bg-green-500' : 'bg-gray-600'}`} />
      <p className={`text-xs font-bold ${active ? 'text-green-400' : 'text-gray-500'}`}>
        {active ? 'ON' : 'OFF'}
      </p>
    </div>
  </div>
);

export default ModemDebugger;
