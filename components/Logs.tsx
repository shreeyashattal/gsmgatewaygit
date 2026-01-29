import React, { useState } from 'react';
import { LogEntry } from '../types';
import { ICONS } from '../constants';

interface LogsProps {
  logs: LogEntry[];
}

type LogLevel = 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';
type LogTag = 'ALL' | 'SIP' | 'GSM' | 'AUDIO' | 'RTP' | 'ERROR';

/**
 * Real-time Event Log Viewer
 * Shows SIP signaling, GSM events, audio bridge, and errors
 */
const Logs: React.FC<LogsProps> = ({ logs }) => {
  const [filter, setFilter] = useState<LogTag>('ALL');
  const [levelFilter, setLevelFilter] = useState<LogLevel[]>(['ERROR', 'WARN', 'INFO']);
  const [showTimestamp, setShowTimestamp] = useState(true);

  // Map tags to colors
  const tagColors: Record<string, string> = {
    'SIP': 'text-blue-400 bg-blue-500/10',
    'GSM': 'text-green-400 bg-green-500/10',
    'AUDIO': 'text-purple-400 bg-purple-500/10',
    'RTP': 'text-cyan-400 bg-cyan-500/10',
    'CALL': 'text-amber-400 bg-amber-500/10',
    'ERROR': 'text-red-400 bg-red-500/10',
    'BRIDGE': 'text-pink-400 bg-pink-500/10',
  };

  const levelColors: Record<LogLevel, string> = {
    'ERROR': 'text-red-400',
    'WARN': 'text-amber-400',
    'INFO': 'text-green-400',
    'DEBUG': 'text-blue-400'
  };

  // Filter logs
  const filteredLogs = logs.filter(log => {
    // Level filter
    if (!levelFilter.includes(log.level as LogLevel)) return false;

    // Tag filter
    if (filter === 'ALL') return true;
    return log.tag.includes(filter);
  });

  const getTagConfig = (tag: string) => {
    for (const [key, color] of Object.entries(tagColors)) {
      if (tag.includes(key)) return color;
    }
    return 'text-gray-400 bg-gray-500/10';
  };

  return (
    <div className="space-y-4 pb-24">
      {/* Header and Controls */}
      <div className="space-y-3">
        <h1 className="text-xl font-black text-white tracking-tight">Event Log</h1>

        {/* Filters */}
        <div className="flex flex-col gap-3">
          {/* Level Filters */}
          <div className="flex gap-2 flex-wrap">
            {(['ERROR', 'WARN', 'INFO', 'DEBUG'] as LogLevel[]).map((level) => (
              <button
                key={level}
                onClick={() =>
                  setLevelFilter(
                    levelFilter.includes(level)
                      ? levelFilter.filter((l) => l !== level)
                      : [...levelFilter, level]
                  )
                }
                className={`px-3 py-1.5 text-xs font-bold uppercase tracking-widest rounded transition-all ${
                  levelFilter.includes(level)
                    ? `${levelColors[level]} border ${levelColors[level].replace('text', 'border')}`
                    : 'text-gray-500 border border-gray-500/20'
                }`}
              >
                [{level}]
              </button>
            ))}
          </div>

          {/* Tag Filters */}
          <div className="flex gap-2 flex-wrap">
            {(['ALL', 'SIP', 'GSM', 'AUDIO', 'RTP', 'CALL', 'ERROR'] as LogTag[]).map((tag) => (
              <button
                key={tag}
                onClick={() => setFilter(tag)}
                className={`px-3 py-1.5 text-xs font-bold uppercase tracking-widest rounded transition-all ${
                  filter === tag
                    ? `${getTagConfig(tag)} border`
                    : 'text-gray-500 border border-gray-500/20 hover:border-gray-400'
                }`}
              >
                {tag === 'ALL' ? '📋 All' : tag}
              </button>
            ))}
          </div>

          {/* Options */}
          <div className="flex gap-3 items-center">
            <label className="flex items-center gap-2 cursor-pointer text-xs">
              <input
                type="checkbox"
                checked={showTimestamp}
                onChange={(e) => setShowTimestamp(e.target.checked)}
                className="w-3 h-3 rounded cursor-pointer accent-blue-500"
              />
              <span className="text-gray-400">Show Timestamps</span>
            </label>

            <div className="text-[9px] text-gray-500 ml-auto">
              Showing {filteredLogs.length} of {logs.length} events
            </div>
          </div>
        </div>
      </div>

      {/* Log Viewer */}
      <div className="bg-[#050505] border border-white/10 rounded-2xl overflow-hidden">
        <div className="max-h-[600px] overflow-y-auto space-y-0 font-mono text-[9px]">
          {filteredLogs.length > 0 ? (
            filteredLogs.map((log) => {
              const tagConfig = getTagConfig(log.tag);
              const levelColor = levelColors[log.level as LogLevel];

              return (
                <div
                  key={log.id}
                  className="px-4 py-2 hover:bg-white/5 border-b border-white/5 transition-colors group flex gap-3 items-start"
                >
                  {/* Timestamp */}
                  {showTimestamp && (
                    <span className="text-gray-700 shrink-0 font-mono">
                      {log.timestamp}
                    </span>
                  )}

                  {/* Level Badge */}
                  <span
                    className={`shrink-0 w-10 font-bold text-center ${levelColor}`}
                  >
                    [{log.level[0]}]
                  </span>

                  {/* Tag Badge */}
                  <span
                    className={`shrink-0 px-2 py-0.5 rounded font-bold uppercase text-[8px] tracking-widest ${tagConfig}`}
                  >
                    {log.tag}
                  </span>

                  {/* Message */}
                  <span className="text-gray-300 break-all flex-1 leading-relaxed">
                    {log.message}
                  </span>
                </div>
              );
            })
          ) : (
            <div className="text-center py-16 text-gray-700 animate-pulse">
              <ICONS.Phone className="w-8 h-8 mx-auto mb-2 opacity-30" />
              <p className="text-[10px] uppercase tracking-[0.2em]">Waiting for events...</p>
            </div>
          )}
        </div>
      </div>

      {/* Info Banner */}
      <div className="bg-blue-500/5 border border-blue-500/20 rounded-xl p-4 space-y-2">
        <p className="text-[10px] font-bold text-blue-400 uppercase tracking-widest">💡 Event Types</p>
        <div className="grid grid-cols-2 gap-2 text-[8px] text-gray-500">
          <div>🔵 <span className="text-blue-400">SIP</span> - SIP signaling (REGISTER, INVITE, BYE)</div>
          <div>🟢 <span className="text-green-400">GSM</span> - GSM/cellular call events</div>
          <div>🟣 <span className="text-purple-400">AUDIO</span> - Audio routing events (tinycap, tinyplay)</div>
          <div>🔷 <span className="text-cyan-400">RTP</span> - RTP stream statistics</div>
          <div>🟡 <span className="text-amber-400">CALL</span> - Call state changes</div>
          <div>🔴 <span className="text-red-400">ERROR</span> - Errors and warnings</div>
        </div>
      </div>
    </div>
  );
};

export default Logs;
