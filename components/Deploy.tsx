
import React, { useState } from 'react';
import { GatewayConfig, LogEntry } from '../types';
import { ICONS } from '../constants';

interface DeployProps {
  config: GatewayConfig;
  logs: LogEntry[];
}

const Deploy: React.FC<DeployProps> = ({ config, logs }) => {
  const [showManifest, setShowManifest] = useState(false);

  return (
    <div className="space-y-6 animate-in fade-in duration-500 pb-20">
      <section className="bg-[#111] border border-blue-500/30 rounded-2xl overflow-hidden shadow-2xl">
        <div className="p-5 bg-blue-500/10 border-b border-blue-500/20 flex items-center gap-3">
          <ICONS.Shield />
          <h2 className="text-sm font-black text-white uppercase tracking-widest">Native Handoff & APK Blueprint</h2>
        </div>

        <div className="p-6 space-y-6">
          <p className="text-xs text-gray-400 leading-relaxed italic">
            This React environment serves as the <strong>Control Plane</strong>. To generate the physical <code>.apk</code>, you must port these specifications into an Android Studio project using the following parameters:
          </p>

          <div className="space-y-4">
            <button 
              onClick={() => setShowManifest(!showManifest)}
              className="w-full py-3 bg-gray-900 border border-gray-800 rounded-xl text-[10px] font-black uppercase tracking-widest text-blue-400 hover:bg-gray-800 transition-colors"
            >
              {showManifest ? 'Hide Build Specs' : 'Show AndroidManifest.xml Specs'}
            </button>

            {showManifest && (
              <div className="bg-black p-4 rounded-xl border border-gray-800 animate-in slide-in-from-top-2">
                <p className="text-[10px] text-gray-500 font-bold mb-2 uppercase">Required Permissions (Root Context)</p>
                <pre className="text-[9px] font-mono text-green-500/80 leading-tight overflow-x-auto">
{`<!-- AndroidManifest.xml -->
<uses-permission name="android.permission.MODIFY_PHONE_STATE" />
<uses-permission name="android.permission.CALL_PHONE" />
<uses-permission name="android.permission.ACCESS_SUPERUSER" />
<uses-permission name="android.permission.WAKE_LOCK" />

<service 
    android:name=".SipGatewayService"
    android:foregroundServiceType="phoneCall|microPhone"
    android:permission="android.permission.BIND_TELECOM_CONNECTION_SERVICE">
    <intent-filter>
        <action android:name="android.telecom.ConnectionService" />
    </intent-filter>
</service>`}
                </pre>
              </div>
            )}
          </div>

          <div className="bg-blue-600/5 border border-blue-500/20 rounded-xl p-5 space-y-4">
            <h3 className="text-xs font-black text-white uppercase tracking-widest">Final Compilation Path</h3>
            <ol className="space-y-3">
              <li className="flex gap-3 text-[11px] text-gray-300">
                <span className="text-blue-500 font-black">01.</span>
                <span>Copy the <strong>src/services/</strong> logic into a Kotlin <code>IntentService</code>.</span>
              </li>
              <li className="flex gap-3 text-[11px] text-gray-300">
                <span className="text-blue-500 font-black">02.</span>
                <span>Compile <strong>PJSIP 2.14</strong> for Android (arm64-v8a) to handle the SIP/RTP engine.</span>
              </li>
              <li className="flex gap-3 text-[11px] text-gray-300">
                <span className="text-blue-500 font-black">03.</span>
                <span>Use <code>libsu</code> to execute the <code>service call telephony</code> commands defined in NativeBridge.</span>
              </li>
              <li className="flex gap-3 text-[11px] text-gray-300">
                <span className="text-blue-500 font-black">04.</span>
                <span>Build → Build Bundle/APK → Debug/Release APK.</span>
              </li>
            </ol>
          </div>

          <div className="pt-4">
            <div className="flex items-center gap-4 p-4 bg-orange-500/10 border border-orange-500/30 rounded-xl">
               <div className="w-10 h-10 bg-orange-500 rounded-lg flex items-center justify-center shrink-0">
                  <ICONS.Shield />
               </div>
               <div>
                  <p className="text-[10px] font-black text-orange-400 uppercase">Hardware Restriction</p>
                  <p className="text-[9px] text-gray-500">The <code>su</code> binary must be present in <code>/system/xbin/</code> for this APK to bridge audio legs.</p>
               </div>
            </div>
          </div>
        </div>
      </section>

      <div className="text-center pb-10">
        <p className="text-[10px] text-gray-600 font-bold uppercase tracking-widest">
          Build System: Gradle 8.2 • SDK 34 • Root: Enabled
        </p>
      </div>
    </div>
  );
};

export default Deploy;
