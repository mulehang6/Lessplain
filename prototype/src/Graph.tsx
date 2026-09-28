import { useEffect, useId, useRef, useState } from "react";
import {
  layout,
  replay,
  resolveAnchor,
  type Frame,
  type Source,
} from "./model";

export default function Graph({
  frame,
  selected,
  cursor,
  sources,
  onSelect,
  onStep,
  onChange,
  onQuestion,
  evidence,
}: {
  frame: Frame;
  selected: string;
  cursor: number;
  sources: Source[];
  onSelect: (id: string) => void;
  onStep: (index: number) => void;
  onChange: (id: string) => void;
  onQuestion: (question: string) => void;
  evidence?: { scope: string; evidence: string; question: string };
}) {
  const marker = useId().replaceAll(":", "");
  const geometry = layout(frame.nodes, frame.edges);
  const state = replay(frame.trace, cursor);
  const viewport = useRef<HTMLDivElement>(null);
  const [fit, setFit] = useState(1);
  const [zoom, setZoom] = useState<number | null>(null);
  const scale = zoom ?? fit;
  const selectedNode = frame.nodes.find((node) => node.id === selected)!;
  const selectedSource = sources.find(
    (source) => source.id === selectedNode.anchor.file,
  )!;
  const resolution = resolveAnchor(selectedNode.anchor, selectedSource);
  const oldTrace =
    !!frame.trace &&
    frame.nodes.some(
      (node) =>
        sources.find((s) => s.id === node.anchor.file)!.revision !==
        frame.trace!.revision,
    );

  useEffect(() => {
    const element = viewport.current;
    if (!element || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(([entry]) => {
      setFit(
        Math.max(
          0.45,
          Math.min(
            1.15,
            (entry.contentRect.width - 24) / geometry.width,
            (entry.contentRect.height - 24) / geometry.height,
          ),
        ),
      );
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [geometry.width, geometry.height]);

  return (
    <section className="graph-workspace" aria-label="交互关系图">
      <div className="canvas-corner">
        <span className="small-dot green" />
        {cursor < 0 ? "全部路径" : oldTrace ? "旧代码的回放" : "历史回放"}
        <span className="canvas-example">示例</span>
      </div>
      <div className="graph-viewport" ref={viewport}>
        <div
          className="graph-size"
          style={{
            width: geometry.width * scale,
            height: geometry.height * scale,
          }}
        >
          <div
            className="graph"
            style={{
              width: geometry.width,
              height: geometry.height,
              transform: `scale(${scale})`,
            }}
            aria-label={`${frame.title}关系图`}
          >
            <svg
              width={geometry.width}
              height={geometry.height}
              aria-hidden="true"
            >
              <defs>
                {[false, true].map((active) => (
                  <marker
                    key={String(active)}
                    id={`${marker}${active ? "hit" : ""}`}
                    markerWidth="7"
                    markerHeight="7"
                    refX="6"
                    refY="3.5"
                    orient="auto"
                  >
                    <path
                      d="M 0 0 L 7 3.5 L 0 7"
                      fill={active ? "#86c7a1" : "#758197"}
                    />
                  </marker>
                ))}
              </defs>
              {frame.edges.map((edge) => {
                const a = geometry.positions.get(edge.from)!;
                const b = geometry.positions.get(edge.to)!;
                const back = b.y <= a.y;
                const active = state.transitions.has(
                  `${edge.from}->${edge.to}`,
                );
                const path = back
                  ? `M ${a.x} ${a.y + 46} C 4 ${a.y + 46}, 4 ${b.y + 46}, ${b.x} ${b.y + 46}`
                  : `M ${a.x + 90} ${a.y + 92} C ${a.x + 90} ${a.y + 113}, ${b.x + 90} ${b.y - 20}, ${b.x + 90} ${b.y}`;
                return (
                  <g key={`${edge.from}-${edge.to}`}>
                    <path
                      d={path}
                      className={`edge ${active ? "hit" : ""}`}
                      markerEnd={`url(#${marker}${active ? "hit" : ""})`}
                    />
                    <text
                      x={back ? 18 : (a.x + b.x) / 2 + 90}
                      y={back ? (a.y + b.y) / 2 + 46 : (a.y + 92 + b.y) / 2 + 3}
                      textAnchor={back ? "start" : "middle"}
                      className="edge-label"
                    >
                      {edge.label}
                    </text>
                  </g>
                );
              })}
            </svg>
            {frame.nodes.map((node) => {
              const position = geometry.positions.get(node.id)!;
              const source = sources.find((s) => s.id === node.anchor.file)!;
              const anchor = resolveAnchor(node.anchor, source);
              const hit = state.latest.get(node.id);
              const occurrences =
                frame.trace?.hits.flatMap((event, index) =>
                  event.nodeId === node.id ? [{ event, index }] : [],
                ) ?? [];
              const broken =
                anchor.status === "missing" || anchor.status === "ambiguous";
              return (
                <div
                  key={node.id}
                  className={`graph-card-node ${selected === node.id ? "selected" : ""} ${hit ? "visited" : ""} ${state.current?.nodeId === node.id ? "current" : ""} ${broken ? "changed" : ""}`}
                  style={{ left: position.x, top: position.y }}
                >
                  <button
                    className="graph-node"
                    onClick={() => onSelect(node.id)}
                    aria-pressed={selected === node.id}
                    title={`${source.name}:${anchor.line ?? "?"}\n${node.anchor.text}`}
                  >
                    <span className="node-title">
                      {node.title}
                      {state.current?.nodeId === node.id && (
                        <span className="node-cursor">●</span>
                      )}
                    </span>
                    {hit ? (
                      <span
                        className="node-values"
                        title={`在 ${source.name}:${hit.observation.lineHint} 执行前读取 · ${hit.observation.revision}`}
                      >
                        {Object.entries(hit.values).map(([key, value]) => (
                          <span key={key}>
                            <small>{key}</small> {value}
                          </span>
                        ))}
                      </span>
                    ) : (
                      <span className="node-detail">{node.detail}</span>
                    )}
                    {!broken && occurrences.length < 2 && (
                      <span className="node-location">
                        {source.name}:{anchor.line ?? "?"}
                        {anchor.status === "relocated" ? " · 已移位" : ""}
                      </span>
                    )}
                  </button>
                  {occurrences.length > 1 && !broken && (
                    <div
                      className="hit-picker"
                      aria-label={`${node.title}的多次命中`}
                    >
                      <span>第</span>
                      {occurrences.map(({ event, index }, occurrence) => (
                        <button
                          key={event.seq}
                          aria-label={`${node.title}第 ${occurrence + 1} 次命中`}
                          aria-pressed={hit?.seq === event.seq}
                          className={hit?.seq === event.seq ? "active" : ""}
                          onClick={() => onStep(index)}
                        >
                          {occurrence + 1}
                        </button>
                      ))}
                      <span>次</span>
                    </div>
                  )}
                  {broken && (
                    <button
                      className="node-change-action"
                      onClick={() => onChange(node.id)}
                    >
                      △{" "}
                      {anchor.status === "ambiguous"
                        ? "位置不确定"
                        : "代码已变"}{" "}
                      · 查看变化
                    </button>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      </div>
      <div className="canvas-tools">
        {frame.trace ? (
          <div className="graph-playback" aria-label="图内回放控制">
            <button onClick={() => onStep(-1)} aria-pressed={cursor < 0}>
              全部路径
            </button>
            <button
              onClick={() => onStep(cursor - 1)}
              disabled={cursor < 0}
              aria-label="上一步"
              title="上一步"
            >
              ‹
            </button>
            <button
              className="primary"
              onClick={() => onStep(cursor + 1)}
              disabled={cursor >= frame.trace.hits.length - 1}
            >
              {cursor < 0 ? "▶ 回放" : "下一步 ›"}
            </button>
            <span className="mono">
              {cursor < 0 ? "—" : cursor + 1} / {frame.trace.hits.length}
            </span>
          </div>
        ) : (
          <span className="canvas-hint">点节点，看源码</span>
        )}
        <div className="graph-zoom">
          <button
            aria-label="缩小图"
            onClick={() => setZoom(Math.max(0.45, scale - 0.15))}
          >
            −
          </button>
          <button onClick={() => setZoom(null)}>适应</button>
          <button
            aria-label="放大图"
            onClick={() => setZoom(Math.min(1.8, scale + 0.15))}
          >
            ＋
          </button>
        </div>
      </div>
      <div className="canvas-context">
        <span className="selected-label">{selectedNode.title}</span>
        {resolution.evidenceStale && (
          <button className="change-link" onClick={() => onChange(selected)}>
            查看代码变化 ↗
          </button>
        )}
        {evidence && (
          <details className="evidence-popover">
            <summary>依据</summary>
            <div className="project-evidence">
              <code>{evidence.scope}</code>
              <p>{evidence.evidence}</p>
            </div>
          </details>
        )}
        {(selectedNode.question || evidence) && (
          <button
            className="question-link"
            title={evidence?.question ?? selectedNode.question}
            onClick={() =>
              onQuestion(evidence?.question ?? selectedNode.question!)
            }
          >
            复制追问 ↗
          </button>
        )}
      </div>
    </section>
  );
}
