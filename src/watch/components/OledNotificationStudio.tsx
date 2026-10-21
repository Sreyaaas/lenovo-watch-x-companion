import React, { useState } from 'react';
import { Send, Bell, PhoneCall, MessageSquare, AlertTriangle, Sparkles, Check } from 'lucide-react';
import { bleManager } from '../bleManager';

export const OledNotificationStudio: React.FC = () => {
  const [sender, setSender] = useState('Telegram');
  const [message, setMessage] = useState('Meeting in 15 mins room B');
  const [channel, setChannel] = useState(7);
  const [isSending, setIsSending] = useState(false);
  const [sentSuccess, setSentSuccess] = useState(false);

  const presets = [
    { label: 'Incoming Call', sender: 'Dad', message: '+1 (555) 019-2831', channel: 1024 },
    { label: 'WhatsApp', sender: 'Sarah', message: 'See you at the cafe!', channel: 7 },
    { label: 'Calendar', sender: 'Schedule', message: 'Flight check-in open', channel: 7 },
    { label: 'Battery Alert', sender: 'Phone', message: 'Phone battery low: 15%', channel: 7 },
    { label: 'Weather', sender: 'Weather', message: '24°C Sunny, Humidity 45%', channel: 7 },
  ];

  const handleSend = async () => {
    if (bleManager.state === 'disconnected') {
      alert('Please connect your Lenovo Watch X first or start simulation mode.');
      return;
    }

    try {
      setIsSending(true);
      await bleManager.pushNotification(sender, message, channel);
      setSentSuccess(true);
      setTimeout(() => setSentSuccess(false), 3000);
    } catch (err: any) {
      alert(`Send failed: ${err.message}`);
    } finally {
      setIsSending(false);
    }
  };

  return (
    <div className="bg-slate-900/90 border border-slate-800 rounded-2xl p-6 shadow-xl backdrop-blur-md">
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center gap-3">
          <div className="p-2.5 rounded-xl bg-emerald-500/10 border border-emerald-500/20 text-emerald-400">
            <Bell className="w-5 h-5" />
          </div>
          <div>
            <h3 className="text-lg font-semibold text-white tracking-wide">Custom OLED Notification Studio</h3>
            <p className="text-xs text-slate-400">
              Transmit custom alerts, messages, and calls directly to the 96×32 OLED screen
            </p>
          </div>
        </div>
      </div>

      {/* Realistic 96x32 OLED Hardware Preview */}
      <div className="my-6 p-6 rounded-2xl bg-slate-950 border border-slate-800 flex flex-col items-center">
        <div className="text-[10px] font-mono tracking-widest text-slate-500 uppercase mb-3">
          Simulated Hardware OLED Display (96 × 32 px)
        </div>

        {/* OLED screen chassis */}
        <div className="w-full max-w-md h-24 rounded-lg bg-black p-3.5 border-2 border-slate-800 shadow-[inset_0_2px_8px_rgba(0,0,0,0.8),0_0_20px_rgba(16,185,129,0.15)] flex flex-col justify-between overflow-hidden relative font-mono">
          {/* Subtle OLED subpixel scanline grid */}
          <div
            className="absolute inset-0 pointer-events-none opacity-20"
            style={{
              backgroundImage:
                'linear-gradient(rgba(16, 185, 129, 0.1) 1px, transparent 1px), linear-gradient(90deg, rgba(16, 185, 129, 0.1) 1px, transparent 1px)',
              backgroundSize: '3px 3px',
            }}
          />

          <div className="flex items-center justify-between border-b border-emerald-950/60 pb-1 z-10">
            <span className="text-[11px] font-bold text-emerald-400 tracking-wider flex items-center gap-1.5 uppercase">
              {channel === 1024 ? (
                <>
                  <PhoneCall className="w-3 h-3 animate-pulse text-emerald-300" /> CALL
                </>
              ) : (
                <>
                  <MessageSquare className="w-3 h-3 text-emerald-300" /> {sender || 'ALERT'}
                </>
              )}
            </span>
            <span className="text-[10px] text-emerald-600 font-mono">CH:{channel}</span>
          </div>

          <div className="text-xs text-emerald-300 font-mono leading-tight tracking-wide z-10 line-clamp-2 drop-shadow-[0_0_4px_rgba(16,185,129,0.7)]">
            {message || 'No text payload'}
          </div>

          <div className="flex justify-between items-center text-[9px] text-emerald-700 font-mono pt-0.5 z-10">
            <span>LENOVO-X-OLED</span>
            <span>VIB_ON</span>
          </div>
        </div>
      </div>

      {/* Preset Pills */}
      <div className="flex flex-wrap gap-2 mb-5">
        <span className="text-xs text-slate-500 self-center font-mono mr-1">Quick Presets:</span>
        {presets.map((p, idx) => (
          <button
            key={idx}
            onClick={() => {
              setSender(p.sender);
              setMessage(p.message);
              setChannel(p.channel);
            }}
            className="text-xs px-2.5 py-1 rounded-md bg-slate-800/80 hover:bg-slate-700 text-slate-300 border border-slate-700/60 transition-colors"
          >
            {p.label}
          </button>
        ))}
      </div>

      {/* Form Fields */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-4">
        <div>
          <label className="text-xs text-slate-400 font-medium block mb-1.5">Sender / App Title (Max 14 chars)</label>
          <input
            type="text"
            maxLength={14}
            value={sender}
            onChange={(e) => setSender(e.target.value)}
            className="w-full px-3.5 py-2.5 rounded-xl bg-slate-950 border border-slate-800 text-white text-sm focus:outline-none focus:border-emerald-500 font-mono"
            placeholder="e.g. WhatsApp"
          />
        </div>

        <div className="md:col-span-2">
          <label className="text-xs text-slate-400 font-medium block mb-1.5">Notification Body (Max 64 chars)</label>
          <input
            type="text"
            maxLength={64}
            value={message}
            onChange={(e) => setMessage(e.target.value)}
            className="w-full px-3.5 py-2.5 rounded-xl bg-slate-950 border border-slate-800 text-white text-sm focus:outline-none focus:border-emerald-500 font-mono"
            placeholder="e.g. Flight boarding starts in 10 minutes"
          />
        </div>
      </div>

      <div className="flex items-center justify-between gap-4 mt-6">
        <div className="flex items-center gap-2">
          <label className="text-xs text-slate-400 font-mono">Channel Profile:</label>
          <select
            value={channel}
            onChange={(e) => setChannel(Number(e.target.value))}
            className="px-2.5 py-1.5 rounded-lg bg-slate-950 border border-slate-800 text-xs text-slate-200 font-mono focus:outline-none"
          >
            <option value={7}>Channel 7 (Message / Text Notification)</option>
            <option value={1024}>Channel 1024 (Continuous Incoming Call Alert)</option>
          </select>
        </div>

        <button
          onClick={handleSend}
          disabled={isSending}
          className={`px-6 py-2.5 rounded-xl font-medium text-sm flex items-center gap-2 shadow-lg transition-all ${
            sentSuccess
              ? 'bg-emerald-600 text-white shadow-emerald-500/25'
              : 'bg-emerald-500 hover:bg-emerald-400 text-slate-950 shadow-emerald-500/20 active:scale-95'
          }`}
        >
          {sentSuccess ? (
            <>
              <Check className="w-4 h-4" /> Sent to Watch!
            </>
          ) : (
            <>
              <Send className="w-4 h-4" /> Transmit to OLED
            </>
          )}
        </button>
      </div>
    </div>
  );
};
