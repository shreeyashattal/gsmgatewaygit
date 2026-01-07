
import React from 'react';
import { LogEntry } from '../types';

interface LogsProps {
  logs: LogEntry[];
}

const Logs: React.FC<LogsProps> = ({ logs }) => {
  return (
    <div className="bg-black min-h-full flex flex-col">
      <div className="flex items-center justify-between mb-4">
        <h2 className="text-xs font-bold text-gray-500 tracking-widest uppercase">System Journal</h2>
        <span className="text-[10px] font-mono text-gray-600">FILTER: ALL</span>
      </div>
      
      <div className="flex-1 space-y-1 font-mono text-[10px]">
        {logs.map((log) => (
          <div key={log.id} className="flex gap-3 hover:bg-white/5 p-1 rounded transition-colors group">
            <span className="text-gray-600 shrink-0">{log.timestamp}</span>
            <span className={`shrink-0 w-12 font-bold ${
              log.level === 'ERROR' ? 'text-red-500' : 
              log.level === 'WARN' ? 'text-yellow-500' : 
              log.level === 'DEBUG' ? 'text-blue-500' : 'text-green-500'
            }`}>
              [{log.level}]
            </span>
            <span className="text-gray-400 shrink-0 uppercase opacity-60">[{log.tag}]</span>
            <span className="text-gray-300 break-all">{log.message}</span>
          </div>
        ))}
        {logs.length === 0 && (
          <div className="text-center py-20 text-gray-700 animate-pulse uppercase tracking-[0.3em]">
            Waiting for events...
          </div>
        )}
      </div>
    </div>
  );
};

export default Logs;
