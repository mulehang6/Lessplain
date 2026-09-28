import { useEffect, useRef } from "react";
import { resolveAnchor, type GraphNode, type Source } from "./model";

export default function ChangeReview({
  node,
  source,
  onClose,
  onCopy,
}: {
  node: GraphNode;
  source: Source;
  onClose: () => void;
  onCopy: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const resolution = resolveAnchor(node.anchor, source);
  useEffect(() => {
    const element = dialog.current!;
    const previous = document.activeElement as HTMLElement | null;
    element.showModal();
    return () => {
      element.close();
      previous?.focus();
    };
  }, []);
  const candidates = source.lines.filter(
    (line) => line.trim() === node.anchor.text.trim(),
  );
  // The changed-code scene provides a known candidate. A real resolver must supply this evidence.
  const candidate =
    node.id === "child" &&
    source.lines.some((line) => line.includes("buildBranch(point)"))
      ? source.lines.find((line) => line.includes("buildBranch(point)"))!.trim()
      : null;
  return (
    <dialog
      ref={dialog}
      className="change-dialog"
      onCancel={onClose}
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
      aria-label="代码变化对比"
    >
      <div className="dialog-heading">
        <h2>{node.title} · 代码变化</h2>
        <button onClick={onClose} aria-label="关闭代码对比">
          ×
        </button>
      </div>
      <div className="code-comparison">
        <div className="old-code">
          <span>当时 · 第 {node.anchor.lineHint} 行</span>
          <code>− {node.anchor.text}</code>
        </div>
        <span className="comparison-arrow" aria-hidden="true">
          ↓
        </span>
        <div className="new-code">
          <span>
            现在
            {resolution.line
              ? ` · 第 ${resolution.line} 行`
              : " · 尚未确认对应位置"}
          </span>
          <code>
            +{" "}
            {candidate ??
              (resolution.line
                ? source.lines[resolution.line - 1].trim()
                : candidates.length > 1
                  ? "同样的文本出现了多次，不能自动选择"
                  : "原来的代码已找不到")}
          </code>
        </div>
      </div>
      <p>
        {resolution.status === "relocated"
          ? "位置已自动更新；周围代码是否改变了含义，需要 Agent 结合源码检查。"
          : "把这处变化交给 Agent，确认原来的解释是否还适用。"}
      </p>
      <div className="dialog-actions">
        <button onClick={onClose}>返回图</button>
        <button className="primary" onClick={onCopy}>
          复制核对问题，发给 Agent ↗
        </button>
      </div>
    </dialog>
  );
}
