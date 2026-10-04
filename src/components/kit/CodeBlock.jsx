import React, { useState } from 'react';
import { Check, Copy } from 'lucide-react';

export default function CodeBlock({ path, content }) {
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(content);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      setCopied(false);
    }
  };

  return (
    <div className="flex flex-col h-full min-h-0">
      <div className="flex items-center justify-between gap-3 px-4 py-2 border-b border-zinc-800 bg-zinc-900/80 rounded-t-lg">
        <span className="font-mono text-[12px] text-zinc-400 truncate">{path}</span>
        <button
          onClick={copy}
          className="flex items-center gap-1.5 text-[12px] px-2.5 py-1 rounded-md bg-zinc-800 hover:bg-zinc-700 text-zinc-300 transition-colors shrink-0"
        >
          {copied ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
      <div className="flex-1 min-h-0 overflow-auto bg-zinc-950 rounded-b-lg">
        <pre className="p-4 text-[12.5px] leading-relaxed font-mono text-zinc-300 whitespace-pre">
          <code>{content}</code>
        </pre>
      </div>
    </div>
  );
}