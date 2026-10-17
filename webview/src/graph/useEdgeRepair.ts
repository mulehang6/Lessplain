import { useCallback, useEffect, useRef } from "react";
import type { ReactFlowInstance } from "@xyflow/react";
import type { CanvasModel } from "./canvasModel";
import type { CanvasFlowNode, CanvasFlowEdge } from "./CanvasNodes";
import type { Layout } from "./layout";
import { repairSections } from "./edgeRepair";

/** 拖动修线按帧合并，只更新受影响边；不订阅整图位置，也不触发布局/持久化。 */
export function useEdgeRepair(flow: ReactFlowInstance<CanvasFlowNode, CanvasFlowEdge>, model: CanvasModel, layout: Layout | undefined, down: boolean) {
  const latest = useRef({ model, layout, down }); latest.current = { model, layout, down };
  const frame = useRef<number | undefined>(undefined);
  const update = useCallback(() => {
    const { model, layout, down } = latest.current;
    if (!layout) return;
    const sections = repairSections(model, layout, flow.getNodes(), down);
    flow.setEdges(edges => edges.map(edge => {
      const next = sections[edge.id];
      if (!edge.data || !next || next === edge.data.sections) return edge;
      return { ...edge, data: { ...edge.data, sections: next } };
    }));
  }, [flow]);
  const schedule = useCallback(() => {
    if (frame.current !== undefined) return;
    frame.current = requestAnimationFrame(() => { frame.current = undefined; update(); });
  }, [update]);
  const flush = useCallback(() => { if (frame.current !== undefined) cancelAnimationFrame(frame.current); frame.current = undefined; update(); }, [update]);
  useEffect(() => () => { if (frame.current !== undefined) cancelAnimationFrame(frame.current); }, []);
  return { schedule, flush };
}
