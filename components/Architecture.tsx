import React from 'react';

const Architecture: React.FC = () => {
  return (
    <div className="space-y-8 pb-32 text-gray-300">
      <section className="bg-[#111] border border-gray-800 rounded-2xl p-6 shadow-2xl relative overflow-hidden">
        <div className="absolute top-0 right-0 p-4">
           <div className="px-2 py-1 bg-green-500/10 border border-green-500/30 text-green-400 text-[8px] font-black rounded uppercase">Production Blueprint v2.4</div>
        </div>
        <h2 className="text-lg font-black text-white mb-4 border-b border-gray-800 pb-2 flex items-center gap-3">
          <div className="w-2 h-2 bg-blue-500 rounded-full animate-pulse" />
          SYSTEM TOPOLOGY
        </h2>
        <div className="space-y-6 font-mono text-[10px]">
          <div className="flex items-start gap-4">
            <div className="w-24 shrink-0 text-blue-400 font-black">[AUDIO]</div>
            <div>
              <p className="text-white font-bold uppercase mb-1">SoC-Level PCM Bridging</p>
              <p className="opacity-70 leading-relaxed">
                Utilizes <code>tinymix</code> (Snapdragon) or <code>amix</code> (MediaTek) to bypass 
                the Android AudioServer. Direct kernel-space loopback ensures zero-jitter bridging 
                between the Modem Baseband and the RTP signaling engine.
              </p>
            </div>
          </div>
          <div className="flex items-start gap-4">
            <div className="w-24 shrink-0 text-orange-400 font-black">[ROOT]</div>
            <div>
              <p className="text-white font-bold uppercase mb-1">Binder Transaction Layer</p>
              <p className="opacity-70 leading-relaxed">
                Executes <code>service call telephony</code> directly via root shell to answer, 
                dial, and hang up calls without standard system UI interference.
              </p>
            </div>
          </div>
        </div>
      </section>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <div className="bg-[#111] border border-gray-800 rounded-2xl p-5">
            <h3 className="text-[10px] font-black text-white uppercase tracking-widest mb-4">Qualcomm Mixer Logic</h3>
            <div className="bg-black/80 p-3 rounded font-mono text-[8px] text-blue-400 border border-gray-800 overflow-x-auto">
                tinymix 'Voice RX' 'AFE_LOOPBACK_TX'<br/>
                tinymix 'AFE_LOOPBACK_RX' 'Voice TX'<br/>
                tinymix 'MultiMedia1 Mixer SLIMBUS_0_TX' 1
            </div>
        </div>
        <div className="bg-[#111] border border-gray-800 rounded-2xl p-5">
            <h3 className="text-[10px] font-black text-white uppercase tracking-widest mb-4">MediaTek Mixer Logic</h3>
            <div className="bg-black/80 p-3 rounded font-mono text-[8px] text-orange-400 border border-gray-800 overflow-x-auto">
                amix 'Modem DL' 'PCM OUT'<br/>
                amix 'PCM IN' 'Modem UL'<br/>
                amix 'Audio_Modem_Bridge' 1
            </div>
        </div>
      </div>

      <section className="bg-blue-600/5 border border-blue-500/20 rounded-2xl p-6">
        <h3 className="text-xs font-black text-white uppercase tracking-widest mb-4">Deployment Checklist</h3>
        <div className="space-y-3">
          <CheckItem label="Magisk / KernelSU binary present in /system/xbin/" />
          <CheckItem label="SELinux set to Permissive via 'setenforce 0'" />
          <CheckItem label="RTP Port Range (10000-20000) allowed in local firewall" />
          <CheckItem label="WakeLock acquired to prevent CPU throttling during standby" />
        </div>
      </section>
    </div>
  );
};

const CheckItem: React.FC<{ label: string }> = ({ label }) => (
  <div className="flex items-center gap-3">
    <div className="w-1.5 h-1.5 rounded-full bg-blue-500" />
    <span className="text-[10px] text-gray-400 font-bold uppercase">{label}</span>
  </div>
);

export default Architecture;