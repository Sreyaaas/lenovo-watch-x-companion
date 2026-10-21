import React, { useState } from 'react';
import { AlarmClock, Plus, Check, Clock } from 'lucide-react';
import { bleManager } from '../bleManager';

interface AlarmItem {
  id: number; // 1, 2, or 3
  hour: number;
  minute: number;
  enabled: boolean;
  days: boolean[]; // [Sun, Mon, Tue, Wed, Thu, Fri, Sat]
  label: string;
}

const DAY_LABELS = ['S', 'M', 'T', 'W', 'T', 'F', 'S'];

export const HardwareAlarmManager: React.FC = () => {
  const [alarms, setAlarms] = useState<AlarmItem[]>([
    { id: 1, hour: 7, minute: 0, enabled: true, days: [false, true, true, true, true, true, false], label: 'Wake Up' },
    { id: 2, hour: 13, minute: 30, enabled: false, days: [false, true, true, true, true, true, false], label: 'Afternoon Workout' },
    { id: 3, hour: 22, minute: 15, enabled: true, days: [true, true, true, true, true, true, true], label: 'Wind Down' },
  ]);

  const [savingSlot, setSavingSlot] = useState<number | null>(null);

  const toggleDay = (alarmId: number, dayIndex: number) => {
    setAlarms((prev) =>
      prev.map((a) => {
        if (a.id !== alarmId) return a;
        const newDays = [...a.days];
        newDays[dayIndex] = !newDays[dayIndex];
        return { ...a, days: newDays };
      })
    );
  };

  const updateTime = (alarmId: number, hour: number, minute: number) => {
    setAlarms((prev) =>
      prev.map((a) => (a.id === alarmId ? { ...a, hour, minute } : a))
    );
  };

  const toggleEnabled = async (alarmId: number) => {
    const target = alarms.find((a) => a.id === alarmId);
    if (!target) return;

    const newEnabled = !target.enabled;
    setAlarms((prev) =>
      prev.map((a) => (a.id === alarmId ? { ...a, enabled: newEnabled } : a))
    );

    await saveAlarm(alarmId, newEnabled);
  };

  const saveAlarm = async (alarmId: number, overrideEnabled?: boolean) => {
    const alarm = alarms.find((a) => a.id === alarmId);
    if (!alarm) return;

    if (bleManager.state === 'disconnected') {
      alert('Please connect your watch first.');
      return;
    }

    try {
      setSavingSlot(alarmId);
      const isEnabled = overrideEnabled !== undefined ? overrideEnabled : alarm.enabled;
      await bleManager.setAlarm(alarm.id, alarm.hour, alarm.minute, alarm.days, isEnabled);
    } catch (err: any) {
      alert(`Alarm sync error: ${err.message}`);
    } finally {
      setTimeout(() => setSavingSlot(null), 800);
    }
  };

  return (
    <div className="bg-slate-900/90 border border-slate-800 rounded-2xl p-6 shadow-xl backdrop-blur-md">
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center gap-3">
          <div className="p-2.5 rounded-xl bg-purple-500/10 border border-purple-500/20 text-purple-400">
            <AlarmClock className="w-5 h-5" />
          </div>
          <div>
            <h3 className="text-lg font-semibold text-white tracking-wide">Hardware Alarms</h3>
            <p className="text-xs text-slate-400">
              3 internal watch vibration alarms stored directly in watch NVRAM
            </p>
          </div>
        </div>
      </div>

      <div className="space-y-4 mt-6">
        {alarms.map((alarm) => (
          <div
            key={alarm.id}
            className={`p-4 rounded-xl border transition-all ${
              alarm.enabled
                ? 'bg-slate-950/80 border-purple-500/30 shadow-md'
                : 'bg-slate-950/40 border-slate-800/80 opacity-70'
            }`}
          >
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-8 h-8 rounded-lg bg-slate-900 border border-slate-800 flex items-center justify-center font-mono text-xs text-purple-400 font-bold">
                  #{alarm.id}
                </div>
                <div>
                  <div className="flex items-center gap-2">
                    <input
                      type="number"
                      min={0}
                      max={23}
                      value={alarm.hour}
                      onChange={(e) => updateTime(alarm.id, parseInt(e.target.value) || 0, alarm.minute)}
                      className="w-12 text-xl font-mono font-bold bg-slate-900 border border-slate-800 rounded px-1.5 py-0.5 text-center text-white"
                    />
                    <span className="text-xl font-mono text-slate-500">:</span>
                    <input
                      type="number"
                      min={0}
                      max={59}
                      value={alarm.minute.toString().padStart(2, '0')}
                      onChange={(e) => updateTime(alarm.id, alarm.hour, parseInt(e.target.value) || 0)}
                      className="w-12 text-xl font-mono font-bold bg-slate-900 border border-slate-800 rounded px-1.5 py-0.5 text-center text-white"
                    />
                  </div>
                  <span className="text-xs text-slate-500">{alarm.label}</span>
                </div>
              </div>

              <div className="flex items-center gap-3">
                <button
                  onClick={() => saveAlarm(alarm.id)}
                  disabled={savingSlot === alarm.id}
                  className="px-3 py-1.5 text-xs rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 border border-slate-700 transition-colors"
                >
                  {savingSlot === alarm.id ? 'Syncing...' : 'Save to Watch'}
                </button>

                {/* Switch toggle */}
                <button
                  onClick={() => toggleEnabled(alarm.id)}
                  className={`w-12 h-6 rounded-full transition-colors relative p-0.5 ${
                    alarm.enabled ? 'bg-purple-600' : 'bg-slate-800'
                  }`}
                >
                  <div
                    className={`w-5 h-5 rounded-full bg-white transition-transform ${
                      alarm.enabled ? 'translate-x-6' : 'translate-x-0'
                    }`}
                  />
                </button>
              </div>
            </div>

            {/* Repeat Days selector */}
            <div className="mt-3 pt-3 border-t border-slate-900 flex items-center justify-between">
              <span className="text-[11px] font-mono text-slate-400">Repeat:</span>
              <div className="flex gap-1">
                {DAY_LABELS.map((dayLabel, idx) => (
                  <button
                    key={idx}
                    onClick={() => toggleDay(alarm.id, idx)}
                    className={`w-6 h-6 rounded text-[10px] font-mono font-bold transition-colors ${
                      alarm.days[idx]
                        ? 'bg-purple-500/20 text-purple-300 border border-purple-500/40'
                        : 'bg-slate-900 text-slate-500 hover:bg-slate-800'
                    }`}
                  >
                    {dayLabel}
                  </button>
                ))}
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};
