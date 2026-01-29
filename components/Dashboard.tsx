import React, { useEffect, useState } from 'react';
import { daemon } from '../services/GatewayDaemon';
import { 
  StatusBadge, 
  SignalIndicator, 
  CallDirectionIcon, 
  RTPMetricsCard, 
  TrunkStatusCard 
} from './shared';
import { ICONS, APP_VERSION } from '../constants';
import { ActiveCall, SIPRegistrationState } from '../types';

/**
 * Modern Dashboard for GSM-SIP Gateway
 * Shows real-time monitoring of dual SIM trunks and active calls
 */
const Dashboard: React.FC = () => {
  const { metrics, config, activeCalls } = daemon.state;
  const [expandedCall, setExpandedCall] = useState<string | null>(null);

  // Format call duration
  const formatDuration = (durationSeconds: number) => {
    const hours = Math.floor(durationSeconds / 3600);
    const minutes = Math.floor((durationSeconds % 3600) / 60);
    const seconds = durationSeconds % 60;
    
    if (hours > 0) return `${hours}h ${minutes}m ${seconds}s`;
    if (minutes > 0) return `${minutes}m ${seconds}s`;
    return `${seconds}s`;
  };

  // Format timestamp to time string
  const formatTimeString = (timestamp: number) => {
    const date = new Date(timestamp);
    return date.toLocaleTimeString();
  };

  const hasActiveCalls = (activeCalls[0] !== null) || (activeCalls[1] !== null);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex justify-between items-start">
        <div>
          <h1 className="text-2xl font-black text-white tracking-tight">Gateway Dashboard</h1>
          <p className="text-xs text-gray-400 font-mono mt-1">v{APP_VERSION}</p>
        </div>
        <div className="text-right">
          <p className="text-[10px] font-bold text-gray-500 uppercase tracking-widest">Uptime</p>
          <p className="text-sm font-mono text-blue-400">{Math.floor(metrics.uptime / 1000)}s</p>
        </div>
      </div>

      {/* SIM Trunk Overview - Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {[0, 1].map((slot) => {
          const simMetrics = metrics.sims[slot];
          const channel = config.channels[slot];
          const isDetected = simMetrics.carrier && simMetrics.carrier !== 'Empty';

          // Hide SIM2 if not detected
          if (slot === 1 && (!isDetected || metrics.slotCount < 2)) {
            return null;
          }

          return (
            <TrunkStatusCard
              key={slot}
              simSlot={slot as 0 | 1}
              registered={simMetrics.sipRegistrationState === SIPRegistrationState.REGISTERED}
              registering={simMetrics.sipRegistrationState === SIPRegistrationState.REGISTERING}
              lastRegisteredTime={simMetrics.lastRegisteredTime}
              nextRegisterTime={simMetrics.nextRegisterTime}
              carrier={simMetrics.carrier}
              signal={simMetrics.radioSignal}
              networkType={simMetrics.networkType}
              phoneNumber={simMetrics.phoneNumber}
              sipUsername={channel.sipUsername}
              pbxHost={channel.pbxHost}
              pbxPort={channel.pbxPort}
              onRefreshRegister={() => {
                // TODO: Implement refresh registration
                console.log(`Refreshing SIM${slot} registration`);
              }}
            />
          );
        })}
      </div>

      {/* Active Calls Section */}
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <h2 className="text-xs font-black uppercase tracking-widest text-gray-300 flex items-center gap-2">
            <ICONS.Phone className="w-4 h-4 text-blue-400" />
            Active Calls
            {hasActiveCalls && (
              <span className="ml-auto text-[10px] font-mono bg-green-500/20 text-green-400 px-2 py-1 rounded">
                {(activeCalls[0] ? 1 : 0) + (activeCalls[1] ? 1 : 0)} Active
              </span>
            )}
          </h2>
        </div>

        {hasActiveCalls ? (
          <div className="space-y-2">
            {[0, 1].map((slot) => {
              const call = activeCalls[slot];
              if (!call) return null;

              const isExpanded = expandedCall === call.id;

              return (
                <div
                  key={call.id}
                  className="border border-green-500/30 bg-green-500/5 rounded-2xl p-4 space-y-3 cursor-pointer transition-all hover:border-green-500/50"
                  onClick={() => setExpandedCall(isExpanded ? null : call.id)}
                >
                  {/* Call Header */}
                  <div className="flex justify-between items-start gap-4">
                    <div className="flex-1 space-y-2">
                      <div className="flex items-center gap-3">
                        <CallDirectionIcon direction={call.direction} size="md" showLabel={false} />
                        <div className="flex-1">
                          <div className="flex items-baseline gap-2">
                            <p className="text-sm font-bold text-white">
                              {call.direction === 'GSM_TO_SIP' ? call.gsmNumber : call.gsmNumber}
                            </p>
                            <p className="text-[10px] font-mono text-gray-500">
                              SIM {call.simSlot + 1} • {formatDuration(call.durationSeconds)}
                            </p>
                          </div>
                          <p className="text-[9px] text-gray-400 font-mono mt-1">
                            Call ID: {call.sipCallId}
                          </p>
                        </div>
                      </div>
                    </div>

                    {/* Quick Hangup Button */}
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        daemon.terminateCall(call.simSlot, 'USER_REQUEST');
                      }}
                      className="p-2 bg-red-500/10 hover:bg-red-500/20 border border-red-500/30 rounded-lg text-red-400 transition-all"
                    >
                      <ICONS.HangUp className="w-4 h-4" />
                    </button>
                  </div>

                  {/* Expanded Details */}
                  {isExpanded && (
                    <div className="pt-3 border-t border-green-500/20 space-y-3 animate-in fade-in slide-in-from-top-2">
                      {/* RTP Metrics */}
                      <RTPMetricsCard
                        jitterMs={call.audioMetrics.jitter}
                        packetLoss={call.audioMetrics.packetLoss || 0}
                        latencyMs={call.audioMetrics.latency}
                        bitrateKbps={64} // G.711
                        packetsRx={call.audioMetrics.rxPackets}
                        packetsTx={call.audioMetrics.txPackets}
                      />

                      {/* Call Details Grid */}
                      <div className="grid grid-cols-2 gap-3 text-[9px]">
                        <div className="bg-black/30 rounded-lg p-2 space-y-1">
                          <p className="text-gray-500 font-bold uppercase tracking-widest">Call Direction</p>
                          <p className="text-gray-300 font-mono">
                            {call.direction === 'GSM_TO_SIP' ? 'Incoming' : 'Outgoing'}
                          </p>
                        </div>
                        <div className="bg-black/30 rounded-lg p-2 space-y-1">
                          <p className="text-gray-500 font-bold uppercase tracking-widest">Started</p>
                          <p className="text-gray-300 font-mono">{formatTimeString(call.startTime)}</p>
                        </div>
                        <div className="bg-black/30 rounded-lg p-2 space-y-1">
                          <p className="text-gray-500 font-bold uppercase tracking-widest">Buffer</p>
                          <p className={`font-mono ${call.audioMetrics.bufferDepth > 200 ? 'text-amber-400' : 'text-green-400'}`}>
                            {call.audioMetrics.bufferDepth}ms
                          </p>
                        </div>
                        <div className="bg-black/30 rounded-lg p-2 space-y-1">
                          <p className="text-gray-500 font-bold uppercase tracking-widest">Underruns</p>
                          <p className={`font-mono ${call.audioMetrics.underruns && call.audioMetrics.underruns > 0 ? 'text-red-400' : 'text-green-400'}`}>
                            {call.audioMetrics.underruns || 0}
                          </p>
                        </div>
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        ) : (
          <div className="border border-white/10 rounded-2xl p-6 text-center">
            <ICONS.Phone className="w-8 h-8 text-gray-600 mx-auto mb-2" />
            <p className="text-sm text-gray-400 font-mono">No active calls</p>
          </div>
        )}
      </div>

      {/* System Health */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <div className="bg-[#080808] border border-white/10 rounded-xl p-3 space-y-2">
          <p className="text-[8px] font-bold text-gray-500 uppercase tracking-widest">CPU Usage</p>
          <div className="space-y-1">
            <p className="text-lg font-black text-white">{Math.round(metrics.cpuUsage)}%</p>
            <div className="w-full bg-white/5 rounded-full h-1 overflow-hidden">
              <div 
                className="bg-blue-500 h-full transition-all"
                style={{ width: `${metrics.cpuUsage}%` }}
              />
            </div>
          </div>
        </div>

        <div className="bg-[#080808] border border-white/10 rounded-xl p-3 space-y-2">
          <p className="text-[8px] font-bold text-gray-500 uppercase tracking-widest">Memory</p>
          <div className="space-y-1">
            <p className="text-lg font-black text-white">{Math.round(metrics.memUsage)}%</p>
            <div className="w-full bg-white/5 rounded-full h-1 overflow-hidden">
              <div 
                className="bg-purple-500 h-full transition-all"
                style={{ width: `${metrics.memUsage}%` }}
              />
            </div>
          </div>
        </div>

        <div className="bg-[#080808] border border-white/10 rounded-xl p-3 space-y-2">
          <p className="text-[8px] font-bold text-gray-500 uppercase tracking-widest">Temperature</p>
          <p className="text-lg font-black text-white">{metrics.temp}°C</p>
          <p className={`text-[8px] font-mono ${metrics.temp > 60 ? 'text-red-400' : metrics.temp > 50 ? 'text-amber-400' : 'text-green-400'}`}>
            {metrics.temp > 60 ? 'HIGH' : metrics.temp > 50 ? 'WARM' : 'NORMAL'}
          </p>
        </div>

        <div className="bg-[#080808] border border-white/10 rounded-xl p-3 space-y-2">
          <p className="text-[8px] font-bold text-gray-500 uppercase tracking-widest">Processor</p>
          <p className="text-sm font-black text-white">{metrics.processor}</p>
          <p className={`text-[8px] font-mono ${metrics.isRooted ? 'text-green-400' : 'text-red-400'}`}>
            {metrics.isRooted ? 'Rooted' : 'Not Rooted'}
          </p>
        </div>
      </div>
    </div>
  );
};

export default Dashboard;
