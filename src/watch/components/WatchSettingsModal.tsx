import React, { useState } from 'react';
import { Settings, X, Moon, Eye, Smartphone, Vibrate, Globe, Clock, Check, BellRing } from 'lucide-react';
import { bleManager } from '../bleManager';

interface WatchSettingsModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const WatchSettingsModal: React.FC<WatchSettingsModalProps> = ({ isOpen, onClose }) => {
  const [liftToWake, setLiftToWake] = useState(true);
  const [dnd, setDnd] = useState(false);
  const [disconnectRemind, setDisconnectRemind] = useState(true);
  const [timeFormat24, setTimeFormat24] = useState(true);
  const [language, setLanguage] = useState<'EN' | 'CN'>('EN');
  const [stepGoal, setStepGoal] = useState(10000);
  const [isFindingWatch, setIsFindingWatch] = useState(false);

  if (!isOpen) return null;

  const handleToggleLiftToWake = async () => {
    const val = !liftToWake;
    setLiftToWake(val);
    try {
      await bleManager.setLiftToWake(val);
    } catch (e) {
      console.error(e);
    }
  };

  const handleToggleDnd = async () => {
    const val = !dnd;
    setDnd(val);
    try {
      await bleManager.setDnd(val);
    } catch (e) {
      console.error(e);
    }
  };

  const handleFindWatch = async () => {
    try {
      setIsFindingWatch(true);
      await bleManager.findMyWatch();
      setTimeout(() => setIsFindingWatch(false), 3000);
    } catch (e: any) {
      alert(e.message);
      setIsFindingWatch(false);
    }
  };

  const handleStepGoalChange = async (val: number) => {
    setStepGoal(val);
    try {
      await bleManager.setDailyStepGoal(val);
    } catch (e) {
      console.error(e);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-sm animate-in fade-in duration-150">
      <div className="w-full max-w-xl bg-slate-950 border border-slate-800 rounded-2xl shadow-2xl overflow-hidden flex flex-col">
        {/* Header */}
        <div className="p-4 border-b border-slate-800 flex items-center justify-between bg-slate-900/60">
          <div className="flex items-center gap-2.5">
            <div className="p-1.5 rounded-lg bg-cyan-500/10 text-cyan-400 border border-cyan-500/20">
              <Settings className="w-4 h-4" />
            </div>
            <div>
              <h3 className="text-sm font-semibold text-white">Watch Hardware Preferences</h3>
              <p className="text-[11px] text-slate-400 font-mono">
                Stored in watch NVRAM (Zero cloud sync)
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-slate-400 hover:text-white hover:bg-slate-800 transition-colors"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Settings Body */}
        <div className="p-6 space-y-5 overflow-y-auto max-h-[70vh]">
          {/* Find Watch Action */}
          <div className="p-4 rounded-xl bg-cyan-950/20 border border-cyan-500/30 flex items-center justify-between">
            <div>
              <div className="text-sm font-semibold text-white flex items-center gap-2">
                <BellRing className="w-4 h-4 text-cyan-400" /> Find My Watch
              </div>
              <div className="text-xs text-slate-400 mt-0.5">
                Causes the watch to vibrate and display alert on OLED screen
              </div>
            </div>
            <button
              onClick={handleFindWatch}
              disabled={isFindingWatch}
              className="px-4 py-2 rounded-xl text-xs font-semibold bg-cyan-500 hover:bg-cyan-400 text-slate-950 active:scale-95 transition-all shadow-md"
            >
              {isFindingWatch ? 'Signaling Watch...' : 'Trigger Vibrate'}
            </button>
          </div>

          {/* Toggle: Lift wrist to wake */}
          <div className="flex items-center justify-between p-3 rounded-xl bg-slate-900/60 border border-slate-800">
            <div className="flex items-center gap-3">
              <Eye className="w-5 h-5 text-cyan-400" />
              <div>
                <div className="text-sm font-medium text-white">Tilt-to-Wake Display</div>
                <div className="text-xs text-slate-400">Light up OLED when you raise your wrist</div>
              </div>
            </div>
            <button
              onClick={handleToggleLiftToWake}
              className={`w-12 h-6 rounded-full transition-colors relative p-0.5 ${
                liftToWake ? 'bg-cyan-500' : 'bg-slate-800'
              }`}
            >
              <div
                className={`w-5 h-5 rounded-full bg-white transition-transform ${
                  liftToWake ? 'translate-x-6' : 'translate-x-0'
                }`}
              />
            </button>
          </div>

          {/* Toggle: Do Not Disturb */}
          <div className="flex items-center justify-between p-3 rounded-xl bg-slate-900/60 border border-slate-800">
            <div className="flex items-center gap-3">
              <Moon className="w-5 h-5 text-indigo-400" />
              <div>
                <div className="text-sm font-medium text-white">Do Not Disturb (DND)</div>
                <div className="text-xs text-slate-400">Mute vibration motors and notification popups</div>
              </div>
            </div>
            <button
              onClick={handleToggleDnd}
              className={`w-12 h-6 rounded-full transition-colors relative p-0.5 ${
                dnd ? 'bg-indigo-600' : 'bg-slate-800'
              }`}
            >
              <div
                className={`w-5 h-5 rounded-full bg-white transition-transform ${
                  dnd ? 'translate-x-6' : 'translate-x-0'
                }`}
              />
            </button>
          </div>

          {/* Toggle: Disconnect reminder */}
          <div className="flex items-center justify-between p-3 rounded-xl bg-slate-900/60 border border-slate-800">
            <div className="flex items-center gap-3">
              <Vibrate className="w-5 h-5 text-amber-400" />
              <div>
                <div className="text-sm font-medium text-white">Bluetooth Disconnect Alert</div>
                <div className="text-xs text-slate-400">Vibrate watch if phone goes out of range</div>
              </div>
            </div>
            <button
              onClick={() => setDisconnectRemind(!disconnectRemind)}
              className={`w-12 h-6 rounded-full transition-colors relative p-0.5 ${
                disconnectRemind ? 'bg-amber-500' : 'bg-slate-800'
              }`}
            >
              <div
                className={`w-5 h-5 rounded-full bg-white transition-transform ${
                  disconnectRemind ? 'translate-x-6' : 'translate-x-0'
                }`}
              />
            </button>
          </div>

          {/* Step Goal Slider */}
          <div className="p-3 rounded-xl bg-slate-900/60 border border-slate-800">
            <div className="flex justify-between items-center mb-2">
              <div className="text-sm font-medium text-white">Target Daily Steps</div>
              <span className="font-mono text-xs text-cyan-400 font-bold">{stepGoal.toLocaleString()}</span>
            </div>
            <input
              type="range"
              min={3000}
              max={30000}
              step={1000}
              value={stepGoal}
              onChange={(e) => handleStepGoalChange(Number(e.target.value))}
              className="w-full accent-cyan-400 cursor-pointer"
            />
            <div className="flex justify-between text-[10px] text-slate-500 font-mono mt-1">
              <span>3,000</span>
              <span>10,000 (Recommended)</span>
              <span>30,000</span>
            </div>
          </div>

          {/* Language and Format */}
          <div className="grid grid-cols-2 gap-3">
            <div className="p-3 rounded-xl bg-slate-900/60 border border-slate-800">
              <label className="text-xs text-slate-400 block mb-1.5 flex items-center gap-1.5">
                <Clock className="w-3.5 h-3.5" /> Time Mode
              </label>
              <select
                value={timeFormat24 ? '24' : '12'}
                onChange={(e) => setTimeFormat24(e.target.value === '24')}
                className="w-full bg-slate-950 border border-slate-800 rounded-lg p-2 text-xs text-white focus:outline-none"
              >
                <option value="24">24-Hour (14:30)</option>
                <option value="12">12-Hour (02:30 PM)</option>
              </select>
            </div>

            <div className="p-3 rounded-xl bg-slate-900/60 border border-slate-800">
              <label className="text-xs text-slate-400 block mb-1.5 flex items-center gap-1.5">
                <Globe className="w-3.5 h-3.5" /> Watch UI Language
              </label>
              <select
                value={language}
                onChange={(e) => setLanguage(e.target.value as any)}
                className="w-full bg-slate-950 border border-slate-800 rounded-lg p-2 text-xs text-white focus:outline-none"
              >
                <option value="EN">English</option>
                <option value="CN">中文 (Chinese)</option>
              </select>
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="p-4 border-t border-slate-800 bg-slate-900/40 flex justify-end">
          <button
            onClick={onClose}
            className="px-5 py-2 rounded-xl text-xs font-semibold bg-slate-800 hover:bg-slate-700 text-white transition-colors"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
};
