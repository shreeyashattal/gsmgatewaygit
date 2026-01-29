import React from 'react';
import { SIGNAL_QUALITY_CONFIG } from '../../constants';

interface SignalIndicatorProps {
  signalDbm: number;
  size?: 'sm' | 'md' | 'lg';
  showLabel?: boolean;
  showValue?: boolean;
}

export const SignalIndicator: React.FC<SignalIndicatorProps> = ({ 
  signalDbm, 
  size = 'md',
  showLabel = true,
  showValue = true
}) => {
  const getQualityConfig = (signal: number) => {
    if (signal >= SIGNAL_QUALITY_CONFIG.EXCELLENT.threshold) return SIGNAL_QUALITY_CONFIG.EXCELLENT;
    if (signal >= SIGNAL_QUALITY_CONFIG.STABLE.threshold) return SIGNAL_QUALITY_CONFIG.STABLE;
    if (signal >= SIGNAL_QUALITY_CONFIG.FAIR.threshold) return SIGNAL_QUALITY_CONFIG.FAIR;
    return SIGNAL_QUALITY_CONFIG.POOR;
  };

  const quality = getQualityConfig(signalDbm);

  // Convert signal (-110 to -50) to 0-5 bars
  const bars = Math.max(0, Math.min(5, Math.floor((signalDbm + 110) / 12)));

  const barHeights = {
    sm: 'h-3',
    md: 'h-4',
    lg: 'h-5'
  };

  const labelSizeClasses = {
    sm: 'text-[8px]',
    md: 'text-[10px]',
    lg: 'text-xs'
  };

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-end gap-[2px]">
        {[1, 2, 3, 4, 5].map((b) => (
          <div
            key={b}
            className={`rounded-t transition-all duration-300 ${barHeights[size]} ${
              b <= bars 
                ? (bars > 3 ? 'bg-green-500 shadow-[0_0_8px_rgba(34,197,94,0.6)]' : 'bg-amber-500 shadow-[0_0_8px_rgba(245,158,11,0.4)]')
                : 'bg-white/5'
            }`}
            style={{ 
              width: size === 'sm' ? '3px' : size === 'md' ? '4px' : '5px'
            }}
          />
        ))}
      </div>
      {showLabel && (
        <div className="space-y-0.5">
          <span className={`font-bold uppercase tracking-widest ${quality.color} ${labelSizeClasses[size]}`}>
            {quality.label}
          </span>
          {showValue && (
            <span className={`font-mono text-gray-400 ${labelSizeClasses[size]}`}>
              {signalDbm > -110 ? `${signalDbm} dBm` : 'N/A'}
            </span>
          )}
        </div>
      )}
    </div>
  );
};
