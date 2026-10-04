import React, { useMemo, useState } from 'react';
import { ChevronDown, ChevronRight, FileText, Folder } from 'lucide-react';

// Build a nested tree from the flat list of "a/b/c.ext" paths.
function buildTree(files) {
  const root = { name: '', children: {}, files: [] };
  for (const f of files) {
    const parts = f.path.split('/');
    let node = root;
    parts.forEach((part, i) => {
      if (i === parts.length - 1) {
        node.files.push({ ...f, leaf: part });
      } else {
        if (!node.children[part]) node.children[part] = { name: part, children: {}, files: [] };
        node = node.children[part];
      }
    });
  }
  return root;
}

function FolderNode({ node, selectedPath, onSelect, depth }) {
  const [open, setOpen] = useState(true);
  const childFolders = Object.values(node.children);
  return (
    <div>
      <button
        onClick={() => setOpen((o) => !o)}
        className="flex items-center gap-1 w-full text-left py-1 hover:bg-zinc-800/60 rounded px-1"
        style={{ paddingLeft: depth * 12 + 4 }}
      >
        {open ? <ChevronDown className="w-3.5 h-3.5 text-zinc-500" /> : <ChevronRight className="w-3.5 h-3.5 text-zinc-500" />}
        <Folder className="w-3.5 h-3.5 text-amber-500/80" />
        <span className="text-[13px] text-zinc-300">{node.name}</span>
      </button>
      {open && (
        <div>
          {childFolders.map((c) => (
            <FolderNode key={c.name} node={c} selectedPath={selectedPath} onSelect={onSelect} depth={depth + 1} />
          ))}
          {node.files.map((f) => (
            <button
              key={f.path}
              onClick={() => onSelect(f.path)}
              className={`flex items-center gap-1.5 w-full text-left py-1 rounded px-1 transition-colors ${
                selectedPath === f.path ? 'bg-zinc-700/80 text-white' : 'hover:bg-zinc-800/60 text-zinc-400'
              }`}
              style={{ paddingLeft: (depth + 1) * 12 + 4 }}
            >
              <FileText className="w-3.5 h-3.5 text-zinc-500 shrink-0" />
              <span className="text-[13px] truncate font-mono">{f.leaf}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

export default function FileTree({ files, selectedPath, onSelect }) {
  const tree = useMemo(() => buildTree(files), [files]);
  return (
    <div className="text-zinc-300">
      <div className="px-2 pb-2 pt-1 text-[11px] uppercase tracking-wider text-zinc-500 font-semibold">Project files</div>
      {Object.values(tree.children).map((c) => (
        <FolderNode key={c.name} node={c} selectedPath={selectedPath} onSelect={onSelect} depth={0} />
      ))}
      {tree.files.map((f) => (
        <button
          key={f.path}
          onClick={() => onSelect(f.path)}
          className={`flex items-center gap-1.5 w-full text-left py-1 rounded px-2 transition-colors ${
            selectedPath === f.path ? 'bg-zinc-700/80 text-white' : 'hover:bg-zinc-800/60 text-zinc-400'
          }`}
        >
          <FileText className="w-3.5 h-3.5 text-zinc-500 shrink-0" />
          <span className="text-[13px] truncate font-mono">{f.leaf}</span>
        </button>
      ))}
    </div>
  );
}