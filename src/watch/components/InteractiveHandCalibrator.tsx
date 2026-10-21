import React, { useState } from 'react';
import { Compass, RotateCw, CheckCircle2, AlertCircle } from 'lucide-react';
import { bleManager } from '../bleManager';

export const InteractiveHandCalibrator: React.FC = () => {
  const [hour, setHour] = useState(12);
  const [minute, setMinute] = useState(0);
  const [isCalibrating, setIsCalibrating] = useState(false);
  const [statusMsg, setStatusMsg] = useState<string | null>(null);

  const hourAngle = ((hour % 12) + minute / 60) * 30; // 360 / 12 = 30 deg/hr
  const minuteAngle = minute * 6; // 360 / 60 = 6 deg/min

  const adjustHour = (delta: number) => {
    setHour((prev) => {
      let next = prev + delta;
      if (next > 12) next = 1;
      if (next < 1) next = 12;
      return next;
    });
  };

  const adjustMinute = (delta: number) => {
    setMinute((prev) => {
      let next = (prev + delta) % 60;
      if (next < 0) next += 60;
      return next;
    });
  };

  const setZeroPosition = () => {
    setHour(12);
    setMinute(0);
  };

  const handleCalibrate = async () => {
    if (bleManager.state === 'disconnected') {
      setStatusMsg('Please connect your Lenovo Watch X first.');
      return;
    }

    try {
      setIsCalibrating(true);
      setStatusMsg('Initiating stepper calibration sequence...');

      // Physical hour (0-11) and minute
      const calHour = hour === 12 ? 0 : hour;
      await bleManager.calibrateHands(calHour, minute, 0);

      setStatusMsg('Calibration complete! Watch hands are moving to current time.');
      setTimeout(() => setStatusMsg(null), 5000);
    } catch (err: any) {
      setStatusMsg(`Calibration failed: ${err.message}`);
    } finally {
      setIsCalibrating(false);
    }
  };

  return (
    <div className="bg-slate-900/90 border border-slate-800 rounded-2xl p-6 shadow-xl backdrop-blur-md">
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center gap-3">
          <div className="p-2.5 rounded-xl bg-cyan-500/10 border border-cyan-500/20 text-cyan-400">
            <Compass className="w-5 h-5" />
          </div>
          <div>
            <h3 className="text-lg font-semibold text-white tracking-wide">Mechanical Hands Calibration</h3>
            <p className="text-xs text-slate-400">
              Align the watch mechanical steppers to match real atomic time
            </p>
          </div>
        </div>
        <button
          onClick={setZeroPosition}
          className="text-xs px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300 transition-colors flex items-center gap-1.5 border border-slate-700"
        >
          <RotateCw className="w-3.5 h-3.5" /> Reset to 12:00
        </button>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-8 items-center mt-6">
        {/* Interactive Analog Watch Face */}
        <div className="flex flex-col items-center justify-center p-4">
          <div className="relative w-56 h-56 rounded-full bg-gradient-to-b from-slate-950 to-slate-900 border-4 border-slate-800 shadow-2xl flex items-center justify-center">
            {/* Outer minute markers */}
            {Array.from({ length: 12 }).map((_, i) => {
              const rot = i * 30;
              return (
                <div
                  key={i}
                  className="absolute w-full h-full flex justify-center pt-2 pointer-events-none"
                  style={{ transform: `rotate(${rot}deg)` }}
                >
                  <div
                    className={`rounded-full ${
                      i % 3 === 0 ? 'w-1.5 h-3 bg-cyan-400 shadow-[0_0_8px_rgba(34,211,238,0.5)]' : 'w-0.5 h-2 bg-slate-600'
                    }`}
                  />
                </div>
              );
            })}

            {/* Inner dial text */}
            <div className="absolute top-12 text-[10px] tracking-widest font-mono text-cyan-500/70 font-bold uppercase">
              LENOVO HYBRID
            </div>
            <div className="absolute bottom-12 text-[9px] font-mono text-slate-500">
              OLED · QUARTZ
            </div>

            {/* Hour Hand */}
            <div
              className="absolute w-1.5 h-16 bg-white rounded-full origin-bottom shadow-lg transition-transform duration-200"
              style={{
                bottom: '50%',
                transform: `rotate(${hourAngle}deg)`,
              }}
            >
              <div className="w-1.5 h-4 bg-cyan-400 rounded-full" />
            </div>

            {/* Minute Hand */}
            <div
              className="absolute w-1 h-24 bg-cyan-400 rounded-full origin-bottom shadow-lg transition-transform duration-200"
              style={{
                bottom: '50%',
                transform: `rotate(${minuteAngle}deg)`,
              }}
            >
              <div className="w-1 h-8 bg-cyan-200 rounded-full" />
            </div>

            {/* Center Cap */}
            <div className="absolute w-4 h-4 rounded-full bg-slate-200 border-2 border-slate-900 shadow-md z-10" />
          </div>

          <div className="mt-4 text-center">
            <span className="text-xs uppercase tracking-wider text-slate-400 font-mono">Current Input:</span>
            <div className="text-2xl font-mono font-bold text-cyan-400 tracking-wider">
              {hour.toString().padStart(2, '0')}:{minute.toString().padStart(2, '0')}
            </div>
          </div>
        </div>

        {/* Calibration Controls & Instructions */}
        <div className="space-y-4">
          <div className="p-4 rounded-xl bg-slate-950/60 border border-slate-800 text-xs text-slate-300 space-y-2">
            <div className="font-semibold text-white flex items-center gap-1.5 text-cyan-400">
              <AlertCircle className="w-4 h-4 text-cyan-400" /> How Calibration Works:
            </div>
            <ol className="list-decimal list-inside space-y-1 text-slate-400">
              <li>Look at the physical hour & minute hands on your Lenovo Watch X.</li>
              <li>Use the buttons below to set this dial to match what you see on the watch face.</li>
              <li>Tap <strong className="text-white">"Align Hands to Real Time"</strong>.</li>
              <li>The watch's internal microcontroller steps the stepper motor to current atomic time.</li>
            </ol>
          </div>

          {/* Stepper Hand Controls */}
          <div className="space-y-3">
            <div>
              <label className="text-xs text-slate-400 font-medium block mb-1.5">Hour Hand</label>
              <div className="flex gap-2">
                <button
                  onClick={() => adjustHour(-1)}
                  className="flex-1 py-2 rounded-lg bg-slate-800 hover:bg-slate-700 text-white font-mono text-sm border border-slate-700 active:scale-95 transition-all"
                >
                  -1 Hour
                </button>
                <div className="w-14 flex items-center justify-center font-mono font-bold text-white bg-slate-950 rounded-lg border border-slate-800 text-base">
                  {hour}
                </div>
                <button
                  onClick={() => adjustHour(1)}
                  className="flex-1 py-2 rounded-lg bg-slate-800 hover:bg-slate-700 text-white font-mono text-sm border border-slate-700 active:scale-95 transition-all"
                >
                  +1 Hour
                </button>
              </div>
            </div>

            <div>
              <label className="text-xs text-slate-400 font-medium block mb-1.5">Minute Hand</label>
              <div className="flex gap-2">
                <button
                  onClick={() => adjustMinute(-5)}
                  className="px-3 py-2 rounded-lg bg-slate-800 hover:bg-slate-700 text-white font-mono text-xs border border-slate-700 active:scale-95 transition-all"
                >
                  -5m
                </button>
                <button
                  onClick={() => adjustMinute(-1)}
                  className="px-3 py-2 rounded-lg bg-slate-800 hover:bg-slate-700 text-white font-mono text-xs border border-slate-700 active:scale-95 transition-all"
                >
                  -1m
                </button>
                <div className="flex-1 flex items-center justify-center font-mono font-bold text-cyan-400 bg-slate-950 rounded-lg border border-slate-800 text-base">
                  {minute.toString().padStart(2, '0')}m
                </div>
                <button
                  onClick={() => adjustMinute(1)}
                  className="px-3 py-2 rounded-lg bg-slate-800 hover:bg-slate-700 text-white font-mono text-xs border border-slate-700 active:scale-95 transition-all"
                >
                  +1m
                </button>
                <button
                  onClick={() => adjustMinute(5)}
                  className="px-3 py-2 rounded-lg bg-slate-800 hover:bg-slate-700 text-white font-mono text-xs border border-slate-700 active:scale-95 transition-all"
                >
                  +5m
                </button>
              </div>
            </div>
          </div>

          <button
            onClick={handleCalibrate}
            disabled={isCalibrating}
            className={`w-full py-3.5 px-4 rounded-xl font-medium text-sm flex items-center justify-center gap-2 shadow-lg transition-all ${
              isCalibrating
                ? 'bg-slate-800 text-slate-500 cursor-not-allowed'
                : 'bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white shadow-cyan-500/25 active:scale-[0.99]'
            }`}
          >
            {isCalibrating ? (
              <>
                <RotateCw className="w-4 h-4 animate-spin" /> Stepper Motors Calibrating...
              </>
            ) : (
              <>
                <CheckCircle2 className="w-4 h-4" /> Align Hands to Real Time
              </>
            )}
          </button>

          {statusMsg && (
            <div
              className={`p-3 rounded-lg text-xs font-mono text-center border ${
                statusMsg.includes('failed')
                  ? 'bg-red-950/40 border-red-800 text-red-300'
                  : 'bg-cyan-950/40 border-cyan-800 text-cyan-300'
              }`}
            >
              {statusMsg}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
