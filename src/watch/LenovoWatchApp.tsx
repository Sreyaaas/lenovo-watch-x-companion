import React, { useState, useEffect } from 'react';
import {
  Watch,
  Bluetooth,
  Battery,
  Clock,
  Compass,
  Bell,
  Activity,
  AlarmClock,
  Settings,
  Terminal,
  ShieldCheck,
  RefreshCw,
  Sparkles,
  Smartphone,
  ExternalLink,
  Volume2,
  CheckCircle,
} from 'lucide-react';
import { bleManager, ConnectionState } from './bleManager';
import { DeviceTelemetry } from './protocol';
import { InteractiveHandCalibrator } from './components/InteractiveHandCalibrator';
import { OledNotificationStudio } from './components/OledNotificationStudio';
import { HealthTelemetryPanel } from './components/HealthTelemetryPanel';
import { HardwareAlarmManager } from './components/HardwareAlarmManager';
import { PacketConsoleModal } from './components/PacketConsoleModal';
import { WatchSettingsModal } from './components/WatchSettingsModal';

type ActiveTab = 'calibration' | 'notifications' | 'health' | 'alarms';

export const LenovoWatchApp: React.FC = () => {
  const [connState, setConnState] = useState<ConnectionState>(bleManager.state);
  const [telemetry, setTelemetry] = useState<DeviceTelemetry>(bleManager.telemetry);
  const [activeTab, setActiveTab] = useState<ActiveTab>('calibration');
  const [showConsole, setShowConsole] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [isSyncingClock, setIsSyncingClock] = useState(false);
  const [syncNotice, setSyncNotice] = useState<string | null>(null);
  const [phoneAlarmActive, setPhoneAlarmActive] = useState(false);

  useEffect(() => {
    const handleState = (state: ConnectionState) => setConnState(state);
    const handleTelemetry = (t: DeviceTelemetry) => setTelemetry({ ...t });
    const handleFindPhone = () => setPhoneAlarmActive(true);

    bleManager.on('state', handleState);
    bleManager.on('telemetry', handleTelemetry);
    bleManager.on('findPhone', handleFindPhone);

    return () => {
      bleManager.off('state', handleState);
      bleManager.off('telemetry', handleTelemetry);
      bleManager.off('findPhone', handleFindPhone);
    };
  }, []);

  const handleConnect = async () => {
    try {
      await bleManager.connect();
    } catch (err: any) {
      if (err.name !== 'NotFoundError') {
        alert(`Bluetooth connection error: ${err.message}`);
      }
    }
  };

  const handleStartSim = () => {
    bleManager.startSimulation();
  };

  const handleDisconnect = () => {
    bleManager.disconnect();
  };

  const handleAtomicSync = async () => {
    try {
      setIsSyncingClock(true);
      await bleManager.syncTime(new Date());
      setSyncNotice('Clock synced to atomic time!');
      setTimeout(() => setSyncNotice(null), 3000);
    } catch (err: any) {
      alert(`Sync failed: ${err.message}`);
    } finally {
      setIsSyncingClock(false);
    }
  };

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 font-sans antialiased selection:bg-cyan-500 selection:text-slate-950 pb-16">
      {/* Top Banner Navigation */}
      <header className="sticky top-0 z-40 bg-slate-950/80 backdrop-blur-xl border-b border-slate-800/80 px-4 lg:px-8 py-3.5">
        <div className="max-w-7xl mx-auto flex flex-col sm:flex-row items-center justify-between gap-4">
          {/* Logo & Identity */}
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-tr from-cyan-600 to-blue-500 p-0.5 shadow-lg shadow-cyan-500/20">
              <div className="w-full h-full bg-slate-950 rounded-[10px] flex items-center justify-center text-cyan-400">
                <Watch className="w-5 h-5" />
              </div>
            </div>
            <div>
              <div className="flex items-center gap-2">
                <span className="font-extrabold tracking-tight text-white text-base">CHRONOS X</span>
                <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-cyan-950 text-cyan-300 border border-cyan-800 font-semibold">
                  LOCAL ONLY
                </span>
              </div>
              <p className="text-[11px] text-slate-400">
                Lenovo Watch X / X Plus Companion Studio
              </p>
            </div>
          </div>

          {/* Device Status & Controls */}
          <div className="flex flex-wrap items-center gap-2.5">
            {connState === 'connected' && (
              <div className="flex items-center gap-2 px-3 py-1.5 rounded-xl bg-emerald-950/40 border border-emerald-500/30 text-xs font-mono text-emerald-300">
                <span className="w-2 h-2 rounded-full bg-emerald-400 animate-ping" />
                <span>ONLINE · BLE</span>
                <span className="text-slate-500">|</span>
                <Battery className="w-3.5 h-3.5 text-emerald-400" />
                <span>{telemetry.battery.levelPercent}%</span>
                <span className="text-slate-500">|</span>
                <span className="text-slate-400">v{telemetry.firmwareVersion}</span>
              </div>
            )}

            {connState === 'simulated' && (
              <div className="flex items-center gap-2 px-3 py-1.5 rounded-xl bg-purple-950/40 border border-purple-500/30 text-xs font-mono text-purple-300">
                <span className="w-2 h-2 rounded-full bg-purple-400" />
                <span>SIMULATED WATCH</span>
                <span className="text-slate-500">|</span>
                <Battery className="w-3.5 h-3.5 text-purple-400" />
                <span>{telemetry.battery.levelPercent}%</span>
              </div>
            )}

            {/* Atomic Clock Sync */}
            {(connState === 'connected' || connState === 'simulated') && (
              <button
                onClick={handleAtomicSync}
                disabled={isSyncingClock}
                className="px-3 py-1.5 rounded-xl bg-slate-900 hover:bg-slate-800 text-xs text-cyan-400 border border-slate-800 hover:border-cyan-500/30 transition-all flex items-center gap-1.5 active:scale-95"
                title="Synchronize watch clock with atomic device time"
              >
                <RefreshCw className={`w-3.5 h-3.5 ${isSyncingClock ? 'animate-spin' : ''}`} />
                {isSyncingClock ? 'Syncing...' : 'Sync Clock'}
              </button>
            )}

            {/* Connection Actions */}
            {connState === 'disconnected' ? (
              <div className="flex items-center gap-2">
                <button
                  onClick={handleConnect}
                  className="px-4 py-2 rounded-xl text-xs font-semibold bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white shadow-lg shadow-cyan-500/25 active:scale-95 transition-all flex items-center gap-1.5"
                >
                  <Bluetooth className="w-3.5 h-3.5" /> Connect Watch (BLE)
                </button>
                <button
                  onClick={handleStartSim}
                  className="px-3 py-2 rounded-xl text-xs font-semibold bg-slate-900 hover:bg-slate-800 text-slate-300 border border-slate-800 transition-colors"
                >
                  Simulation Mode
                </button>
              </div>
            ) : (
              <button
                onClick={handleDisconnect}
                className="px-3 py-1.5 rounded-xl text-xs text-red-400 hover:bg-red-950/30 border border-red-900/40 transition-colors"
              >
                Disconnect
              </button>
            )}

            {/* Tool buttons */}
            <button
              onClick={() => setShowConsole(true)}
              className="p-2 rounded-xl bg-slate-900 hover:bg-slate-800 text-slate-300 border border-slate-800 transition-colors"
              title="Open Bluetooth Packet Inspector"
            >
              <Terminal className="w-4 h-4" />
            </button>
            <button
              onClick={() => setShowSettings(true)}
              className="p-2 rounded-xl bg-slate-900 hover:bg-slate-800 text-slate-300 border border-slate-800 transition-colors"
              title="Watch Hardware Settings"
            >
              <Settings className="w-4 h-4" />
            </button>
          </div>
        </div>
      </header>

      {/* Sync Toast Alert */}
      {syncNotice && (
        <div className="max-w-md mx-auto my-3 px-4 py-2.5 rounded-xl bg-cyan-950/80 border border-cyan-500/40 text-cyan-200 text-xs font-mono flex items-center justify-center gap-2 shadow-xl animate-in slide-in-from-top-2">
          <CheckCircle className="w-4 h-4 text-cyan-400" /> {syncNotice}
        </div>
      )}

      {/* Reverse Engineering Banner Callout */}
      <div className="max-w-7xl mx-auto px-4 lg:px-8 mt-6">
        <div className="p-4 rounded-2xl bg-gradient-to-r from-slate-900 via-slate-900/90 to-cyan-950/30 border border-slate-800 flex flex-col md:flex-row items-start md:items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <div className="p-2.5 rounded-xl bg-cyan-500/10 text-cyan-400 border border-cyan-500/20">
              <ShieldCheck className="w-5 h-5" />
            </div>
            <div>
              <div className="text-sm font-semibold text-white">
                Cleanroom Reverse-Engineered Architecture
              </div>
              <p className="text-xs text-slate-400">
                100% cloudless & local. Replaces the abandoned 8-year-old vendor app with zero bloat, native stepper calibration, and raw Web BLE communication.
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <a
              href="#apk-guide"
              className="text-xs font-mono text-cyan-400 hover:text-cyan-300 flex items-center gap-1 px-3 py-1.5 rounded-lg bg-cyan-950/40 border border-cyan-800/40"
            >
              Install Ready-to-Run APK <ExternalLink className="w-3 h-3" />
            </a>
          </div>
        </div>
      </div>

      {/* Navigation Tabs */}
      <div className="max-w-7xl mx-auto px-4 lg:px-8 mt-6">
        <div className="flex border-b border-slate-800/80 gap-2 overflow-x-auto pb-1">
          <button
            onClick={() => setActiveTab('calibration')}
            className={`px-4 py-3 rounded-xl text-xs font-medium font-mono flex items-center gap-2 transition-all ${
              activeTab === 'calibration'
                ? 'bg-slate-900 text-cyan-400 border border-slate-800 shadow-sm'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900/40'
            }`}
          >
            <Compass className="w-4 h-4" /> Mechanical Calibration
          </button>

          <button
            onClick={() => setActiveTab('notifications')}
            className={`px-4 py-3 rounded-xl text-xs font-medium font-mono flex items-center gap-2 transition-all ${
              activeTab === 'notifications'
                ? 'bg-slate-900 text-emerald-400 border border-slate-800 shadow-sm'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900/40'
            }`}
          >
            <Bell className="w-4 h-4" /> OLED Notifications
          </button>

          <button
            onClick={() => setActiveTab('health')}
            className={`px-4 py-3 rounded-xl text-xs font-medium font-mono flex items-center gap-2 transition-all ${
              activeTab === 'health'
                ? 'bg-slate-900 text-rose-400 border border-slate-800 shadow-sm'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900/40'
            }`}
          >
            <Activity className="w-4 h-4" /> Health & Pedometer
          </button>

          <button
            onClick={() => setActiveTab('alarms')}
            className={`px-4 py-3 rounded-xl text-xs font-medium font-mono flex items-center gap-2 transition-all ${
              activeTab === 'alarms'
                ? 'bg-slate-900 text-purple-400 border border-slate-800 shadow-sm'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900/40'
            }`}
          >
            <AlarmClock className="w-4 h-4" /> Hardware Alarms
          </button>
        </div>
      </div>

      {/* Main Tab Panels */}
      <main className="max-w-7xl mx-auto px-4 lg:px-8 mt-6">
        {activeTab === 'calibration' && <InteractiveHandCalibrator />}
        {activeTab === 'notifications' && <OledNotificationStudio />}
        {activeTab === 'health' && <HealthTelemetryPanel />}
        {activeTab === 'alarms' && <HardwareAlarmManager />}

        {/* Technical Architecture & APK Guide Section */}
        <section id="apk-guide" className="mt-12 bg-slate-900/50 border border-slate-800 rounded-2xl p-6">
          <div className="flex items-center gap-3 mb-4">
            <div className="p-2 rounded-xl bg-blue-500/10 text-blue-400 border border-blue-500/20">
              <Smartphone className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-base font-semibold text-white">Direct Android APK Options</h3>
              <p className="text-xs text-slate-400">
                How to install and run a 100% local, no-bloat companion app on your phone right now
              </p>
            </div>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-6 mt-4 text-xs text-slate-300">
            {/* Option 1: Web Bluetooth PWA */}
            <div className="p-4 rounded-xl bg-slate-950 border border-slate-800 space-y-2.5">
              <div className="font-semibold text-cyan-400 text-sm flex items-center gap-1.5">
                <span className="w-5 h-5 rounded-full bg-cyan-950 border border-cyan-800 flex items-center justify-center text-[10px]">
                  1
                </span>
                Option 1: This Progressive Web App (PWA)
              </div>
              <p className="text-slate-400">
                You can run this exact app directly in <strong>Google Chrome</strong> or <strong>Edge</strong> on your Android phone with zero APK compilation.
              </p>
              <ul className="list-disc list-inside space-y-1 text-slate-400">
                <li>Open this page on your Android phone's Chrome browser.</li>
                <li>Tap Chrome Menu (⋮) ➔ <strong>"Add to Home screen" / "Install App"</strong>.</li>
                <li>Tap <strong>"Connect Watch (BLE)"</strong> and select your Lenovo Watch X.</li>
                <li>Works 100% locally with zero cloud or accounts!</li>
              </ul>
            </div>

            {/* Option 2: Pre-built Open Source APK (Gadgetbridge) */}
            <div className="p-4 rounded-xl bg-slate-950 border border-slate-800 space-y-2.5">
              <div className="font-semibold text-emerald-400 text-sm flex items-center gap-1.5">
                <span className="w-5 h-5 rounded-full bg-emerald-950 border border-emerald-800 flex items-center justify-center text-[10px]">
                  2
                </span>
                Option 2: Gadgetbridge (Pre-built Android APK)
              </div>
              <p className="text-slate-400">
                The open-source community already reverse-engineered and merged the full Lenovo Watch X driver into <strong>Gadgetbridge</strong>!
              </p>
              <div className="flex flex-wrap gap-2 pt-1">
                <a
                  href="https://f-droid.org/packages/nodomain.freeyourgadget.gadgetbridge/"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="px-3 py-1.5 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-slate-950 font-semibold transition-colors flex items-center gap-1"
                >
                  Download on F-Droid <ExternalLink className="w-3 h-3" />
                </a>
                <a
                  href="https://codeberg.org/Freeyourgadget/Gadgetbridge/releases"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 transition-colors flex items-center gap-1"
                >
                  GitHub / Codeberg APKs <ExternalLink className="w-3 h-3" />
                </a>
              </div>
              <p className="text-[11px] text-slate-500 pt-1">
                Gadgetbridge has background services to listen for Android system SMS, Phone Calls, WhatsApp notifications, and automatically route them to the watch.
              </p>
            </div>
          </div>
        </section>
      </main>

      {/* Reverse "Find My Phone" Incoming Alert Modal */}
      {phoneAlarmActive && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-red-950/90 backdrop-blur-md animate-bounce">
          <div className="p-8 rounded-3xl bg-slate-950 border-2 border-red-500 shadow-[0_0_50px_rgba(239,68,68,0.5)] text-center max-w-sm">
            <Volume2 className="w-16 h-16 text-red-500 mx-auto mb-4 animate-pulse" />
            <h2 className="text-2xl font-black text-white">WATCH BUTTON TRIGGERED!</h2>
            <p className="text-xs text-red-300 mt-2 font-mono">
              "Find My Phone" signal received from Lenovo Watch X button press (Opcode 0x04 0x03 0x11).
            </p>
            <button
              onClick={() => setPhoneAlarmActive(false)}
              className="mt-6 w-full py-3 rounded-xl bg-red-600 hover:bg-red-500 text-white font-bold text-sm shadow-lg shadow-red-600/30 active:scale-95 transition-all"
            >
              Dismiss Alarm
            </button>
          </div>
        </div>
      )}

      {/* Modals */}
      <PacketConsoleModal isOpen={showConsole} onClose={() => setShowConsole(false)} />
      <WatchSettingsModal isOpen={showSettings} onClose={() => setShowSettings(false)} />
    </div>
  );
};
