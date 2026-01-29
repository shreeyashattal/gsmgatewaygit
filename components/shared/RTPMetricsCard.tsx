import React from 'react';

interface RTPMetricsCardProps {
  jitterMs: number;
  packetLoss: number;
  latencyMs: number;
  bitrateKbps: number;
  packetsRx: number;
  packetsTx: number;
}

export const RTPMetricsCard: React.FC<RTPMetricsCardProps> = ({
  jitterMs,
  packetLoss,
  latencyMs,
  bitrateKbps,
  packetsRx,
  packetsTx
}) => {
  const getQualityColor = (jitter: number, loss: number, latency: number) => {
    if (jitter > 50 || loss > 5 || latency > 150) return 'border-red-500/30 bg-red-500/5';
    if (jitter > 30 || loss > 2 || latency > 100) return 'border-amber-500/30 bg-amber-500/5';
    return 'border-green-500/30 bg-green-500/5';
  };

  const getMetricColor = (value: number, thresholds: { good: number; warning: number; bad: number }) => {
    if (value <= thresholds.good) return 'text-green-400';
    if (value <= thresholds.warning) return 'text-amber-400';
    return 'text-red-400';
  };

  return (
    <div className={`border rounded-2xl p-4 space-y-3 ${getQualityColor(jitterMs, packetLoss, latencyMs)}`}>
      <div className="flex justify-between items-center">
        <h3 className="text-xs font-bold uppercase tracking-widest text-gray-300">RTP Metrics</h3>
        <span className={`text-[10px] font-mono ${getMetricColor(jitterMs, { good: 20, warning: 50, bad: 100 })}`}>
          Quality: {jitterMs <= 20 ? 'EXCELLENT' : jitterMs <= 50 ? 'GOOD' : 'FAIR'}
        </span>
      </div>

      <div className="grid grid-cols-2 gap-3 text-[9px]">
        <div className="space-y-1">
          <p className="text-gray-500 font-bold uppercase tracking-widest">Jitter</p>
          <p className={`font-mono font-bold ${getMetricColor(jitterMs, { good: 20, warning: 50, bad: 100 })}`}>
            {jitterMs}ms
          </p>
        </div>

        <div className="space-y-1">
          <p className="text-gray-500 font-bold uppercase tracking-widest">Loss</p>
          <p className={`font-mono font-bold ${getMetricColor(packetLoss, { good: 1, warning: 2, bad: 5 })}`}>
            {packetLoss.toFixed(2)}%
          </p>
        </div>

        <div className="space-y-1">
          <p className="text-gray-500 font-bold uppercase tracking-widest">Latency</p>
          <p className={`font-mono font-bold ${getMetricColor(latencyMs, { good: 50, warning: 100, bad: 150 })}`}>
            {latencyMs}ms
          </p>
        </div>

        <div className="space-y-1">
          <p className="text-gray-500 font-bold uppercase tracking-widest">Bitrate</p>
          <p className="font-mono font-bold text-blue-400">
            {bitrateKbps}kbps
          </p>
        </div>
      </div>

      <div className="pt-2 border-t border-white/10 flex justify-between text-[8px]">
        <span className="text-gray-500">RX: <span className="text-gray-300 font-mono">{packetsRx}</span></span>
        <span className="text-gray-500">TX: <span className="text-gray-300 font-mono">{packetsTx}</span></span>
      </div>
    </div>
  );
};
