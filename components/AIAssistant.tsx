
import React, { useState } from 'react';
import { GoogleGenerativeAI } from "@google/generative-ai";
import { LogEntry, BackendMetrics } from '../types';

interface AIAssistantProps {
  logs: LogEntry[];
  metrics: BackendMetrics;
}

const AIAssistant: React.FC<AIAssistantProps> = ({ logs, metrics }) => {
  const [response, setResponse] = useState<string>('');
  const [loading, setLoading] = useState(false);

  const runDiagnostics = async () => {
    setLoading(true);
    try {
      const apiKey = process.env.GEMINI_API_KEY || '';
      if (!apiKey) {
          throw new Error("Missing API Key");
      }
      
      const genAI = new GoogleGenerativeAI(apiKey);
      const model = genAI.getGenerativeModel({ model: "gemini-1.5-flash" });

      const logContext = logs.slice(0, 15).map(l => `${l.timestamp} [${l.tag}] ${l.message}`).join('\n');
      const systemPrompt = `Analyze this Dual-SIM GSM-SIP Gateway. 
      Hardware: Qualcomm Rooted. 
      SIM1: ${metrics.sims[0].radioSignal}dBm (${metrics.sims[0].carrier}).
      SIM2: ${metrics.sims[1].radioSignal}dBm (${metrics.sims[1].carrier}).
      CPU: ${metrics.cpuUsage.toFixed(1)}%, Temp: ${metrics.temp.toFixed(1)}C.
      
      Review the provided logs for crosstalk, registration race conditions, or baseband stalling.
      Output a 2-sentence technical audit focusing on multi-channel stability.
      
      Logs:
      ${logContext}`;

      const result = await model.generateContent(systemPrompt);
      const res = await result.response;
      setResponse(res.text() || 'Audit engine returned empty results.');
    } catch (e: any) {
      console.error(e);
      setResponse(`AI TOC Analyzer is temporarily unavailable: ${e.message}`);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="bg-gradient-to-br from-blue-600/10 to-transparent border border-blue-500/20 rounded-2xl overflow-hidden mb-8 shadow-lg shadow-blue-500/5">
      <div className="p-4 bg-blue-500/10 border-b border-blue-500/20 flex justify-between items-center">
        <div className="flex items-center gap-2">
            <div className="w-1.5 h-1.5 bg-blue-400 rounded-full animate-pulse" />
            <span className="text-[10px] font-black text-blue-400 tracking-[0.2em] uppercase">DUAL-CHANNEL AUDIT ENGINE</span>
        </div>
      </div>
      <div className="p-5 space-y-4">
        {response ? (
          <div className="animate-in fade-in slide-in-from-top-2 duration-500">
            <p className="text-xs text-blue-100/90 leading-relaxed font-mono italic border-l-2 border-blue-500/40 pl-4 py-1">
              "{response}"
            </p>
          </div>
        ) : (
          <p className="text-[10px] text-gray-600 font-medium">
            Analyzer ready to scan SIM1/SIM2 signaling sequences and SOC thermal loads.
          </p>
        )}
        <button 
          onClick={runDiagnostics}
          disabled={loading}
          className={`w-full py-3 rounded-xl border border-blue-500/30 bg-blue-500/5 text-blue-400 text-[10px] font-black uppercase tracking-[0.2em] transition-all ${loading ? 'opacity-30 cursor-not-allowed' : 'hover:bg-blue-500 hover:text-white'}`}
        >
          {loading ? 'Analyzing Multi-Path Logs...' : 'Execute Dual-SIM Audit'}
        </button>
      </div>
    </div>
  );
};

export default AIAssistant;
