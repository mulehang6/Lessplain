import { currentSources, initialFrames } from "./data";
import {
  resolveAnchor,
  captureContext,
  presentFrame,
  type Frame,
} from "./model";

/** A prepared Agent response for this exact scene; never relabel old runtime evidence. */
export function updatedBranchScene(): Frame {
  const frame = structuredClone(initialFrames[0]);
  const source = currentSources("missing")[0];
  frame.nodes = frame.nodes.map((node) => {
    const line =
      node.id === "child" ? 8 : resolveAnchor(node.anchor, source).line;
    if (!line) throw new Error("预制场景缺少可核对的源码位置");
    return {
      ...node,
      detail: node.id === "child" ? "buildBranch(point)" : node.detail,
      anchor: {
        ...node.anchor,
        lineHint: line,
        text: source.lines[line - 1].trim(),
        revision: source.revision,
      },
    };
  });
  return frame;
}

export function followupScene(): Frame {
  const source = initialFrames[0];
  const token = captureContext(
    source,
    { nodeId: "show", cursor: 3, file: "branch" },
    "scene-followup",
  );
  return presentFrame(structuredClone(initialFrames[1]), token);
}
