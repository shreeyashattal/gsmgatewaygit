import { GatewayConfig, ChannelConfig } from '../types';

const STORAGE_KEY = 'shreeyash_gateway_config_v2';
const OLD_STORAGE_KEY = 'shreeyash_gateway_config';

export class PersistenceService {
  static saveConfig(config: GatewayConfig) {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(config));
      console.log('[PERSISTENCE] Config saved');
    } catch (e) {
      console.error('[PERSISTENCE] Failed to save config:', e);
    }
  }

  static loadConfig(): GatewayConfig | null {
    try {
      // Try new format first
      const data = localStorage.getItem(STORAGE_KEY);
      if (data) {
        const config = JSON.parse(data);
        if (this.isValidConfig(config)) {
          return config;
        }
      }

      // Try migrating from old format
      const oldData = localStorage.getItem(OLD_STORAGE_KEY);
      if (oldData) {
        const oldConfig = JSON.parse(oldData);
        const migratedConfig = this.migrateOldConfig(oldConfig);
        if (migratedConfig) {
          // Save migrated config and remove old
          this.saveConfig(migratedConfig);
          localStorage.removeItem(OLD_STORAGE_KEY);
          return migratedConfig;
        }
      }

      return null;
    } catch (e) {
      console.error('[PERSISTENCE] Failed to load config:', e);
      return null;
    }
  }

  private static isValidConfig(config: any): config is GatewayConfig {
    return config &&
      Array.isArray(config.channels) &&
      config.channels.length === 2 &&
      typeof config.autoAnswer === 'boolean';
  }

  private static migrateOldConfig(oldConfig: any): GatewayConfig | null {
    try {
      // Old config had 'trunks' with SIP settings
      // New config has 'channels' with Asterisk settings
      const channels: [ChannelConfig, ChannelConfig] = [
        this.createDefaultChannel(1),
        this.createDefaultChannel(2)
      ];

      // Try to preserve some settings from old config
      if (oldConfig.trunks) {
        for (let i = 0; i < 2; i++) {
          const trunk = oldConfig.trunks[i];
          if (trunk) {
            channels[i].enabled = trunk.serviceActive ?? (i === 0);
            channels[i].codec = trunk.codec || 'PCMU';
            // Map old sipUser (which was context) to asteriskContext
            if (trunk.sipUser) {
              channels[i].asteriskContext = trunk.sipUser;
            }
          }
        }
      }

      return {
        channels,
        autoAnswer: oldConfig.autoAnswer ?? true,
        rootLevel: oldConfig.rootLevel ?? true,
        jitterBufferMs: oldConfig.jitterBufferMs ?? 60,
        keepAliveInterval: oldConfig.keepAliveInterval ?? 30,
        speakerphoneOn: oldConfig.speakerphoneOn ?? false
      };
    } catch (e) {
      console.error('[PERSISTENCE] Migration failed:', e);
      return null;
    }
  }

  private static createDefaultChannel(id: number): ChannelConfig {
    return {
      enabled: id === 1,
      asteriskContext: `from-gsm${id}`,
      defaultExtension: 's',
      codec: 'PCMU',
      rtpPort: id === 1 ? 5004 : 5006
    };
  }
}
