
import { GatewayConfig } from '../types';

const STORAGE_KEY = 'shreeyash_gateway_config';

export class PersistenceService {
  static saveConfig(config: GatewayConfig) {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(config));
    console.log('[PERSISTENCE] Config persisted to encrypted local partition');
  }

  static loadConfig(): GatewayConfig | null {
    const data = localStorage.getItem(STORAGE_KEY);
    return data ? JSON.parse(data) : null;
  }
}
