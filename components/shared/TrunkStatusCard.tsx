import React from 'react';

interface TrunkStatusCardProps {
  simSlot: 0 | 1;
  registered: boolean;
  registering: boolean;
  lastRegisteredTime?: number;
  nextRegisterTime?: number;
  carrier: string;
  signal: number;
  networkType: string;
  phoneNumber: string;
  sipUsername: string;
  pbxHost: string;
  pbxPort: number;
  onRefreshRegister?: () => void;
}

export const TrunkStatusCard: React.FC<TrunkStatusCardProps> = ({
  simSlot,
  registered,
  registering,
  lastRegisteredTime,
  nextRegisterTime,
  carrier,
  signal,
  networkType,
  phoneNumber,
  sipUsername,
  pbxHost,
  pbxPort,
  onRefreshRegister
}) => {
  const formatTime = (timestamp?: number) => {
    if (!timestamp) return 'N/A';
    const date = new Date(timestamp);
    return date.toLocaleTimeString();
  };

  const getStatusColor = () => {
    if (registering) return 'border-amber-500/30 bg-amber-500/5 shadow-amber-500/20';
    if (registered) return 'border-green-500/30 bg-green-500/5 shadow-green-500/20';
    return 'border-red-500/30 bg-red-500/5 shadow-red-500/20';
  };

  return (
    <div className={`border rounded-2xl p-5 space-y-4 shadow-lg transition-all ${getStatusColor()}`}>
      {/* Header */}
      <div className="flex justify-between items-start">
        <div className="space-y-1">
          <div className="flex items-center gap-2">
            <div className={`w-2 h-2 rounded-full ${
              registered ? 'bg-green-500 shadow-[0_0_12px_rgba(34,197,94,0.8)]' :
              registering ? 'bg-amber-500 animate-pulse' :
              'bg-red-500'
            }`} />
            <h3 className="text-xs font-black uppercase tracking-widest text-gray-400">SIM {simSlot + 1} Trunk</h3>
          </div>
          <p className="text-sm font-bold text-white">{carrier || 'Searching...'}</p>
          <p className="text-[10px] font-mono text-blue-400">{phoneNumber}</p>
        </div>
        {onRefreshRegister && (
          <button
            onClick={onRefreshRegister}
            disabled={registering}
            className="px-3 py-1.5 text-[9px] font-bold uppercase tracking-widest bg-blue-600/20 hover:bg-blue-600/30 border border-blue-500/30 rounded text-blue-400 disabled:opacity-50 transition-all"
          >
            Refresh
          </button>
        )}
      </div>

      {/* SIP Registration Status */}
      <div className="bg-black/30 border border-white/5 rounded-xl p-3 space-y-2">
        <div className="flex justify-between items-center text-[9px]">
          <span className="text-gray-500 font-bold uppercase tracking-widest">SIP Status</span>
          <span className={`font-mono font-bold ${
            registered ? 'text-green-400' :
            registering ? 'text-amber-400' :
            'text-red-400'
          }`}>
            {registering ? 'REGISTERING...' : registered ? 'REGISTERED' : 'FAILED'}
          </span>
        </div>
        <div className="flex justify-between items-center text-[9px]">
          <span className="text-gray-500 font-bold uppercase tracking-widest">Last Registered</span>
          <span className="text-gray-300 font-mono text-[8px]">{formatTime(lastRegisteredTime)}</span>
        </div>
        <div className="flex justify-between items-center text-[9px]">
          <span className="text-gray-500 font-bold uppercase tracking-widest">Next Register</span>
          <span className="text-gray-300 font-mono text-[8px]">{formatTime(nextRegisterTime)}</span>
        </div>
      </div>

      {/* Trunk Configuration */}
      <div className="bg-black/30 border border-white/5 rounded-xl p-3 space-y-2">
        <p className="text-[9px] font-bold uppercase tracking-widest text-gray-500">PBX Connection</p>
        <div className="space-y-1">
          <div className="flex justify-between text-[9px]">
            <span className="text-gray-600">Host</span>
            <span className="text-gray-300 font-mono">{pbxHost}:{pbxPort}</span>
          </div>
          <div className="flex justify-between text-[9px]">
            <span className="text-gray-600">Username</span>
            <span className="text-gray-300 font-mono">{sipUsername}</span>
          </div>
          <div className="flex justify-between text-[9px]">
            <span className="text-gray-600">Network</span>
            <span className="text-gray-300 font-mono">{networkType}</span>
          </div>
          <div className="flex justify-between text-[9px]">
            <span className="text-gray-600">Signal</span>
            <span className={`font-mono font-bold ${signal > -85 ? 'text-green-400' : 'text-amber-400'}`}>
              {signal > -110 ? `${signal}dBm` : 'N/A'}
            </span>
          </div>
        </div>
      </div>
    </div>
  );
};
