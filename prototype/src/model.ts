export type Anchor = {
  file: string;
  symbol: string;
  text: string;
  lineHint: number;
  revision: string;
};
export type Source = {
  id: string;
  name: string;
  role: string;
  revision: string;
  symbol: string;
  lines: string[];
};
export type GraphNode = {
  id: string;
  title: string;
  detail: string;
  anchor: Anchor;
  question?: string;
};
export type Edge = { from: string; to: string; label: string };
export type Hit = {
  nodeId: string;
  seq: number;
  values: Record<string, string>;
  observation: Anchor;
};
export type Trace = { id: string; revision: string; lane: string; hits: Hit[] };
export type Origin = {
  frameId: string;
  nodeId: string;
  sourceAnchor: Anchor;
  traceId: string | null;
  seq: number | null;
};
export type ContextToken = { id: string; origin: Origin };
export type Frame = {
  id: string;
  title: string;
  subtitle: string;
  parentId: string | null;
  origin: Origin | null;
  nodes: GraphNode[];
  edges: Edge[];
  trace?: Trace;
};
export type Visit = { nodeId: string; cursor: number; file: string };
export type SourceMode = "original" | "moved" | "missing" | "ambiguous";

/** 文本匹配模拟未来的 PSI 定位器；存在歧义时不能默认选择某一行。 */
export function resolveAnchor(anchor: Anchor, source: Source) {
  const matches =
    source.id === anchor.file && source.symbol === anchor.symbol
      ? source.lines.flatMap((line, i) =>
          line.trim() === anchor.text.trim() ? [i + 1] : [],
        )
      : [];
  const line = matches.length === 1 ? matches[0] : null;
  const status =
    matches.length > 1
      ? "ambiguous"
      : line === null
        ? "missing"
        : line === anchor.lineHint
          ? "located"
          : "relocated";
  return {
    line,
    status,
    evidenceStale: source.revision !== anchor.revision,
  } as const;
}

/** 值属于一次命中，不属于节点；仅显示当前游标及之前的观测。 */
export function replay(trace: Trace | undefined, cursor: number) {
  const hits = trace?.hits.slice(0, Math.max(0, cursor + 1)) ?? [];
  const latest = new Map<string, Hit>();
  const counts = new Map<string, number>();
  const transitions = new Set<string>();
  hits.forEach((hit, i) => {
    latest.set(hit.nodeId, hit);
    counts.set(hit.nodeId, (counts.get(hit.nodeId) ?? 0) + 1);
    if (i > 0) transitions.add(`${hits[i - 1].nodeId}->${hit.nodeId}`);
  });
  return { latest, counts, transitions, current: hits.at(-1) };
}

export function validateTrace(frame: Frame, trace: Trace) {
  const ids = new Set(frame.nodes.map((node) => node.id));
  const edges = new Set(frame.edges.map((edge) => `${edge.from}->${edge.to}`));
  let previous = 0;
  trace.hits.forEach((hit, i) => {
    if (
      !ids.has(hit.nodeId) ||
      !Number.isSafeInteger(hit.seq) ||
      hit.seq <= previous
    ) {
      throw new Error("命中必须引用已有节点，seq 必须严格递增");
    }
    if (i > 0 && !edges.has(`${trace.hits[i - 1].nodeId}->${hit.nodeId}`)) {
      throw new Error("相邻命中没有已声明的路径，不能推断一条执行边");
    }
    previous = hit.seq;
  });
}

/** 布局由查看端负责；通过已访问节点处理循环，并绘制反馈边。 */
export function layout(nodes: GraphNode[], edges: Edge[]) {
  const positions = new Map<string, { x: number; y: number }>();
  const depth = new Map<string, number>();
  const roots = nodes.filter(
    (node) => !edges.some((edge) => edge.to === node.id),
  );
  const queue = (roots.length ? roots : nodes.slice(0, 1)).map(
    (node) => node.id,
  );
  queue.forEach((id) => depth.set(id, 0));
  for (let i = 0; i < queue.length; i++) {
    const id = queue[i];
    edges
      .filter((edge) => edge.from === id)
      .forEach((edge) => {
        if (!depth.has(edge.to)) {
          depth.set(edge.to, depth.get(id)! + 1);
          queue.push(edge.to);
        }
      });
  }
  nodes.forEach((node) => {
    if (!depth.has(node.id)) depth.set(node.id, 0);
  });
  const levels = [...new Set(depth.values())];
  const widest = Math.max(
    1,
    ...levels.map(
      (level) => nodes.filter((n) => depth.get(n.id) === level).length,
    ),
  );
  const width = Math.max(430, widest * 200 + 40);
  levels.forEach((level) => {
    const row = nodes.filter((node) => depth.get(node.id) === level);
    row.forEach((node, i) =>
      positions.set(node.id, {
        x: (width - row.length * 200) / 2 + i * 200 + 10,
        y: level * 132 + 22,
      }),
    );
  });
  return { positions, width, height: (Math.max(0, ...levels) + 1) * 132 + 8 };
}

export function captureContext(
  frame: Frame,
  visit: Visit,
  id: string,
): ContextToken {
  const node = frame.nodes.find((n) => n.id === visit.nodeId) ?? frame.nodes[0];
  return structuredClone({
    id,
    origin: {
      frameId: frame.id,
      nodeId: node.id,
      sourceAnchor: node.anchor,
      traceId: visit.cursor >= 0 ? (frame.trace?.id ?? null) : null,
      seq: frame.trace?.hits[visit.cursor]?.seq ?? null,
    },
  });
}

export function presentFrame(
  frame: Frame,
  token: ContextToken | null,
  parent?: string | null,
): Frame {
  if (!token && parent === undefined)
    throw new Error("缺少上下文，请先读取上下文或显式创建根问题");
  return {
    ...frame,
    parentId: parent === undefined ? token!.origin.frameId : parent,
    origin: token ? structuredClone(token.origin) : null,
  };
}

export function initialVisit(frame: Frame): Visit {
  return {
    nodeId: frame.nodes[0].id,
    cursor: -1,
    file: frame.nodes[0].anchor.file,
  };
}
