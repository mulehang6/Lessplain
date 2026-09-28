import { useEffect, useRef } from "react";
import {
  replay,
  resolveAnchor,
  type Frame,
  type Source,
  type Visit,
} from "./model";

function Code({ text }: { text: string }) {
  if (text.trimStart().startsWith("//"))
    return <span className="syntax-comment">{text}</span>;
  return (
    <>
      {text
        .split(
          /("[^"]*"|\b(?:fun|val|var|if|while|return|true|false|null)\b|\b\d+\b)/g,
        )
        .map((part, i) => (
          <span
            key={i}
            className={
              /^"/.test(part)
                ? "syntax-string"
                : /^(fun|val|var|if|while|return|true|false|null)$/.test(part)
                  ? "syntax-keyword"
                  : /^\d+$/.test(part)
                    ? "syntax-number"
                    : ""
            }
          >
            {part}
          </span>
        ))}
    </>
  );
}

export default function Editor({
  frame,
  visit,
  sources,
  onSelect,
  onFile,
}: {
  frame: Frame;
  visit: Visit;
  sources: Source[];
  onSelect: (id: string) => void;
  onFile: (file: string) => void;
}) {
  const source = sources.find((s) => s.id === visit.file) ?? sources[0];
  const selected = frame.nodes.find((node) => node.id === visit.nodeId);
  const resolution = selected ? resolveAnchor(selected.anchor, source) : null;
  const selectedRef = useRef<HTMLButtonElement>(null);
  const state = replay(frame.trace, visit.cursor);
  useEffect(() => {
    selectedRef.current?.scrollIntoView({
      block: "nearest",
      inline: "nearest",
    });
  }, [resolution?.line, source.id, frame.id]);
  return (
    <section className="editor-panel" aria-label="源码预览">
      <div className="file-tabs">
        {sources.map((file) => (
          <button
            key={file.id}
            className={source.id === file.id ? "active" : ""}
            onClick={() => onFile(file.id)}
          >
            <span className="kotlin">K</span>
            {file.name}
          </button>
        ))}
      </div>
      <div className="breadcrumbs">
        demo <span>/</span> {source.name} <span>/</span> {source.symbol}
      </div>
      <div className="source-role">
        <span className="small-dot" />
        {source.role}
      </div>
      <div className="code-editor">
        {source.lines.map((line, i) => {
          const number = i + 1;
          const nodes = frame.nodes.filter(
            (node) => resolveAnchor(node.anchor, source).line === number,
          );
          const runtime = nodes
            .map((node) => state.latest.get(node.id))
            .find(Boolean);
          const isSelected = resolution?.line === number;
          return (
            <button
              type="button"
              key={number}
              ref={isSelected ? selectedRef : undefined}
              className={`code-line ${nodes.length ? "annotated" : ""} ${isSelected ? "selected" : ""}`}
              aria-label={`第 ${number} 行 ${line.trim()}`}
              onClick={() => {
                if (nodes[0]) onSelect(nodes[0].id);
              }}
            >
              <span className="gutter-mark">{isSelected ? "›" : ""}</span>
              <span className="line-number">{number}</span>
              <code>
                <Code text={line} />
              </code>
              {nodes.length > 0 && (
                <span className={`inline-value ${runtime ? "runtime" : ""}`}>
                  {runtime
                    ? Object.entries(runtime.values)
                        .map(([key, value]) => `${key} = ${value}`)
                        .join(" · ")
                    : nodes[0].title}
                  {runtime && (
                    <small>
                      {" "}
                      #{runtime.seq} · 历史观测
                      {source.revision !== runtime.observation.revision
                        ? " · 旧版本"
                        : ""}
                    </small>
                  )}
                </span>
              )}
            </button>
          );
        })}
      </div>
      <div className="editor-footnote">
        <span>只读示例</span>
        <span>点击带注释的代码行，同步定位图节点</span>
        <span>{source.revision}</span>
      </div>
    </section>
  );
}
