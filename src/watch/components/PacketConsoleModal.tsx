import React, { useState, useEffect } from 'react';
import { Terminal, Trash2, ArrowUpRight, ArrowDownLeft, X, Copy, Check } from 'lucide-react';
import { bleManager, PacketLogEntry } from '../bleManager';

interface PacketConsoleModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const PacketConsoleModal: React.FC<PacketConsoleModalProps> = ({ isOpen, onClose }) => {
  const [logs, setLogs] = useState<PacketLogEntry[]>([...bleManager.logs]);
  const [copiedId, setCopiedId] = useState<string | null>(null);

  useEffect(() => {
    if (!isOpen) return;

    const handleLog = () => {
      setLogs([...bleManager.logs]);
    };

    bleManager.on('log', handleLog);
    return () => {
      bleManager.off('log', handleLog);
    };
  }, [isOpen]);

  if (!isOpen) return null;

  const copyToClipboard = (hex: string, id: string) => {
    navigator.clipboard.writeText(hex);
    setCopiedId(id);
    setTimeout(() => setCopiedId(null), 2000);
  };

  const clearLogs = () => {
    bleManager.logs = [];
    setLogs([]);
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-sm animate-in fade-in duration-150">
      <div className="w-full max-w-3xl bg-slate-950 border border-slate-800 rounded-2xl shadow-2xl overflow-hidden flex flex-col max-h-[85vh]">
        {/* Header */}
        <div className="p-4 border-b border-slate-800 flex items-center justify-between bg-slate-900/60">
          <div className="flex items-center gap-2.5">
            <div className="p-1.5 rounded-lg bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
              <Terminal className="w-4 h-4" />
            </div>
            <div>
              <h3 className="text-sm font-semibold text-white">GATT Packet Protocol Inspector</h3>
              <p className="text-[11px] text-slate-400 font-mono">
                Real-time transport layer trace (0000a801 / 0000a802)
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <button
              onClick={clearLogs}
              className="p-1.5 rounded-lg text-slate-400 hover:text-red-400 hover:bg-slate-800 transition-colors"
              title="Clear Console"
            >
              <Trash2 className="w-4 h-4" />
            </button>
            <button
              onClick={onClose}
              className="p-1.5 rounded-lg text-slate-400 hover:text-white hover:bg-slate-800 transition-colors"
            >
              <X className="w-4 h-4" />
            </button>
          </div>
        </div>

        {/* Packet Stream */}
        <div className="flex-1 p-4 overflow-y-auto space-y-2.5 font-mono text-xs">
          {logs.length === 0 ? (
            <div className="text-center py-12 text-slate-600">
              No BLE packets transmitted yet. Connect watch or send a command to see raw hex traffic.
            </div>
          ) : (
            logs.map((entry) => (
              <div
                key={entry.id}
                className={`p-3 rounded-xl border flex flex-col gap-1.5 ${
                  entry.direction === 'TX'
                    ? 'bg-cyan-950/20 border-cyan-500/30 text-cyan-200'
                    : 'bg-emerald-950/20 border-emerald-500/30 text-emerald-200'
                }`}
              >
                <div className="flex items-center justify-between text-[11px]">
                  <div className="flex items-center gap-2 font-bold">
                    {entry.direction === 'TX' ? (
                      <span className="flex items-center gap-1 text-cyan-400">
                        <ArrowUpRight className="w-3.5 h-3.5" /> TX [PHONE ➔ WATCH]
                      </span>
                    ) : (
                      <span className="flex items-center gap-1 text-emerald-400">
                        <ArrowDownLeft className="w-3.5 h-3.5" /> RX [WATCH ➔ PHONE]
                      </span>
                    )}
                    <span className="text-slate-400 font-normal">({entry.timestamp})</span>
                  </div>

                  <button
                    onClick={() => copyToClipboard(entry.hex, entry.id)}
                    className="text-slate-500 hover:text-slate-300 flex items-center gap-1 text-[10px]"
                  >
                    {copiedId === entry.id ? (
                      <>
                        <Check className="w-3 h-3 text-emerald-400" /> Copied
                      </>
                    ) : (
                      <>
                        <Copy className="w-3 h-3" /> Copy Hex
                      </>
                    )}
                  </button>
                </div>

                <div className="font-mono text-xs tracking-wider bg-black/50 p-2 rounded-lg break-all text-white border border-slate-900">
                  {entry.hex}
                </div>

                <div className="text-[11px] text-slate-400 italic">
                  Payload intent: <span className="text-slate-200 not-italic font-medium">{entry.description}</span>
                </div>
              </div>
            ))
          )}
        </div>

        {/* Footer info */}
        <div className="p-3 border-t border-slate-800 bg-slate-900/40 text-[11px] font-mono text-slate-500 flex justify-between items-center">
          <span>Framing: [Header #\x01] [Len] [Req/Resp] [Seq] [Action] [Data...] [Checksum]</span>
          <span>Buffer: {logs.length} frames</span>
        </div>
      </div>
    </div>
  );
};
