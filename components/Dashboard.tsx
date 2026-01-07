import React from 'react';
import { SipStatus, GsmStatus, CallState } from '../types';
import { daemon } from '../services/GatewayDaemon';
import { ICONS } from '../constants';

const Dashboard: React.FC = () => {
  const { metrics, sipStatuses, callStates, config } = daemon.state;

  const renderSignalTowers = (signal: number) => {
    // Convert signal (-110 to -50 range) to 0-5 bars
    const bars = Math.max(0, Math.min(5, Math.floor((signal + 110) / 12)));
    return (
      <div className="flex items-end gap-[2px] h-4">
        {[1, 2, 3, 4, 5].map((b) => (
          <div 
            key={b} 
            className={`w-1.5 rounded-t-[1px] transition-all duration-300 ${b <= bars ? (bars > 3 ? 'bg-blue-500 shadow-[0_0_8px_rgba(59,130,246,0.6)]' : 'bg-amber-500 shadow-[0_0_8px_rgba(245,158,11,0.4)]') : 'bg-white/5'}`} 
            style={{ height: `${b * 20}%` }} 
          />
        ))}
      </div>
    );
  };

  const getQualityLabel = (signal: number) => {
    if (signal >= -70) return 'EXCELLENT';
    if (signal >= -85) return 'STABLE';
    if (signal >= -100) return 'FAIR';
    return 'POOR';
  };

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {[0, 1].map((slot) => {
          const simMetrics = metrics.sims[slot];
          const isDetected = simMetrics.status !== GsmStatus.NOT_DETECTED;
          const trunk = config.trunks[slot];
          const isActive = trunk.serviceActive;
          const isBusy = callStates[slot] !== CallState.IDLE;

          // Hide SIM slot 2 if not detected or not populated
          if (slot === 1 && (!isDetected || metrics.slotCount < 2 || (!simMetrics.carrier || simMetrics.carrier === "Empty" || simMetrics.carrier === "No SIM"))) {
            return null; // Don't render slot 2 if not populated
          }

          return (
            <div key={slot} className={`relative transition-all duration-500 ${isActive ? 'opacity-100' : 'opacity-70 scale-[0.99]'}`}>
              {/* Status Indicator Glow */}
              {isActive && (
                <div className={`absolute -inset-[1px] rounded-[32px] blur-lg transition-opacity duration-1000 ${isBusy ? 'bg-green-500/10' : 'bg-blue-500/10'}`} />
              )}
              
              <div className={`relative bg-[#080808] border rounded-[32px] p-6 space-y-5 shadow-2xl transition-all ${isActive ? 'border-white/10' : 'border-white/5'}`}>
                
                {/* Header: Carrier & Main Toggle */}
                <div className="flex justify-between items-start">
                  <div className="space-y-1">
                    <div className="flex items-center gap-2">
                      <div className={`w-2 h-2 rounded-full ${isActive ? 'bg-blue-500 shadow-[0_0_12px_rgba(59,130,246,0.8)]' : 'bg-gray-800'}`} />
                      <span className="text-[9px] font-black text-gray-500 uppercase tracking-widest">Channel {slot + 1} Gateway</span>
                    </div>
                    <h2 className="text-xl font-black text-white tracking-tight">{simMetrics.carrier || 'Searching...'}</h2>
                    <p className="text-[11px] font-mono text-blue-400 font-bold tracking-tight">{simMetrics.phoneNumber || '+00 00000 00000'}</p>
                  </div>
                  
                  <button 
                    onClick={() => daemon.toggleService(slot as 0 | 1)}
                    className={`px-5 py-2 rounded-2xl text-[10px] font-black uppercase tracking-widest transition-all ${isActive ? 'bg-red-500/10 text-red-500 border border-red-500/20 hover:bg-red-500/20' : 'bg-blue-600 text-white shadow-xl shadow-blue-900/40 hover:bg-blue-500'}`}
                  >
                    {isActive ? 'Stop' : 'Start'}
                  </button>
                </div>

                {/* Main Status Grid */}
                <div className="grid grid-cols-2 gap-3">
                  <div className="bg-white/[0.03] border border-white/5 rounded-2xl p-4 flex flex-col justify-between h-20">
                    <p className="text-[8px] font-black text-gray-500 uppercase tracking-widest">Network Signal</p>
                    <div className="flex items-end justify-between">
                      <span className={`text-[10px] font-black tracking-tighter ${simMetrics.radioSignal >= -85 ? 'text-blue-400' : 'text-amber-500'}`}>
                        {getQualityLabel(simMetrics.radioSignal)}
                      </span>
                      {renderSignalTowers(simMetrics.radioSignal)}
                    </div>
                  </div>

                  <div className="bg-white/[0.03] border border-white/5 rounded-2xl p-4 flex flex-col justify-between h-20">
                    <p className="text-[8px] font-black text-gray-500 uppercase tracking-widest">Connection</p>
                    <div className="flex items-center gap-2">
                       {simMetrics.connectionType === 'VoWiFi' ? (
                         <>
                           <ICONS.Wifi className="text-blue-400 w-4 h-4" />
                           <span className="text-[10px] font-black text-blue-400">VoWiFi HD</span>
                         </>
                       ) : (
                         <>
                           <ICONS.Tower className="text-gray-400 w-4 h-4" />
                           <span className="text-[10px] font-black text-gray-400">CELLULAR</span>
                         </>
                       )}
                    </div>
                    <p className={`text-[9px] font-black tracking-widest uppercase ${isActive ? 'text-gray-300' : 'text-gray-600'}`}>
                      {isActive ? sipStatuses[slot] : 'OFFLINE'}
                    </p>
                  </div>
                </div>

                {/* SIP Endpoint Config Readout */}
                <div className="bg-black/40 border border-white/5 rounded-2xl p-4 space-y-2">
                  <div className="flex justify-between items-center text-[10px]">
                    <span className="text-gray-600 font-bold uppercase tracking-widest">Binding</span>
                    <span className="text-gray-300 font-mono">{isActive ? `${trunk.sipServer}:${trunk.sipPort}` : '----'}</span>
                  </div>
                  <div className="flex justify-between items-center text-[10px]">
                    <span className="text-gray-600 font-bold uppercase tracking-widest">Identity</span>
                    <span className="text-gray-300 font-mono truncate max-w-[120px]">{isActive ? trunk.sipUser : '----'}</span>
                  </div>
                </div>

                {/* Test Controls */}
                {isActive && (
                  <div className="pt-2 grid grid-cols-2 gap-2 animate-in fade-in slide-in-from-bottom-2 duration-500">
                    <button 
                      onClick={() => daemon.handleIncomingGsm(slot as 0 | 1, simMetrics.phoneNumber)}
                      disabled={isBusy}
                      className="w-full py-3.5 bg-blue-500/5 hover:bg-blue-500/10 border border-blue-500/20 rounded-2xl text-[10px] font-black uppercase tracking-widest text-blue-400 transition-all disabled:opacity-30 disabled:grayscale flex items-center justify-center gap-3"
                    >
                      {isBusy ? (
                        <span className="flex items-center gap-2 text-green-400 animate-pulse">
                          <div className="w-2 h-2 bg-green-500 rounded-full" />
                          BUSY
                        </span>
                      ) : (
                        <>
                          <ICONS.Phone className="w-3 h-3" />
                          Test Inbound
                        </>
                      )}
                    </button>
                    <button 
                      onClick={() => daemon.testSipInvite(slot as 0 | 1)}
                      disabled={isBusy}
                      className="w-full py-3.5 bg-purple-500/5 hover:bg-purple-500/10 border border-purple-500/20 rounded-2xl text-[10px] font-black uppercase tracking-widest text-purple-400 transition-all disabled:opacity-30 disabled:grayscale flex items-center justify-center gap-3"
                    >
                      {isBusy ? (
                        <span className="flex items-center gap-2 text-green-400 animate-pulse">
                          <div className="w-2 h-2 bg-green-500 rounded-full" />
                          BUSY
                        </span>
                      ) : (
                        <>
                          <ICONS.Cog className="w-3 h-3" />
                          Test Outbound
                        </>
                      )}
                    </button>
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default Dashboard;
