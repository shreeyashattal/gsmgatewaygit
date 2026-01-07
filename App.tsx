
import React, { useState, useEffect, useCallback } from 'react';
import { CallState, SipStatus, GsmStatus, LogEntry, GatewayConfig, ActiveCall, BackendMetrics } from './types';
import { ICONS, APP_VERSION } from './constants';
import Dashboard from './components/Dashboard';
import Settings from './components/Settings';
import Logs from './components/Logs';
import CallView from './components/CallView';
import AIAssistant from './components/AIAssistant';
import ModemDebugger from './components/ModemDebugger';
import { daemon } from './services/GatewayDaemon';

type Tab = 'status' | 'trunks' | 'radio' | 'logs';

const App: React.FC = () => {
  const [activeTab, setActiveTab] = useState<Tab>('status');
  const [metrics, setMetrics] = useState<BackendMetrics>(daemon.state.metrics);
  const [callStates, setCallStates] = useState<[CallState, CallState]>([CallState.IDLE, CallState.IDLE]);
  const [activeCalls, setActiveCalls] = useState<[ActiveCall | null, ActiveCall | null]>([null, null]);
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [config, setConfig] = useState<GatewayConfig>(daemon.state.config);

  const addLog = useCallback((log: LogEntry) => {
    setLogs(prev => [log, ...prev].slice(0, 150));
  }, []);

  useEffect(() => {
    daemon.subscribe('state_changed', (state: any) => {
      setCallStates([...state.callStates] as [CallState, CallState]);
      setActiveCalls([...state.activeCalls] as [ActiveCall | null, ActiveCall | null]);
    });
    daemon.subscribe('new_log', (log: LogEntry) => addLog(log));
    daemon.subscribe('metrics_updated', (newMetrics: BackendMetrics) => {
      setMetrics({...newMetrics});
    });
  }, [addLog]);

  const handleHangup = (slot: 0 | 1) => {
    daemon.terminateCall(slot, 'USER_REQUEST');
  };

  return (
    <div className="flex flex-col min-h-screen bg-[#050505] text-gray-200 font-sans selection:bg-blue-500 selection:text-white relative">
      
      {/* Root Protection Overlay */}
      {!metrics.isRooted && (
        <div className="fixed inset-0 z-[100] bg-black/98 backdrop-blur-3xl flex items-center justify-center p-8 text-center">
          <div className="max-w-md space-y-10 animate-in zoom-in-95 duration-700">
            <div className="w-20 h-20 bg-red-600/10 border border-red-500/20 rounded-full mx-auto flex items-center justify-center text-red-500">
              <ICONS.Shield />
            </div>
            <div className="space-y-3">
              <h2 className="text-2xl font-black text-white uppercase tracking-tighter">Root Handshake Required</h2>
              <p className="text-[11px] text-gray-500 leading-relaxed font-medium">
                Kernel-level audio routing requires <code>su</code>. Please authorize SuperUser access to continue.
              </p>
            </div>
            <button 
              onClick={() => window.location.reload()}
              className="px-10 py-4 bg-white text-black font-black text-[9px] uppercase tracking-[0.4em] rounded-2xl active:scale-95 transition-all shadow-2xl"
            >
              Verify SU Access
            </button>
          </div>
        </div>
      )}

      {/* Modern Compact Header */}
      <header className="px-6 py-4 border-b border-white/5 flex justify-between items-center bg-[#0a0a0a]/90 backdrop-blur-xl sticky top-0 z-50">
        <div className="flex items-center gap-4">
          <div className="w-8 h-8 bg-white rounded-lg flex items-center justify-center text-black font-black text-sm shadow-xl select-none transition-transform active:scale-90">SG</div>
          <div>
            <h1 className="text-[11px] font-black tracking-widest uppercase text-white">Shreeyash Gateway</h1>
            <div className="flex items-center gap-2 mt-0.5">
               <span className="text-[7px] font-black text-blue-500 tracking-widest uppercase">V{APP_VERSION}</span>
               <div className="w-0.5 h-0.5 bg-white/20 rounded-full" />
               <span className="text-[7px] text-gray-600 font-mono uppercase tracking-widest">KERNEL-SYNC: OK</span>
            </div>
          </div>
        </div>
        
        <div className="flex gap-1.5">
            {[0, 1].map(i => (
              <div key={i} className={`w-2 h-2 rounded-full border border-black/50 ${daemon.state.sipStatuses[i] === SipStatus.REGISTERED || daemon.state.sipStatuses[i] === SipStatus.LISTENING ? 'bg-blue-500 shadow-[0_0_10px_rgba(59,130,246,0.8)]' : 'bg-red-500 shadow-[0_0_10px_rgba(239,68,68,0.4)]'}`} />
            ))}
        </div>
      </header>

      {/* Main Content Area */}
      <main className="flex-1 overflow-y-auto p-4 sm:p-6 pb-28">
        <div className="max-w-4xl mx-auto space-y-6">
          
          {activeTab === 'status' && (
            <div className="animate-in fade-in duration-500 space-y-6">
              {/* Call Monitoring */}
              <div className="space-y-4">
                {[0, 1].map((slot) => (
                  callStates[slot] !== CallState.IDLE && (
                    <div key={slot} className="animate-in slide-in-from-top-4 duration-500">
                      <CallView 
                        call={activeCalls[slot]} 
                        state={callStates[slot]} 
                        onHangup={() => handleHangup(slot as 0 | 1)} 
                      />
                    </div>
                  )
                ))}
              </div>
              <Dashboard />
              <AIAssistant logs={logs} metrics={metrics} />
            </div>
          )}

          {activeTab === 'trunks' && (
            <div className="animate-in slide-in-from-right-4 duration-500">
              <Settings config={config} setConfig={setConfig} />
            </div>
          )}

          {activeTab === 'radio' && (
            <div className="animate-in slide-in-from-right-4 duration-500">
              <ModemDebugger />
            </div>
          )}

          {activeTab === 'logs' && (
            <div className="bg-[#0a0a0a] rounded-[28px] border border-white/5 p-6 min-h-[500px] shadow-2xl overflow-hidden animate-in fade-in duration-500">
                <Logs logs={logs} />
            </div>
          )}
        </div>
      </main>

      {/* Floating Tactical Navigation */}
      <nav className="fixed bottom-6 left-1/2 -translate-x-1/2 bg-[#0c0c0c]/80 backdrop-blur-2xl border border-white/10 p-1.5 flex gap-1 rounded-[28px] shadow-[0_20px_50px_rgba(0,0,0,0.8)] z-50">
        <NavButton active={activeTab === 'status'} onClick={() => setActiveTab('status')} label="Dash" icon={<ICONS.Activity />} />
        <NavButton active={activeTab === 'trunks'} onClick={() => setActiveTab('trunks')} label="Trunks" icon={<ICONS.Cog />} />
        <NavButton active={activeTab === 'radio'} onClick={() => setActiveTab('radio')} label="Radio" icon={<ICONS.Signal />} />
        <NavButton active={activeTab === 'logs'} onClick={() => setActiveTab('logs')} label="Journal" icon={<ICONS.Phone />} />
      </nav>
    </div>
  );
};

const NavButton: React.FC<{ active: boolean; onClick: () => void; label: string; icon: React.ReactNode }> = ({ active, onClick, label, icon }) => (
  <button 
    onClick={onClick} 
    className={`relative group flex flex-col items-center justify-center gap-1 w-14 h-12 rounded-[22px] transition-all duration-300 ${active ? 'bg-white text-black shadow-xl scale-105' : 'text-gray-500 hover:text-gray-300 hover:bg-white/5'}`}
  >
    {active && (
      <div className="absolute -top-1 w-1 h-1 bg-blue-500 rounded-full shadow-[0_0_8px_rgba(59,130,246,1)] animate-pulse" />
    )}
    <div className={`w-4 h-4 transition-transform duration-300 ${active ? 'scale-110' : 'group-hover:scale-110 group-active:scale-90'}`}>
      {icon}
    </div>
    <span className={`text-[7px] font-black uppercase tracking-tight transition-opacity ${active ? 'opacity-100' : 'opacity-40'}`}>
      {label}
    </span>
  </button>
);

export default App;
