import React, { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import { Send, Smartphone, Terminal, Package } from 'lucide-react';
import { KIT_FILES, KIT_INTENT } from '@/data/kitManifest';
import FileTree from '@/components/kit/FileTree';
import CodeBlock from '@/components/kit/CodeBlock';

export default function Kit() {
  const [selectedPath, setSelectedPath] = useState('README.md');
  const selected = KIT_FILES.find((f) => f.path === selectedPath) ?? KIT_FILES[0];
  const isReadme = selected.path === 'README.md';

  return (
    <div className="min-h-screen bg-white text-zinc-900">
      {/* Header */}
      <header className="border-b border-zinc-200 bg-white sticky top-0 z-10">
        <div className="max-w-[1400px] mx-auto px-5 py-4 flex items-start gap-4">
          <div className="flex items-center justify-center w-10 h-10 rounded-lg bg-zinc-900 text-white shrink-0">
            <Send className="w-5 h-5" />
          </div>
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2 flex-wrap">
              <h1 className="text-xl font-semibold tracking-tight">MMS Test Kit</h1>
              <span className="text-[11px] font-mono px-2 py-0.5 rounded-full bg-zinc-100 text-zinc-600 border border-zinc-200">
                native Android · Kotlin
              </span>
            </div>
            <p className="text-[13px] text-zinc-500 mt-1 max-w-3xl whitespace-pre-line leading-relaxed">{KIT_INTENT}</p>
          </div>
        </div>
      </header>

      {/* Quick facts */}
      <section className="border-b border-zinc-200 bg-zinc-50">
        <div className="max-w-[1400px] mx-auto px-5 py-3 grid grid-cols-2 md:grid-cols-4 gap-3">
          <Fact icon={<Smartphone className="w-4 h-4" />} label="Send path" value="SmsManager.sendMultimediaMessage" />
          <Fact icon={<Package className="w-4 h-4" />} label="PDU builder" value="AOSP PduComposer (klinkerapps)" />
          <Fact icon={<Terminal className="w-4 h-4" />} label="Transcode" value="Media3 Transformer · H.264" />
          <Fact icon={<Send className="w-4 h-4" />} label="Output" value="Real carrier MMS (no share-intent)" />
        </div>
      </section>

      {/* Body: tree + viewer */}
      <main className="max-w-[1400px] mx-auto px-5 py-5">
        <div className="grid grid-cols-1 lg:grid-cols-[300px_minmax(0,1fr)] gap-5 items-start">
          {/* Tree */}
          <aside className="lg:sticky lg:top-[140px] bg-zinc-950 rounded-lg p-2 max-h-[70vh] overflow-auto lg:max-h-[calc(100vh-160px)]">
            <FileTree files={KIT_FILES} selectedPath={selectedPath} onSelect={setSelectedPath} />
          </aside>

          {/* Viewer */}
          <section className="h-[70vh] lg:h-[calc(100vh-160px)] min-h-[420px]">
            {isReadme ? (
              <div className="h-full overflow-auto rounded-lg border border-zinc-200 bg-white p-6">
                <div className="prose prose-sm prose-zinc max-w-none prose-headings:tracking-tight prose-code:before:hidden prose-code:after:hidden prose-code:bg-zinc-100 prose-code:px-1.5 prose-code:py-0.5 prose-code:rounded prose-pre:bg-zinc-950 prose-pre:text-zinc-200">
                  <ReactMarkdown>{selected.content}</ReactMarkdown>
                </div>
              </div>
            ) : (
              <CodeBlock path={selected.path} content={selected.content} />
            )}
          </section>
        </div>
      </main>

      <footer className="border-t border-zinc-200 bg-zinc-50">
        <div className="max-w-[1400px] mx-auto px-5 py-4 text-[12px] text-zinc-500">
          Reference implementation kit · build it on the test phone, then hand the proven project to Rocket.
        </div>
      </footer>
    </div>
  );
}

function Fact({ icon, label, value }) {
  return (
    <div className="flex items-start gap-2.5">
      <div className="text-zinc-400 mt-0.5">{icon}</div>
      <div className="min-w-0">
        <div className="text-[11px] uppercase tracking-wider text-zinc-400">{label}</div>
        <div className="text-[12.5px] font-mono text-zinc-700 truncate">{value}</div>
      </div>
    </div>
  );
}