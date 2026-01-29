import React, { useState, useEffect } from 'react';
import { ActiveCall } from '../types';
import { CallDirectionIcon, RTPMetricsCard } from './shared';
import { ICONS } from '../constants';

interface CallViewProps {
  calls: [ActiveCall | null, ActiveCall | null];
  onHangup: (slot: 0 | 1) => void;
}

/**
 * Active Call Detail View
 * Shows per-call information with RTP metrics
 */
const CallView: React.FC<CallViewProps> = ({ calls, onHangup }) => {
  const [expandedSlot, setExpandedSlot] = useState<0 | 1 | null>(null);

  const hasActiveCalls = calls[0] !== null || calls[1] !== null;

  const formatDuration = (durationSeconds: number) => {
    const hours = Math.floor(durationSeconds / 3600);
    const minutes = Math.floor((durationSeconds % 3600) / 60);
    const seconds = durationSeconds % 60;
    
    if (hours > 0) {
      return `${hours}h ${minutes}m ${seconds}s`;
    }
    if (minutes > 0) {
      return `${minutes}m ${seconds}s`;
    }
    return `${seconds}s`;
  };

  if (!hasActiveCalls) {
    return (
      <div className="space-y-6 pb-20">
        <h1 className="text-2xl font-black text-white tracking-tight">Active Calls</h1>
        <div className="border border-white/10 rounded-2xl p-12 text-center">
          <ICONS.Phone className="w-12 h-12 text-gray-600 mx-auto mb-3" />
          <p className="text-gray-400 font-mono">No active calls</p>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6 pb-24">
      <h1 className="text-2xl font-black text-white tracking-tight">Active Calls</h1>

      {[0, 1].map((slot) => {
        const call = calls[slot];
        if (!call) return null;

        const isExpanded = expandedSlot === slot;

        return (
          <div
            key={`call-${slot}`}
            className="border border-green-500/30 bg-gradient-to-br from-green-500/10 to-transparent rounded-2xl overflow-hidden"
          >
            {/* Call Header - Always Visible */}
            <div
              onClick={() => setExpandedSlot(isExpanded ? null : (slot as 0 | 1))}
              className="p-6 cursor-pointer hover:bg-green-500/5 transition-all space-y-4"
            >
              {/* Top Row: Direction and Time */}
              <div className="flex items-start justify-between gap-4">
                <div className="flex-1 space-y-3">
                  <CallDirectionIcon direction={call.direction} size="md" showLabel={true} />

                  {/* Call Numbers */}
                  <div className="bg-black/30 border border-white/5 rounded-xl p-3 space-y-2">
                    <p className="text-[9px] font-bold text-gray-500 uppercase tracking-widest">Phone Numbers</p>
                    <div className="space-y-1">
                      <p className="text-sm font-mono text-blue-400">
                        {call.direction === 'GSM_TO_SIP' ? '📱 From: ' : '📞 To: '}
                        {call.gsmNumber}
                      </p>
                      <p className="text-[9px] font-mono text-gray-500">
                        Call ID: {call.sipCallId}
                      </p>
                    </div>
                  </div>
                </div>

                {/* Duration and Hangup */}
                <div className="text-right space-y-3">
                  <div className="bg-green-500/10 border border-green-500/20 rounded-xl p-3">
                    <p className="text-[9px] font-bold text-gray-500 uppercase tracking-widest mb-1">Duration</p>
                    <p className="text-lg font-mono font-black text-green-400">
                      {formatDuration(call.durationSeconds)}
                    </p>
                  </div>

                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      onHangup(slot as 0 | 1);
                    }}
                    className="w-full px-3 py-2 bg-red-500/20 hover:bg-red-500/30 border border-red-500/30 rounded-lg text-red-400 text-xs font-bold uppercase tracking-widest transition-all flex items-center justify-center gap-2"
                  >
                    <ICONS.HangUp className="w-3 h-3" />
                    Hang Up
                  </button>
                </div>
              </div>

              {/* Expandable Indicator */}
              <div className="flex items-center justify-center text-gray-600">
                <span className="text-[9px] font-mono">
                  {isExpanded ? '▼ Hide Details' : '▶ Show Details'}
                </span>
              </div>
            </div>

            {/* Expanded Details */}
            {isExpanded && (
              <div className="border-t border-green-500/20 p-6 bg-black/20 space-y-4 animate-in fade-in slide-in-from-top-2">
                {/* RTP Metrics */}
                <div className="space-y-2">
                  <h3 className="text-xs font-bold uppercase tracking-widest text-gray-300 flex items-center gap-2">
                    <ICONS.BarChart className="w-4 h-4 text-cyan-400" />
                    RTP Audio Metrics
                  </h3>
                  <RTPMetricsCard
                    jitterMs={call.audioMetrics.jitter}
                    packetLoss={call.audioMetrics.packetLoss || 0}
                    latencyMs={call.audioMetrics.latency}
                    bitrateKbps={64} // G.711
                    packetsRx={call.audioMetrics.rxPackets}
                    packetsTx={call.audioMetrics.txPackets}
                  />
                </div>

                {/* Call Details Grid */}
                <div className="grid grid-cols-2 gap-3">
                  {/* Slot Info */}
                  <div className="bg-black/40 border border-white/5 rounded-lg p-3 space-y-1">
                    <p className="text-[8px] text-gray-500 font-bold uppercase tracking-widest">SIM Slot</p>
                    <p className="text-sm font-black text-white">SIM {call.simSlot + 1}</p>
                  </div>

                  {/* Direction */}
                  <div className="bg-black/40 border border-white/5 rounded-lg p-3 space-y-1">
                    <p className="text-[8px] text-gray-500 font-bold uppercase tracking-widest">Call Type</p>
                    <p className="text-sm font-black text-white">
                      {call.direction === 'GSM_TO_SIP' ? 'Incoming' : 'Outgoing'}
                    </p>
                  </div>

                  {/* Start Time */}
                  <div className="bg-black/40 border border-white/5 rounded-lg p-3 space-y-1">
                    <p className="text-[8px] text-gray-500 font-bold uppercase tracking-widest">Started</p>
                    <p className="text-xs font-mono text-gray-300">
                      {new Date(call.startTime).toLocaleTimeString()}
                    </p>
                  </div>

                  {/* Buffer Depth */}
                  <div className="bg-black/40 border border-white/5 rounded-lg p-3 space-y-1">
                    <p className="text-[8px] text-gray-500 font-bold uppercase tracking-widest">Buffer Depth</p>
                    <p className={`text-sm font-bold ${
                      call.audioMetrics.bufferDepth > 200 ? 'text-amber-400' : 'text-green-400'
                    }`}>
                      {call.audioMetrics.bufferDepth}ms
                    </p>
                  </div>

                  {/* Underruns */}
                  <div className="bg-black/40 border border-white/5 rounded-lg p-3 space-y-1">
                    <p className="text-[8px] text-gray-500 font-bold uppercase tracking-widest">Underruns</p>
                    <p className={`text-sm font-bold ${
                      call.audioMetrics.underruns && call.audioMetrics.underruns > 0 
                        ? 'text-red-400' 
                        : 'text-green-400'
                    }`}>
                      {call.audioMetrics.underruns || 0}
                    </p>
                  </div>

                  {/* Clipping */}
                  <div className="bg-black/40 border border-white/5 rounded-lg p-3 space-y-1">
                    <p className="text-[8px] text-gray-500 font-bold uppercase tracking-widest">Clipping</p>
                    <p className={`text-sm font-bold ${
                      call.audioMetrics.isClipping ? 'text-red-400' : 'text-green-400'
                    }`}>
                      {call.audioMetrics.isClipping ? 'YES' : 'NO'}
                    </p>
                  </div>
                </div>

                {/* Signaling Events */}
                {call.signaling && call.signaling.length > 0 && (
                  <div className="space-y-2">
                    <h4 className="text-xs font-bold uppercase tracking-widest text-gray-300">
                      SIP Signaling Events
                    </h4>
                    <div className="bg-black/40 border border-white/5 rounded-lg p-3 space-y-1 max-h-40 overflow-y-auto">
                      {call.signaling.map((event, idx) => (
                        <p key={idx} className="text-[8px] font-mono text-gray-400">
                          {event}
                        </p>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
};

export default CallView;
