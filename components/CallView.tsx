import React, { useState, useEffect, useRef } from 'react';
import { ActiveCall, CallState } from '../types';

interface CallViewProps {
  call: ActiveCall | null;
  state: CallState;
  onHangup: () => void;
}

const CallView: React.FC<CallViewProps> = ({ call, state, onHangup }) => {
  const [seconds, setSeconds] = useState(0);
  const [spectrum, setSpectrum] = useState<number[]>(new Array(16).fill(10));

  useEffect(() => {
    let interval: any;
    if (state === CallState.BRIDGING) {
      interval = setInterval(() => {
        setSeconds(s => s + 1);
      }, 1000);
    }
    return () => clearInterval(interval);
  }, [state]);

  useEffect(() => {
    let spectrumInterval: any;
    if (state === CallState.BRIDGING) {
      spectrumInterval = setInterval(() => {
        // Generate pseudo-audio visualization (simulating voice activity)
        setSpectrum(new Array(16).fill(0).map(() => {
            // Introduce occasional "silence" to simulate pauses in speech
            const isSilent = Math.random() > 0.8; 
            return isSilent ? 4 : 5 + Math.random() * 25;
        }));
      }, 80);
    }
    return () => clearInterval(spectrumInterval);
  }, [state]);

  const formatTime = (s: number) => {
    const mins = Math.floor(s / 60);
    const secs = s % 60;
    return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
  };

  const getStatusColor = () => {
      switch(state) {
          case CallState.BRIDGING: return 'text-green-500';
          case CallState.INCOMING_GSM: return 'text-blue-400';
          case CallState.INCOMING_SIP: return 'text-purple-400';
          case CallState.TERMINATING: return 'text-red-500';
          default: return 'text-gray-400';
      }
  };

  const getStatusText = () => {
    switch(state) {
        case CallState.BRIDGING: return 'RTP BRIDGE ACTIVE';
        case CallState.INCOMING_GSM: return 'INCOMING GSM CALL';
        case CallState.INCOMING_SIP: return 'INCOMING SIP INVITE';
        case CallState.TERMINATING: return 'TERMINATING CALL...';
        default: return 'SIGNALING...';
    }
  };

  return (
    <div className={`bg-[#0a0a0a] border border-white/10 rounded-2xl p-6 relative overflow-hidden transition-all duration-300 ${state === CallState.BRIDGING ? 'shadow-[0_0_30px_rgba(34,197,94,0.1)]' : ''}`}>
      
      {/* Background Pulse Animation for Incoming Calls */}
      {(state === CallState.INCOMING_GSM || state === CallState.INCOMING_SIP) && (
         <div className="absolute inset-0 bg-blue-500/5 animate-pulse" />
      )}

      {/* Header with Call Duration */}
      <div className="text-center relative z-10 space-y-2">
          <p className={`text-[9px] font-black tracking-[0.25em] uppercase ${getStatusColor()}`}>
            {getStatusText()}
          </p>
          <p className="text-4xl font-mono font-black text-white tracking-tighter drop-shadow-2xl">
            {state === CallState.BRIDGING ? formatTime(seconds) : '00:00'}
          </p>
          
          {/* Visual Spectrum Bridge */}
          <div className="flex justify-center items-end gap-[2px] h-8 mt-4 opacity-60">
            {spectrum.map((h, i) => (
              <div 
                key={i} 
                className={`w-1 rounded-t-[1px] transition-all duration-100 ${state === CallState.BRIDGING ? 'bg-green-500' : 'bg-gray-700'}`} 
                style={{ height: `${state === CallState.BRIDGING ? h : 4}px` }} 
              />
            ))}
          </div>
      </div>

      <div className="flex items-center justify-between gap-3 mt-6 relative z-10">
          {/* GSM Leg Info */}
          <div className="flex-1 bg-white/5 p-3 rounded-xl border border-white/5 backdrop-blur-sm">
            <p className="text-[8px] text-gray-500 font-black uppercase mb-1 tracking-widest">GSM ENDPOINT</p>
            <p className="text-xs font-mono font-bold text-gray-200">{call?.gsmNumber || '---'}</p>
            
            {/* Audio Signal Strength Simulator */}
            <div className="flex gap-0.5 mt-2 h-1 w-12">
                {[1,2,3,4].map(i => <div key={i} className={`flex-1 rounded-full ${state === CallState.BRIDGING ? 'bg-green-500 animate-pulse' : 'bg-gray-700'}`} style={{opacity: i*0.25}} />)}
            </div>
          </div>
          
          {/* Direction Indicator */}
          <div className="flex flex-col items-center shrink-0 opacity-50">
             {call?.direction === 'GSM_TO_SIP' ? (
                 <div className="flex flex-col gap-1">
                     <div className="w-1 h-1 bg-white rounded-full animate-bounce" style={{ animationDelay: '0s' }} />
                     <div className="w-1 h-1 bg-white rounded-full animate-bounce" style={{ animationDelay: '0.1s' }} />
                     <div className="w-1 h-1 bg-white rounded-full animate-bounce" style={{ animationDelay: '0.2s' }} />
                 </div>
             ) : (
                <div className="flex flex-col gap-1">
                     <div className="w-1 h-1 bg-white rounded-full animate-bounce" style={{ animationDelay: '0.2s' }} />
                     <div className="w-1 h-1 bg-white rounded-full animate-bounce" style={{ animationDelay: '0.1s' }} />
                     <div className="w-1 h-1 bg-white rounded-full animate-bounce" style={{ animationDelay: '0s' }} />
                 </div>
             )}
          </div>

          {/* SIP Leg Info */}
          <div className="flex-1 bg-white/5 p-3 rounded-xl border border-white/5 text-right backdrop-blur-sm">
            <p className="text-[8px] text-gray-500 font-black uppercase mb-1 tracking-widest">SIP TRUNK</p>
            <p className="text-xs font-mono font-bold truncate text-gray-200">{call?.sipAddress || '---'}</p>
             <div className="flex gap-0.5 mt-2 h-1 w-12 ml-auto justify-end">
                {[1,2,3,4].map(i => <div key={i} className={`flex-1 rounded-full ${state === CallState.BRIDGING ? 'bg-blue-500 animate-pulse' : 'bg-gray-700'}`} style={{opacity: i*0.25}} />)}
            </div>
          </div>
      </div>

      <div className="mt-6">
        <div className="grid grid-cols-3 gap-2 mb-2 text-[8px] font-mono text-gray-500 text-center uppercase">
            <div>
                <span className="block text-gray-300 font-bold">{call?.audioMetrics.jitter || 0}ms</span>
                Jitter
            </div>
             <div>
                <span className="block text-gray-300 font-bold">{call?.audioMetrics.latency || 0}ms</span>
                Latency
            </div>
             <div>
                <span className="block text-gray-300 font-bold">{call?.audioMetrics.packetLoss?.toFixed(1) || 0}%</span>
                Loss
            </div>
        </div>

        <button 
          onClick={onHangup}
          className="w-full py-4 bg-red-600/10 hover:bg-red-600 hover:text-white border border-red-600/30 text-red-500 rounded-xl font-black text-[10px] uppercase tracking-[0.2em] shadow-lg transition-all active:scale-[0.98] flex items-center justify-center gap-2 group"
        >
          <div className="w-2 h-2 bg-red-500 rounded-full group-hover:bg-white" />
          Terminate Channel
        </button>
      </div>
    </div>
  );
};

export default CallView;
