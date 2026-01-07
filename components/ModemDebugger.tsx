
import React, { useState, useEffect, useRef } from 'react';

const ModemDebugger: React.FC = () => {
  const [atLog, setAtLog] = useState<string[]>([]);
  const scrollRef = useRef<HTMLDivElement>(null);

  const addAtLog = (cmd: string, resp: string) => {
    setAtLog(prev => [...prev, `TX: ${cmd}`, `RX: ${resp}`].slice(-30));
  };

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [atLog]);

  useEffect(() => {
    const interval = setInterval(() => {
      const commands = [
        ['AT+CSQ', '+CSQ: 24,99'],
        ['AT+CREG?', '+CREG: 0,1'],
        ['AT+COPS?', '+COPS: 0,0,"Vodafone UK",7'],
        ['AT+CPSI?', '+CPSI: LTE,Online,234-15,0x4C53,1042...']
      ];
      const [cmd, resp] = commands[Math.floor(Math.random() * commands.length)];
      addAtLog(cmd, resp);
    }, 4000);
    return () => clearInterval(interval);
  }, []);

  return (
    <div className="space-y-6">
      <div className="bg-[#0a0a0a] border border-gray-800 rounded-2xl overflow-hidden shadow-2xl">
        <div className="p-4 bg-gray-900/50 border-b border-gray-800 flex justify-between items-center">
            <h2 className="text-xs font-bold text-white tracking-widest uppercase">Radio Interface Layer (RIL)</h2>
            <div className="px-2 py-0.5 bg-orange-500/10 border border-orange-500/30 text-orange-400 text-[8px] font-black rounded uppercase">RAW SERIAL</div>
        </div>
        
        <div ref={scrollRef} className="p-4 h-64 overflow-y-auto font-mono text-[9px] space-y-1 bg-black/40">
          {atLog.map((line, i) => (
            <div key={i} className={line.startsWith('TX:') ? 'text-blue-500' : 'text-green-500'}>
              {line}
            </div>
          ))}
          <div className="animate-pulse text-gray-700">_</div>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div className="bg-[#111] border border-gray-800 rounded-xl p-4">
            <p className="text-[8px] text-gray-500 font-bold uppercase mb-2">Modem Status</p>
            <div className="space-y-2">
                <StatusItem label="State" value="LTE_ACTIVE" />
                <StatusItem label="MCC/MNC" value="234/15" />
                <StatusItem label="Band" value="B3 (1800MHz)" />
            </div>
        </div>
        <div className="bg-[#111] border border-gray-800 rounded-xl p-4">
            <p className="text-[8px] text-gray-500 font-bold uppercase mb-2">Sim Status</p>
            <div className="space-y-2">
                <StatusItem label="Type" value="USIM_v3" />
                <StatusItem label="PIN" value="READY" />
                <StatusItem label="SMSC" value="+447785016005" />
            </div>
        </div>
      </div>

      <div className="bg-orange-500/5 border border-orange-500/20 rounded-xl p-4">
        <p className="text-[9px] font-bold text-orange-400 uppercase tracking-widest mb-2">Engine Warning</p>
        <p className="text-[10px] text-gray-500 leading-relaxed italic">
          "Direct RIL access detected. Modification of Radio state may result in carrier-side blacklisting if timing violates network protocol."
        </p>
      </div>
    </div>
  );
};

const StatusItem: React.FC<{ label: string; value: string }> = ({ label, value }) => (
  <div className="flex justify-between items-center">
    <span className="text-[9px] text-gray-600 font-bold uppercase">{label}</span>
    <span className="text-[9px] text-gray-300 font-mono font-bold">{value}</span>
  </div>
);

export default ModemDebugger;
