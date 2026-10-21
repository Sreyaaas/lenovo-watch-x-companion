import React, { useState, useEffect } from 'react';
import { Heart, Activity, Flame, Trophy, RefreshCw, Zap } from 'lucide-react';
import { bleManager } from '../bleManager';
import { DeviceTelemetry } from '../protocol';

export const HealthTelemetryPanel: React.FC = () => {
  const [telemetry, setTelemetry] = useState<DeviceTelemetry>(bleManager.telemetry);
  const [isMeasuringHr, setIsMeasuringHr] = useState(false);
  const [stepGoal, setStepGoal] = useState(10000);

  useEffect(() => {
    const handleTelemetry = (data: DeviceTelemetry) => {
      setTelemetry({ ...data });
    };

    bleManager.on('telemetry', handleTelemetry);
    return () => {
      bleManager.off('telemetry', handleTelemetry);
    };
  }, []);

  const handleMeasureHeartRate = async () => {
    if (bleManager.state === 'disconnected') {
      alert('Connect Lenovo Watch X first.');
      return;
    }

    try {
      setIsMeasuringHr(true);
      await bleManager.measureHeartRate();
      setTimeout(() => setIsMeasuringHr(false), 8000);
    } catch (e: any) {
      alert(e.message);
      setIsMeasuringHr(false);
    }
  };

  const steps = telemetry.todaySteps;
  const progressPercent = Math.min(100, Math.round((steps / stepGoal) * 100));
  const estimatedCalories = Math.round(steps * 0.04);
  const estimatedDistanceKm = (steps * 0.00075).toFixed(2);

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
      {/* Daily Steps Card */}
      <div className="bg-slate-900/90 border border-slate-800 rounded-2xl p-6 shadow-xl backdrop-blur-md flex flex-col justify-between">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-3">
            <div className="p-2.5 rounded-xl bg-amber-500/10 border border-amber-500/20 text-amber-400">
              <Activity className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-base font-semibold text-white">Daily Pedometer</h3>
              <p className="text-xs text-slate-400">Hardware 3-axis accelerometer data</p>
            </div>
          </div>
          <span className="text-xs font-mono px-2.5 py-1 rounded-full bg-amber-500/10 text-amber-400 border border-amber-500/20">
            {progressPercent}% Goal
          </span>
        </div>

        <div className="my-4 flex items-center justify-between">
          <div>
            <div className="text-4xl font-extrabold text-white tracking-tight font-mono">
              {steps.toLocaleString()}
            </div>
            <div className="text-xs text-slate-400 mt-1">Goal: {stepGoal.toLocaleString()} steps</div>
          </div>

          {/* Circular progress bar */}
          <div className="relative w-20 h-20 flex items-center justify-center">
            <svg className="w-full h-full transform -rotate-90">
              <circle
                cx="40"
                cy="40"
                r="34"
                className="stroke-slate-800"
                strokeWidth="6"
                fill="transparent"
              />
              <circle
                cx="40"
                cy="40"
                r="34"
                className="stroke-amber-400 transition-all duration-1000 ease-out"
                strokeWidth="6"
                strokeDasharray={213.6}
                strokeDashoffset={213.6 - (213.6 * progressPercent) / 100}
                strokeLinecap="round"
                fill="transparent"
              />
            </svg>
            <Trophy className="absolute w-6 h-6 text-amber-400" />
          </div>
        </div>

        {/* Secondary metrics */}
        <div className="grid grid-cols-2 gap-3 pt-4 border-t border-slate-800 text-xs">
          <div className="flex items-center gap-2 p-2.5 rounded-xl bg-slate-950 border border-slate-800/80">
            <Flame className="w-4 h-4 text-orange-400" />
            <div>
              <div className="text-slate-400 text-[10px]">Calories</div>
              <div className="font-mono font-bold text-white">{estimatedCalories} kcal</div>
            </div>
          </div>
          <div className="flex items-center gap-2 p-2.5 rounded-xl bg-slate-950 border border-slate-800/80">
            <Zap className="w-4 h-4 text-sky-400" />
            <div>
              <div className="text-slate-400 text-[10px]">Distance</div>
              <div className="font-mono font-bold text-white">{estimatedDistanceKm} km</div>
            </div>
          </div>
        </div>
      </div>

      {/* Heart Rate Telemetry Card */}
      <div className="bg-slate-900/90 border border-slate-800 rounded-2xl p-6 shadow-xl backdrop-blur-md flex flex-col justify-between">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-3">
            <div className="p-2.5 rounded-xl bg-rose-500/10 border border-rose-500/20 text-rose-400">
              <Heart className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-base font-semibold text-white">Photoplethysmography (PPG)</h3>
              <p className="text-xs text-slate-400">Optical heart rate sensor stream</p>
            </div>
          </div>
          <button
            onClick={handleMeasureHeartRate}
            disabled={isMeasuringHr}
            className="text-xs px-3 py-1.5 rounded-lg bg-rose-500/10 hover:bg-rose-500/20 text-rose-400 border border-rose-500/20 flex items-center gap-1.5 transition-all active:scale-95 disabled:opacity-50"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${isMeasuringHr ? 'animate-spin' : ''}`} />
            {isMeasuringHr ? 'Measuring...' : 'Trigger Read'}
          </button>
        </div>

        <div className="my-4 flex items-center justify-between">
          <div>
            <div className="text-4xl font-extrabold text-white tracking-tight font-mono flex items-baseline gap-2">
              <span>{telemetry.lastHeartRate || '--'}</span>
              <span className="text-sm font-normal text-slate-400">BPM</span>
            </div>
            <div className="text-xs text-slate-400 mt-1">
              Status: {isMeasuringHr ? 'Sampling PPG sensor...' : 'Resting pulse verified'}
            </div>
          </div>

          <div
            className={`w-16 h-16 rounded-full flex items-center justify-center border-2 ${
              isMeasuringHr
                ? 'border-rose-500 bg-rose-500/20 animate-pulse shadow-[0_0_20px_rgba(244,63,94,0.4)]'
                : 'border-slate-800 bg-slate-950'
            }`}
          >
            <Heart
              className={`w-8 h-8 text-rose-500 ${isMeasuringHr ? 'scale-110' : ''} transition-transform`}
            />
          </div>
        </div>

        <div className="p-3 rounded-xl bg-slate-950 border border-slate-800 text-[11px] text-slate-400">
          Raw sample frequency: 2-minute interval with local hardware buffering. Real-time stream can be queried over BLE opcode <code className="text-rose-400 font-mono">0xF0 0x10</code>.
        </div>
      </div>
    </div>
  );
};
