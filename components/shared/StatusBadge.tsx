import React from 'react';
import { SIPRegistrationState } from '../../types';
import { SIP_STATUS_CONFIG } from '../../constants';

interface StatusBadgeProps {
  state: SIPRegistrationState;
  showLabel?: boolean;
  size?: 'sm' | 'md' | 'lg';
  animated?: boolean;
}

export const StatusBadge: React.FC<StatusBadgeProps> = ({ 
  state, 
  showLabel = true, 
  size = 'md',
  animated = true 
}) => {
  const config = SIP_STATUS_CONFIG[state];

  const sizeClasses = {
    sm: 'w-2 h-2',
    md: 'w-3 h-3',
    lg: 'w-4 h-4'
  };

  const labelSizeClasses = {
    sm: 'text-[8px]',
    md: 'text-[10px]',
    lg: 'text-xs'
  };

  const isAnimating = animated && 
    (state === SIPRegistrationState.REGISTERING || 
     state === SIPRegistrationState.UNREGISTERING);

  return (
    <div className="flex items-center gap-2">
      <div className="relative">
        <div 
          className={`${sizeClasses[size]} rounded-full ${config.bgColor} ${isAnimating ? 'animate-pulse' : ''}`}
          style={{
            boxShadow: state === SIPRegistrationState.REGISTERED ? `0 0 12px ${config.color.split('-')[1]}` : 'none'
          }}
        />
      </div>
      {showLabel && (
        <span className={`font-bold uppercase tracking-widest ${config.color} ${labelSizeClasses[size]}`}>
          {config.label}
        </span>
      )}
    </div>
  );
};
