import React from 'react';
import { CALL_DIRECTION_CONFIG, ICONS } from '../../constants';

interface CallDirectionIconProps {
  direction: 'GSM_TO_SIP' | 'SIP_TO_GSM';
  size?: 'sm' | 'md' | 'lg';
  showLabel?: boolean;
}

export const CallDirectionIcon: React.FC<CallDirectionIconProps> = ({ 
  direction, 
  size = 'md',
  showLabel = true
}) => {
  const config = CALL_DIRECTION_CONFIG[direction];

  const sizeClasses = {
    sm: 'w-3 h-3',
    md: 'w-4 h-4',
    lg: 'w-5 h-5'
  };

  const iconSize = {
    sm: 'text-xs',
    md: 'text-sm',
    lg: 'text-lg'
  };

  const labelSizeClasses = {
    sm: 'text-[8px]',
    md: 'text-[10px]',
    lg: 'text-xs'
  };

  const IconComponent = direction === 'GSM_TO_SIP' ? ICONS.ArrowIn : ICONS.ArrowOut;

  return (
    <div className="flex items-center gap-2">
      <div className={`${config.bgColor} p-1.5 rounded ${sizeClasses[size]} flex items-center justify-center`}>
        <IconComponent />
      </div>
      {showLabel && (
        <div className="flex flex-col gap-0.5">
          <span className={`font-bold uppercase tracking-widest ${config.color} ${labelSizeClasses[size]}`}>
            {config.label}
          </span>
          <span className={`text-gray-400 ${labelSizeClasses[size]}`}>
            {config.description}
          </span>
        </div>
      )}
    </div>
  );
};
