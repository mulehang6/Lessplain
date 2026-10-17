import ELK, { type ElkNode, type ElkEdgeSection } from "elkjs/lib/elk-api";
import workerUrl from "elkjs/lib/elk-worker.min.js?url&no-inline";
import type { CanvasModel } from "./canvasModel";
import { recordCanvasEvent } from "./diagnostics";
export type Measure = { id: string; width: number; height: number };
export type Layout = {
  positions: Record<string, { x: number; y: number }>;
  sizes: Record<string, { width: number; height: number }>;
  sections: Record<string, ElkEdgeSection[]>;
};

const GROUP_HEADER_HEIGHT = 52;
const GROUP_PADDING = 20;

/** Build one compound ELK graph so group boundaries and cross-group edges share a layout. */
export function layoutInput(model: CanvasModel, sizes: Measure[], direction: "RIGHT" | "DOWN"): ElkNode {
  const measured = new Map(sizes.map(size => [size.id, size]));
  const portSide = direction === "DOWN" ? { input: "NORTH", output: "SOUTH" } : { input: "WEST", output: "EAST" };
  const node = (id: string) => {
    const size = measured.get(id);
    if (!size) throw new Error("节点尚未测量：" + id);
    return {
      id,
      width: size.width,
      height: size.height,
      layoutOptions: { "elk.portConstraints": "FIXED_SIDE" },
      ports: [
        { id: id + ":in", width: 0, height: 0, layoutOptions: { "elk.port.side": portSide.input } },
        { id: id + ":out", width: 0, height: 0, layoutOptions: { "elk.port.side": portSide.output } },
      ],
    };
  };
  return {
    id: "canvas",
    layoutOptions: {
      "elk.algorithm": "layered", "elk.direction": direction,
      "elk.edgeRouting": "ORTHOGONAL", "elk.hierarchyHandling": "INCLUDE_CHILDREN",
      "elk.spacing.nodeNode": "32",
      "elk.layered.spacing.nodeNodeBetweenLayers": "64",
      "elk.layered.considerModelOrder.strategy": "NODES_AND_EDGES",
    },
    children: model.groups.map(group => {
      const children = model.nodes.filter(child => child.parentId === group.nodeId).map(child => node(child.id));
      return {
        id: group.nodeId,
        layoutOptions: {
          "elk.algorithm": "layered",
          "elk.direction": direction,
          "elk.padding": `[top=${GROUP_HEADER_HEIGHT},left=${GROUP_PADDING},bottom=${GROUP_PADDING},right=${GROUP_PADDING}]`,
          "elk.spacing.nodeNode": "24",
          "elk.layered.spacing.nodeNodeBetweenLayers": "44",
        },
        children,
      };
    }),
    edges: model.edges.map(edge => ({ id: edge.id, sources: [edge.source + ":out"], targets: [edge.target + ":in"] })),
  };
}
export function readLayout(result: ElkNode): Layout {
  const positions: Layout["positions"] = {};
  const sizes: Layout["sizes"] = {};
  const absolute = new Map<string, { x: number; y: number }>([[result.id, { x: 0, y: 0 }]]);
  const visit = (element: ElkNode, parent = { x: 0, y: 0 }) => {
    for (const child of element.children ?? []) {
      positions[child.id] = { x: child.x ?? 0, y: child.y ?? 0 };
      const offset = { x: parent.x + (child.x ?? 0), y: parent.y + (child.y ?? 0) };
      absolute.set(child.id, offset);
      if (typeof child.width === "number" && typeof child.height === "number") {
        sizes[child.id] = { width: child.width, height: child.height };
      }
      visit(child, offset);
    }
  };
  visit(result);
  const sections: Layout["sections"] = {};
  const readEdges = (element: ElkNode) => {
    for (const edge of element.edges ?? []) {
      const offset = absolute.get(edge.container ?? element.id) ?? { x: 0, y: 0 };
      const translate = (point: { x: number; y: number }) => ({ x: point.x + offset.x, y: point.y + offset.y });
      sections[edge.id] = (edge.sections ?? []).map(section => ({ ...section,
        startPoint: translate(section.startPoint), endPoint: translate(section.endPoint),
        ...(section.bendPoints ? { bendPoints: section.bendPoints.map(translate) } : {}),
      }));
    }
    element.children?.forEach(readEdges);
  };
  readEdges(result);
  return {
    positions,
    sizes,
    sections,
  };
}

/** 同一轮请求合并为最新一次；过期 Worker 立即终止，不能阻塞新布局。 */
export class LayoutQueue {
  private elk?: InstanceType<typeof ELK>;
  private generation = 0;
  private active?: { version: number; elk: InstanceType<typeof ELK>; timeout?: ReturnType<typeof setTimeout> };
  private scheduled = false;
  private pending?: { input: ElkNode; version: number; done(layout: Layout): void; fail(error: Error): void };
  private disposed = false;
  /** 输入变化只废弃当前任务，不把主动取消报告成布局失败。 */
  invalidate() {
    ++this.generation;
    this.pending = undefined;
    const active = this.active;
    this.active = undefined;
    if (active) {
      clearTimeout(active.timeout);
      active.elk.terminateWorker();
      this.elk = undefined;
      recordCanvasEvent("layout-cancelled", { generation: active.version });
    }
  }
  request(input: ElkNode, done: (layout: Layout) => void, fail: (error: Error) => void) {
    if (this.disposed) return;
    this.invalidate();
    this.pending = { input, version: this.generation, done, fail };
    if (this.scheduled) return;
    this.scheduled = true;
    queueMicrotask(() => { this.scheduled = false; this.run(); });
  }
  private run() {
    if (!this.pending || this.disposed) return;
    const task = this.pending; this.pending = undefined;
    let elk: InstanceType<typeof ELK>;
    try { elk = this.elk ??= new ELK({ workerUrl }); }
    catch (error) {
      recordCanvasEvent("layout-failed", { generation: task.version });
      task.fail(error instanceof Error ? error : new Error(String(error)));
      return;
    }
    const active = { version: task.version, elk, timeout: undefined as ReturnType<typeof setTimeout> | undefined };
    this.active = active;
    const failed = (error: unknown) => {
      if (this.active !== active) return;
      this.active = undefined;
      clearTimeout(active.timeout);
      active.elk.terminateWorker();
      this.elk = undefined;
      recordCanvasEvent("layout-failed", { generation: task.version });
      task.fail(error instanceof Error ? error : new Error(String(error)));
    };
    try {
      recordCanvasEvent("layout-requested", { generation: task.version });
      active.timeout = setTimeout(() => failed(new Error("布局超时，保留上次位置")), 8000);
      void active.elk.layout(task.input).then(result => {
        if (this.active !== active || this.disposed) return;
        const layout = readLayout(result);
        clearTimeout(active.timeout);
        this.active = undefined;
        recordCanvasEvent("layout-completed", { generation: task.version });
        task.done(layout);
      }).catch(failed);
    } catch (error) {
      failed(error);
    }
  }
  dispose() { this.disposed = true; this.invalidate(); this.elk?.terminateWorker(); this.elk = undefined; }
}
