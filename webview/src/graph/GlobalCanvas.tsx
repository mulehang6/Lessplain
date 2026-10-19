import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type CSSProperties, type KeyboardEvent, type MouseEvent as ReactMouseEvent, type ReactNode } from "react";
import {
  Background, MarkerType, ReactFlow, ReactFlowProvider, useReactFlow,
  type Node, type NodeChange,
} from "@xyflow/react";
import type { ElkEdgeSection } from "elkjs/lib/elk-api";
import type { Spec } from "@json-render/core";
import { useShallow } from "zustand/react/shallow";
import { beginAnnotation, currentView, readPointer, saveGraph, setLessonValueAtPointer, useApp } from "../runtime/store";
import { call, type NavigationRequest } from "../runtime/bridge";
import type { GraphBrowse, GraphOptions, View } from "../runtime/types";
import { CanvasButton } from "../components/CanvasButton";
import { elementAnchor } from "../components/popupGeometry";
import { useWorkspace, type GraphController } from "../components/WorkspaceContext";
import { canvasBrowseKey } from "../components/WorkspaceBrowseState";
import { centeredViewport, fitBoundsViewport, ViewportAnimator, wheelPanDelta, wheelViewport, type Viewport } from "./interaction";
import { buildCanvasModel, graphNodeId, type CanvasEdgeDefinition, type CanvasModel, type CanvasNodeDefinition } from "./canvasModel";
import { LayoutQueue, layoutInput, type Layout, type Measure } from "./layout";
import { mergeMeasuredSizes, retainMeasuredSizes } from "./measurements";
import { stepChoices, startingNode, upstreamEdges } from "./navigation";
import { canvasEdgeTypes, canvasNodeTypes, type CanvasFlowEdge, type CanvasFlowNode, type CompanionFlowNode, type LessonFlowNode } from "./CanvasNodes";
import { formatGraphNodeContext } from "./nodeContext";
import { repairSections } from "./edgeRepair";
import { useEdgeRepair } from "./useEdgeRepair";
import { branchNodes, cyclePreview } from "./navigation";
import { dismissTooltips } from "../components/tooltipInput";
import { NodeActionsProvider } from "./NodeActions";
import { useCanvasWidth } from "./useCanvasWidth";
import { preserveMeasuredLayout } from "./layoutGeometry";
import { componentContentKey } from "./componentGeometry";
import { recordCanvasEvent, recordWheel } from "./diagnostics";
import { openCodeSample } from "../runtime/samples";
import "./graph.css";

type Props = { spec: Spec; view: View; renderCompanion(elementId: string): ReactNode };
type Point = { x: number; y: number };
type MenuState = { kind: "canvas" | "node" | "edge"; x: number; y: number; id?: string };

const processedNavigationRequests = new Set<string>();

/** One provider, viewport, topology, camera and layout for all clusters on the page. */
export function GlobalCanvas(props: Props) {
  return <NodeActionsProvider><ReactFlowProvider><GlobalCanvasScene {...props} /></ReactFlowProvider></NodeActionsProvider>;
}

function GlobalCanvasScene({ spec, view, renderCompanion }: Props) {
  const model = useMemo(() => buildCanvasModel(spec), [spec]);
  const branches = useMemo(() => branchNodes(model), [model]);
  const viewRef = view.ref;
  const host = window.__lessplainBoot?.host ?? (window.__lessplainBoot?.ref ? "editor" : "toolwindow");
  const browseKey = canvasBrowseKey(host);
  const saved = useApp(state => state.snapshot.browse[viewRef]?.[browseKey]) as GraphBrowse | undefined;
  const validIds = useMemo(() => new Set(model.nodes.filter(node => node.kind === "graph").map(node => node.id)), [model]);
  const allNodeIds = useMemo(() => new Set(model.nodes.map(node => node.id)), [model]);
  const [sizes, setSizes] = useState<Measure[]>([]);
  const savedTrail = (saved?.trail ?? []).filter(id => validIds.has(id));
  // 曾被错误投影成纯组件的历史没有有效图路径；旧镜头/手动位置不适用于恢复后的图。
  const compatibleBrowse = validIds.size === 0 || savedTrail.length > 0;
  const initialTrail = savedTrail.length ? savedTrail : [startingNode(model) || model.nodes.find(node => node.kind === "graph")?.id].filter((id): id is string => Boolean(id));
  const [trail, setTrail] = useState<string[]>(initialTrail);
  const trailRef = useRef(trail);
  const confirmedSelection = useRef<string | undefined>(undefined);
  trailRef.current = trail;
  const [choices, setChoices] = useState<{ direction: "previous" | "next"; edges: CanvasEdgeDefinition[] }>();
  const [preview, setPreview] = useState<string>();
  const [menuState, setMenuState] = useState<MenuState>();
  const [layout, setLayout] = useState<Layout>();
  const [error, setError] = useState<string>();
  const [layoutRevision, setLayoutRevision] = useState(0);
  const [manualRevision, setManualRevision] = useState(0);
  const [options, setOptions] = useState<GraphOptions>(() => saved?.options ?? initialOptions(model));
  const optionsRef = useRef(options);
  optionsRef.current = options;
  const current = trail.at(-1);
  const graphIds = useMemo(() => Object.keys(model.graphProps), [model]);
  const externalSelections = useApp(useShallow(state => graphIds.map(elementId =>
    readPointer(state.lesson, model.graphProps[elementId].selectionPath ?? defaultSelectionPath(elementId)))));
  const previousSelections = useRef<unknown[] | undefined>(undefined);
  const currentDefinition = current ? model.nodes.find(node => node.id === current) : undefined;
  const shell = useRef<HTMLDivElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const navigationStateSignature = useRef("");
  const cameraInteracted = useRef(false);
  const animator = useRef<ViewportAnimator | null>(null);
  animator.current ??= new ViewportAnimator();
  const animating = useRef(false);
  const firstLayout = useRef(true);
  const fitAfterLayout = useRef(false);
  const restoredViewport = useRef(compatibleBrowse ? saved?.viewport : undefined);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const queue = useRef<LayoutQueue | null>(null);
  const width = useCanvasWidth(shell);
  const [contentWidth, setContentWidth] = useState(0);
  const [autoDirection, setAutoDirection] = useState<"RIGHT" | "DOWN">("RIGHT");
  useLayoutEffect(() => {
    if (contentWidth || !width) return;
    setContentWidth(width);
    setAutoDirection(width < 800 ? "DOWN" : "RIGHT");
  }, [contentWidth, width]);
  const direction = options.direction === "AUTO" ? autoDirection : options.direction;
  const appliedLayoutKey = useRef<string | undefined>(undefined);
  const appliedDirection = useRef<string | undefined>(undefined);
  const appliedStructure = useRef<string | undefined>(undefined);
  const structureKey = useMemo(() => JSON.stringify([
    model.nodes.map(node => [node.id, node.parentId, node.kind]),
    model.edges.map(edge => [edge.id, edge.source, edge.target]),
  ]), [model]);
  const manual = useRef<Record<string, Point>>(compatibleBrowse ? saved?.positions ?? {} : {});
  const flow = useReactFlow<CanvasFlowNode, CanvasFlowEdge>();
  const edgeRepair = useEdgeRepair(flow, model, layout, direction === "DOWN");
  const workspace = useWorkspace();
  // 控制器只依赖稳定动作，避免发布控制器后又因 context 值更新重建自己。
  const selectCluster = workspace?.selectCluster;
  const followupViews = useApp(state => state.snapshot.views);
  const followupsByNode = useMemo(() => {
    const grouped = new Map<string, { ref: string; label: string }[]>();
    followupViews.filter(item => item.origin.viewRef === viewRef && item.origin.elementId && item.origin.nodeId)
      .forEach(item => {
        const key = JSON.stringify([item.origin.elementId, item.origin.nodeId]);
        grouped.set(key, [...(grouped.get(key) ?? []), { ref: item.ref, label: item.document.title ?? item.document.question }]);
      });
    return grouped;
  }, [followupViews, viewRef]);

  const persist = useCallback((positions = manual.current, path = trailRef.current, preferences = optionsRef.current) => {
    saveGraph(browseKey, { positions, trail: path, options: preferences, viewport: flow.getViewport() });
  }, [browseKey, flow]);
  const schedulePersist = useCallback(() => {
    clearTimeout(timer.current);
    timer.current = setTimeout(() => persist(), 160);
  }, [persist]);
  const cancelCamera = useCallback(() => {
    animator.current?.cancel();
    animating.current = false;
  }, []);
  const animateCamera = useCallback((target: Viewport) => {
    cancelCamera();
    animating.current = true;
    animator.current?.animate(flow.getViewport(), target, viewport => {
      void flow.setViewport(viewport, { duration: 0 });
    }, 240, () => {
      animating.current = false;
      schedulePersist();
    });
  }, [cancelCamera, flow, schedulePersist]);

  const centerNodes = useCallback((ids: string[], contain = false) => {
    const bounds = shell.current?.getBoundingClientRect();
    if (!bounds || !ids.length) return;
    const nodes = ids.map(id => flow.getNode(id)).filter((node): node is NonNullable<typeof node> => Boolean(node));
    if (!nodes.length) return;
    const rects = nodes.map(node => {
      let x = node.position.x, y = node.position.y;
      if (node.parentId) {
        const parent = flow.getNode(node.parentId);
        if (parent) { x += parent.position.x; y += parent.position.y; }
      }
      const w = node.measured?.width ?? node.width ?? 220;
      const h = node.measured?.height ?? node.height ?? 80;
      return { left: x, top: y, right: x + w, bottom: y + h };
    });
    const left = Math.min(...rects.map(rect => rect.left)), right = Math.max(...rects.map(rect => rect.right));
    const top = Math.min(...rects.map(rect => rect.top)), bottom = Math.max(...rects.map(rect => rect.bottom));
    const currentViewport = flow.getViewport();
    const target = contain
      ? fitBoundsViewport(currentViewport, [{ left, top, right, bottom }], { width: bounds.width, height: bounds.height })
      : centeredViewport(currentViewport, { x: (left + right) / 2, y: (top + bottom) / 2 },
        { width: bounds.width, height: bounds.height });
    if (!target) return;
    animateCamera(target);
  }, [animateCamera, flow]);

  const fit = useCallback(() => {
    cancelCamera();
    void flow.fitView({ padding: 0.16, maxZoom: 1, duration: 160 }).then(schedulePersist);
  }, [cancelCamera, flow, schedulePersist]);
  const zoomBy = useCallback((mode: "in" | "out") => {
    cameraInteracted.current = true;
    cancelCamera();
    void (mode === "in" ? flow.zoomIn({ duration: 120 }) : flow.zoomOut({ duration: 120 })).then(schedulePersist);
  }, [cancelCamera, flow, schedulePersist]);
  const resetZoom = useCallback(() => {
    cameraInteracted.current = true;
    cancelCamera();
    void flow.zoomTo(1, { duration: 120 }).then(schedulePersist);
  }, [cancelCamera, flow, schedulePersist]);

  const selectionPath = useCallback((elementId: string) => {
    const configured = model.graphProps[elementId]?.selectionPath;
    return configured ?? defaultSelectionPath(elementId);
  }, [model]);
  const updateNodeSelection = useCallback((definition: CanvasNodeDefinition) => {
    if (definition.kind !== "graph") return;
    setLessonValueAtPointer(selectionPath(definition.elementId), definition.nodeId);
  }, [selectionPath]);

  const navigateSource = useCallback((definition: CanvasNodeDefinition) => {
    if (definition.sample) { openCodeSample(definition.sample, definition.elementId, definition.nodeId); return; }
    if (!definition.source) return;
    const state = useApp.getState();
    const currentView = state.snapshot.views.find(item => item.ref === state.selected);
    if (!currentView?.sources[definition.source]) return;
    const previous = stepChoices(model, definition.id, "previous").length > 0;
    const next = stepChoices(model, definition.id, "next").length > 0;
    state.focus(definition.elementId, definition.nodeId, definition.source);
    const snapshot = freezeSnapshot(structuredClone(useApp.getState().lesson));
    void call("source", {
      ref: currentView.ref,
      source: definition.source,
      elementId: definition.elementId,
      nodeId: definition.nodeId,
      nodeLabel: definition.label,
      revision: currentView.revision,
      state: snapshot,
      canPrevious: previous,
      canNext: next,
    }).catch(error => useApp.getState().setError(error instanceof Error ? error.message : String(error)));
  }, [model]);

  const choose = useCallback((nodeId: string, append = false, frozenStartId?: string, recenter = false) => {
    if (!validIds.has(nodeId)) return;
    const definition = model.nodes.find(node => node.id === nodeId);
    if (!definition || definition.kind !== "graph") return;
    const activePath = trailRef.current;
    if (!append && activePath.at(-1) === nodeId && confirmedSelection.current === nodeId) {
      if (recenter && optionsRef.current.follow) centerNodes([nodeId]);
      return;
    }
    const sameCurrent = activePath.at(-1) === nodeId;
    const firstConfirmation = confirmedSelection.current === undefined;
    confirmedSelection.current = nodeId;
    const base = frozenStartId ? [frozenStartId] : activePath;
    const path = append ? [...base, nodeId] : [nodeId];
    trailRef.current = path;
    setTrail(path);
    setChoices(undefined);
    setPreview(undefined);
    setMenuState(undefined);
    const state = useApp.getState();
    const focusChanged = state.elementId !== definition.elementId
      || state.nodeId !== definition.nodeId || state.sourceId !== definition.source;
    const hasSource = definition.source ? currentView()?.sources[definition.source] : definition.sample && currentView()?.document.codeSamples?.[definition.sample.id];
    if (optionsRef.current.jumpToSource && hasSource && focusChanged) navigateSource(definition);
    else if (focusChanged) state.focus(definition.elementId, definition.nodeId, definition.source);
    updateNodeSelection(definition);
    persist(manual.current, path);
    if (optionsRef.current.follow && (!sameCurrent || firstConfirmation)) centerNodes([nodeId]);
  }, [centerNodes, currentView, model, navigateSource, persist, updateNodeSelection, validIds]);

  const showChoices = useCallback((stepDirection: "previous" | "next", edges: CanvasEdgeDefinition[], startId: string) => {
    setChoices({ direction: stepDirection, edges });
    setPreview(edges[0]?.id);
    setMenuState(undefined);
    const candidateIds = edges.map(edge => stepDirection === "next" ? edge.target : edge.source);
    centerNodes(candidateIds, true);
    dismissTooltips();
    shell.current?.focus({ preventScroll: true });
  }, [centerNodes]);

  const stepFrom = useCallback((startId: string, stepDirection: "previous" | "next", frozen = false) => {
    const edges = stepChoices(model, startId, stepDirection);
    if (frozen) {
      trailRef.current = [startId];
      setTrail([startId]);
      setChoices(undefined);
      setPreview(undefined);
      const sourceNode = model.nodes.find(node => node.id === startId);
      if (sourceNode?.kind === "graph") {
        useApp.getState().focus(sourceNode.elementId, sourceNode.nodeId, sourceNode.source);
        updateNodeSelection(sourceNode);
        const group = model.groups.find(item => item.nodeId === sourceNode.parentId);
        if (group) selectCluster?.(group.id);
      }
      persist(manual.current, [startId]);
    }
    const nextIds = new Set(edges.map(edge => stepDirection === "next" ? edge.target : edge.source));
    if (nextIds.size === 1) {
      const nextId = stepDirection === "next" ? edges[0].target : edges[0].source;
      choose(nextId, true, frozen ? startId : undefined);
    } else if (edges.length > 1) {
      showChoices(stepDirection, edges, startId);
    } else if (frozen) {
      centerNodes([startId]);
    }
  }, [centerNodes, choose, model, persist, showChoices, updateNodeSelection, selectCluster]);

  const selectChoice = useCallback((edgeId: string) => {
    const choice = choices?.edges.find(edge => edge.id === edgeId);
    if (!choice || !choices) return;
    choose(choices.direction === "next" ? choice.target : choice.source, true);
  }, [choices, choose]);

  const focusGroup = useCallback((groupId: string) => {
    const group = model.groups.find(candidate => candidate.id === groupId);
    if (!group) return;
    centerNodes([group.nodeId], true);
  }, [centerNodes, model]);

  const changeOptions = useCallback((next: GraphOptions) => {
    const previous = optionsRef.current;
    optionsRef.current = next;
    setOptions(next);
    if (next.direction === "AUTO" && previous.direction !== "AUTO") setAutoDirection(width < 800 ? "DOWN" : "RIGHT");
    persist(manual.current, trailRef.current, next);
    if (next.follow && !previous.follow && trailRef.current.at(-1)) centerNodes([trailRef.current.at(-1)!]);
  }, [centerNodes, persist, width]);
  const arrange = useCallback(() => {
    setContentWidth(width);
    setAutoDirection(width < 800 ? "DOWN" : "RIGHT");
    manual.current = {};
    setManualRevision(value => value + 1);
    fitAfterLayout.current = true;
    setLayoutRevision(value => value + 1);
    persist({});
  }, [persist, width]);

  const controller = useMemo<GraphController>(() => ({
    options,
    nodes: model.nodes,
    edges: model.edges,
    currentNodeId: current,
    choices,
    preview,
    canPrevious: current ? stepChoices(model, current, "previous").length > 0 : false,
    canNext: current ? stepChoices(model, current, "next").length > 0 : false,
    onPreview: setPreview,
    onChoose: selectChoice,
    onCancelChoice: () => { setChoices(undefined); setPreview(undefined); },
    zoomIn: () => zoomBy("in"),
    zoomOut: () => zoomBy("out"),
    fit,
    resetZoom,
    arrange,
    stepPrevious: () => current && stepFrom(current, "previous"),
    stepNext: () => current && stepFrom(current, "next"),
    focusGroup,
    getOptions: () => optionsRef.current,
    setOptions: changeOptions,
  }), [arrange, changeOptions, choices, current, fit, focusGroup, model, options, preview, resetZoom, selectChoice, stepFrom, zoomBy]);
  const controllerRef = useRef(controller);
  controllerRef.current = controller;

  const sizesById = useMemo(() => new Map(sizes.map(size => [size.id, size])), [sizes]);
  const measuredAll = useMemo(() => model.nodes.length > 0 && model.nodes.filter(node => node.kind !== "group").every(definition => {
    const size = sizesById.get(definition.id);
    return Boolean(size && size.width > 0 && size.height > 0);
  }), [model.nodes, sizesById]);
  // 父框测量不参与 ELK 输入；四舍五入消除不影响显示的亚像素尺寸噪声。
  const layoutSizeKey = model.nodes.filter(node => node.kind !== "group").map(node => {
    const size = sizesById.get(node.id);
    return [node.id, Math.round(size?.width ?? 0), Math.round(size?.height ?? 0)];
  });
  const measuredKey = JSON.stringify(layoutSizeKey);
  const input = useMemo(() => measuredAll ? layoutInput(model, JSON.parse(measuredKey).map(([id, width, height]: [string, number, number]) => ({ id, width, height })), direction) : undefined,
    [direction, measuredAll, measuredKey, model]);
  const serializedInput = input && contentWidth ? JSON.stringify(input) : undefined;
  // 元数据刷新可能创建等价 model；稳定 ELK 输入对象，不能中断同一任务。
  const stableInput = useMemo(() => serializedInput ? JSON.parse(serializedInput) : undefined, [serializedInput]);
  const inputKey = serializedInput ? layoutRevision + ":" + serializedInput : undefined;
  const onNodesChange = useCallback((changes: NodeChange<CanvasFlowNode>[]) => {
    const measured = changes.filter(change => change.type === "dimensions");
    if (measured.length) recordCanvasEvent("measure", { count: measured.length });
    setSizes(current => mergeMeasuredSizes(current, changes));
  }, []);

  const nodeData = useMemo((): CanvasFlowNode[] => {
    const groupByElement = new Map(model.groups.map(group => [group.id, group]));
    return model.nodes.map(definition => {
      if (definition.kind === "group") {
        const group = groupByElement.get(definition.elementId);
        return {
          id: definition.id,
          type: "cluster",
          position: manual.current[definition.id] ?? { x: 0, y: 0 },
          style: { width: 360, height: 220, zIndex: 0 },
          selectable: false,
          draggable: true,
          dragHandle: ".canvas-cluster-title",
          data: { title: group?.title ?? definition.label, clusterId: definition.elementId, elementId: definition.elementId },
        } as Node<{ title: string; clusterId: string; elementId: string }, "cluster">;
      }
      if (definition.kind === "companion") {
        return {
          id: definition.id,
          type: "companion",
          parentId: definition.parentId,
          extent: definition.parentId ? "parent" : undefined,
          position: manual.current[definition.id] ?? { x: 0, y: 0 },
          draggable: true,
          data: { elementId: definition.elementId, label: definition.label,
            contentKey: componentContentKey(spec, definition.elementId), content: renderCompanion(definition.elementId) },
        } as CompanionFlowNode;
      }
      return {
        id: definition.id,
        type: "lesson",
        parentId: definition.parentId,
        extent: definition.parentId ? "parent" : undefined,
        position: manual.current[definition.id] ?? { x: 0, y: 0 },
        draggable: true,
        data: {
          elementId: definition.elementId,
          nodeId: definition.nodeId,
          label: definition.label,
          source: definition.source,
          roles: definition.roles,
          outcome: definition.outcome,
          branch: branches.has(definition.id),
          down: direction === "DOWN",
          followups: [],
          onAnnotate: anchor => {
            useApp.getState().focus(definition.elementId, definition.nodeId);
            beginAnnotation(undefined, elementAnchor(anchor));
          },
          onCopy: () => copyNodeContext(definition.elementId, definition.nodeId, definition.label, definition.source),
          onSelectFollowup: ref => useApp.getState().select(ref),
        },
      } as LessonFlowNode;
    });
  }, [branches, direction, model, renderCompanion, spec]);

  const definitionsById = useMemo(() => new Map(model.nodes.map(node => [node.id, node])), [model]);
  const initialNodes = useMemo(() => nodeData, [nodeData]);
  const initialEdges = useMemo(() => model.edges.map(edge => toFlowEdge(edge, direction)), [model.edges, direction]);
  const candidateNodeIds = useMemo(() => new Set(choices?.edges.map(edge => choices.direction === "next" ? edge.target : edge.source) ?? []), [choices]);

  useLayoutEffect(() => {
    queue.current = new LayoutQueue();
    return () => {
      queue.current?.dispose();
      queue.current = null;
      animator.current?.cancel();
      clearTimeout(timer.current);
    };
  }, []);
  useEffect(() => {
    setSizes(current => retainMeasuredSizes(current, allNodeIds));
  }, [allNodeIds]);
  useEffect(() => workspace?.registerController("canvas", () => controllerRef.current), [workspace?.registerController]);
  useEffect(() => { workspace?.refreshController("canvas"); }, [workspace?.refreshController, controller]);

  useEffect(() => {
    if (!model.nodes.length) return;
    flow.setNodes(existing => {
      const oldById = new Map(existing.map(node => [node.id, node]));
      return nodeData.map((node, index) => {
        const old = oldById.get(node.id);
        return {
          ...old,
          ...node,
          // 元数据刷新不能覆盖布局尺寸，否则 extent:parent 会瞬间夹紧全部子节点。
          style: old?.type === node.type ? old.style ?? node.style : node.style,
          position: manual.current[node.id] ?? old?.position ?? node.position ?? { x: index * 240, y: 0 },
        } as CanvasFlowNode;
      });
    });
  }, [direction, flow, model, nodeData]);

  useEffect(() => {
    flow.setNodes(nodes => nodes.map(node => {
      if (node.type !== "lesson") return node;
      const followups = followupsByNode.get(JSON.stringify([node.data.elementId, node.data.nodeId])) ?? [];
      if (node.data.followups.length === followups.length && node.data.followups.every((item, index) =>
        item.ref === followups[index].ref && item.label === followups[index].label)) return node;
      return { ...node, data: { ...node.data, followups } };
    }));
  }, [flow, followupsByNode, nodeData]);

  useLayoutEffect(() => {
    if (!stableInput || !queue.current || appliedLayoutKey.current === inputKey) return;
    queue.current.invalidate();
    // 等当前帧所有 ResizeObserver 测量合并，不增加停拖后的固定等待。
    const frame = requestAnimationFrame(() => queue.current?.request(stableInput, next => {
      const sameStructure = layout && appliedStructure.current === structureKey
        && Object.keys(layout.positions).length === model.nodes.length
        && model.nodes.every(node => layout.positions[node.id]);
      const result = sameStructure && appliedDirection.current === direction && !fitAfterLayout.current
        ? preserveMeasuredLayout(model, next, layout, flow.getNodes(), direction === "DOWN") : next;
      if (result !== next) {
        // 局部避让或组内尺寸差同样更新手动坐标，否则下一次渲染会用旧坐标覆盖修正。
        manual.current = Object.fromEntries(Object.entries(manual.current).map(([id, point]) => [id, result.positions[id] ?? point]));
      }
      appliedLayoutKey.current = inputKey;
      appliedDirection.current = direction;
      appliedStructure.current = structureKey;
      recordCanvasEvent("layout-applied", { nodes: Object.keys(result.positions).length });
      setLayout(result);
      setError(undefined);
      flow.setNodes(nodes => nodes.map(node => {
        const size = result.sizes[node.id];
        return {
          ...node,
          position: manual.current[node.id] ?? result.positions[node.id] ?? node.position,
          ...(node.type === "cluster" && size ? { style: { ...node.style, width: size.width, height: size.height } } : {}),
          ...(node.type === "lesson" ? { data: { ...node.data, down: direction === "DOWN" } } : {}),
        } as CanvasFlowNode;
      }));
      if (firstLayout.current) {
        firstLayout.current = false;
        const restored = restoredViewport.current;
        restoredViewport.current = undefined;
        requestAnimationFrame(() => {
          if (restored) void flow.setViewport(restored, { duration: 0 });
          else if (!cameraInteracted.current) fit();
        });
      } else if (fitAfterLayout.current) {
        fitAfterLayout.current = false;
        requestAnimationFrame(fit);
      }
    }, failure => setError(failure.message)));
    return () => { cancelAnimationFrame(frame); queue.current?.invalidate(); };
  }, [direction, fit, flow, stableInput, inputKey]);

  useEffect(() => {
    const highlighted = !choices && current ? upstreamEdges(model, current) : new Set<string>();
    const choiceEdges = new Set(choices?.edges.map(edge => edge.id) ?? []);
    const sections = layout ? repairSections(model, layout, flow.getNodes(), direction === "DOWN") : {};
    flow.setEdges(model.edges.map(edge => ({
      ...toFlowEdge(edge, direction),
      selected: highlighted.has(edge.id),
      animated: options.flow && (highlighted.has(edge.id) || choiceEdges.has(edge.id)),
      markerEnd: { type: MarkerType.ArrowClosed, color: preview === edge.id ? "var(--diagram-purple)" : choiceEdges.has(edge.id) ? "var(--diagram-warning)" : highlighted.has(edge.id) ? "var(--diagram-purple)" : "var(--muted-foreground)" },
      data: { ...toFlowEdge(edge, direction).data, sections: sections[edge.id] ?? [],
        automatic: Object.keys(manual.current).length === 0, down: direction === "DOWN",
        preview: preview === edge.id,
        candidate: choiceEdges.has(edge.id),
        onOpenEvidence: () => openEdgeEvidence(edge, model, navigateSource) },
    })) as CanvasFlowEdge[]);
    const previewEdge = choices?.edges.find(edge => edge.id === preview);
    const previewNode = choices && previewEdge ? (choices.direction === "next" ? previewEdge.target : previewEdge.source) : undefined;
    flow.setNodes(nodes => nodes.map(node => {
      const selected = !choices && node.id === current;
      if (node.type !== "lesson") return node.selected === selected ? node : { ...node, selected };
      const attention = node.id === previewNode ? "preview" : candidateNodeIds.has(node.id) ? "candidate" : undefined;
      const choiceEdge = choices?.edges.find(edge => (choices.direction === "next" ? edge.target : edge.source) === node.id);
      return { ...node, selected, data: { ...node.data, attention, onPreview: choiceEdge ? () => setPreview(choiceEdge.id) : undefined } };
    }) as CanvasFlowNode[]);
  }, [candidateNodeIds, choices, current, direction, flow, layout, manualRevision, model, navigateSource, options.flow, preview]);

  useEffect(() => {
    if (!current) return;
    const currentState = useApp.getState();
    const stateView = currentState.snapshot.views.find(item => item.ref === currentState.selected);
    const definition = definitionsById.get(current);
    if (!stateView || definition?.kind !== "graph") return;
    const previous = stepChoices(model, current, "previous").length > 0;
    const next = stepChoices(model, current, "next").length > 0;
    const signature = JSON.stringify([stateView.ref, stateView.revision, definition.elementId, definition.nodeId, previous, next]);
    if (navigationStateSignature.current === signature) return;
    navigationStateSignature.current = signature;
    void call("navigationState", { ref: stateView.ref, revision: stateView.revision, elementId: definition.elementId,
      nodeId: definition.nodeId, canPrevious: previous, canNext: next }).catch(() => {});
  }, [current, definitionsById, model]);

  useEffect(() => {
    const previous = previousSelections.current;
    previousSelections.current = externalSelections;
    for (const [index, elementId] of graphIds.entries()) {
      const selection = externalSelections[index];
      if (previous ? Object.is(previous[index], selection) : savedTrail.length > 0) continue;
      if (typeof selection !== "string") continue;
      const selectedId = graphNodeId(elementId, selection);
      if (validIds.has(selectedId) && selectedId !== trailRef.current.at(-1)) {
        choose(selectedId);
        return;
      }
    }
  }, [choose, externalSelections, graphIds, validIds]);

  useEffect(() => {
    const onRequest = (event: Event) => {
      const detail = (event as CustomEvent<NavigationRequest>).detail;
      if (!detail?.id || processedNavigationRequests.has(detail.id)) return;
      processedNavigationRequests.add(detail.id);
      if (processedNavigationRequests.size > 256) processedNavigationRequests.delete(processedNavigationRequests.values().next().value!);
      const state = useApp.getState();
      const activeView = state.snapshot.views.find(item => item.ref === state.selected);
      if (!activeView || detail.viewRef !== activeView.ref || detail.revision !== activeView.revision) return;
      const frozenNodeId = graphNodeId(detail.elementId, detail.nodeId);
      const frozenNode = definitionsById.get(frozenNodeId);
      if (frozenNode?.kind !== "graph") return;
      stepFrom(frozenNodeId, detail.direction, true);
    };
    window.addEventListener("lessplain:navigate", onRequest);
    return () => window.removeEventListener("lessplain:navigate", onRequest);
  }, [definitionsById, stepFrom, viewRef, view.revision]);

  useEffect(() => {
    if (!menuState) return;
    menu.current?.querySelector<HTMLButtonElement>("button")?.focus();
    const dismiss = (event: PointerEvent) => {
      if (!menu.current?.contains(event.target as globalThis.Node)) setMenuState(undefined);
    };
    document.addEventListener("pointerdown", dismiss);
    return () => document.removeEventListener("pointerdown", dismiss);
  }, [menuState]);

  const handleWheel = useCallback((event: WheelEvent) => {
    if (!shell.current?.contains(event.target as globalThis.Node)) return;
    const receivedAt = performance.now();
    const target = event.target as HTMLElement;
    if (target.closest(".graph-popup, .graph-context-menu, .graph-node-details, button, input, textarea, select, iframe")) {
      recordWheel(event, { receivedAt, route: 0 });
      return;
    }
    const bounds = shell.current.getBoundingClientRect();
    const { x: nativeX, y: nativeY } = wheelPanDelta(event, bounds);
    for (let node: HTMLElement | null = target; node && node !== shell.current; node = node.parentElement) {
      const css = getComputedStyle(node);
      const vertical = /auto|scroll/.test(css.overflowY) && node.scrollHeight > node.clientHeight &&
        (nativeY > 0 ? node.scrollTop + node.clientHeight < node.scrollHeight : nativeY < 0 && node.scrollTop > 0);
      const horizontal = /auto|scroll/.test(css.overflowX) && node.scrollWidth > node.clientWidth &&
        (nativeX > 0 ? node.scrollLeft + node.clientWidth < node.scrollWidth : nativeX < 0 && node.scrollLeft > 0);
      if (!event.ctrlKey && (vertical || horizontal)) {
        recordWheel(event, { receivedAt, route: 1 });
        return;
      }
    }
    event.preventDefault();
    cameraInteracted.current = true;
    cancelCamera();
    const before = flow.getViewport();
    const next = wheelViewport(before, bounds, event, 0.08, 2, window.__lessplainBoot?.horizontalWheelSign ?? 1);
    if (next) {
      void flow.setViewport(next, { duration: 0 });
      schedulePersist();
    }
    const after = flow.getViewport();
    recordWheel(event, { receivedAt, route: 2, beforeX: before.x, beforeY: before.y,
      beforeZoom: before.zoom, afterX: after.x, afterY: after.y, afterZoom: after.zoom });
  }, [cancelCamera, flow, schedulePersist]);
  useEffect(() => {
    const element = shell.current;
    if (!element) return;
    element.addEventListener("wheel", handleWheel, { passive: false });
    return () => element.removeEventListener("wheel", handleWheel);
  }, [handleWheel]);

  const contextAt = (event: { preventDefault(): void; stopPropagation(): void; clientX: number; clientY: number }, kind: MenuState["kind"], id?: string) => {
    event.preventDefault(); event.stopPropagation();
    cancelCamera();
    setMenuState({ kind, id, x: event.clientX, y: event.clientY });
  };
  const focusDefinition = (definition: CanvasNodeDefinition) => {
    useApp.getState().focus(definition.elementId, definition.kind === "graph" ? definition.nodeId : undefined,
      definition.kind === "graph" ? definition.source : undefined);
    if (definition.parentId) {
      const group = model.groups.find(item => item.nodeId === definition.parentId);
      if (group) workspace?.selectCluster(group.id);
    }
  };
  const onNodeClick = (event: ReactMouseEvent, node: CanvasFlowNode) => {
    const definition = definitionsById.get(node.id);
    // 组件内部操作保留镜头；只有点击组件本体才走画布跟焦。
    if (definition?.kind === "companion" && event.target instanceof Element
      && event.target.closest("button,a,input,textarea,select,[role=tab],[role=combobox],[role=option],[role=switch],[role=slider],[contenteditable=true]")) return;
    if (!definition || definition.kind !== "graph") {
      if (definition) {
        focusDefinition(definition);
        if (definition.kind === "group") workspace?.activateCluster(definition.elementId);
        else if (optionsRef.current.follow) centerNodes([node.id]);
      }
      return;
    }
    const parentGroup = model.groups.find(group => group.nodeId === definition.parentId);
    if (parentGroup) workspace?.selectCluster(parentGroup.id);
    const candidates = choices?.edges.filter(edge => (choices.direction === "next" ? edge.target : edge.source) === node.id);
    if (candidates?.length) selectChoice(candidates.find(edge => edge.id === preview)?.id ?? candidates[0].id);
    else choose(node.id, false, undefined, true);
  };
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const target = event.target as HTMLElement;
    if (target.closest("button, input, textarea, select, [contenteditable=true]")) return;
    if (choices) {
      if (event.key === "Escape") { event.preventDefault(); setChoices(undefined); setPreview(undefined); }
      else if (["ArrowRight", "ArrowDown", "ArrowLeft", "ArrowUp"].includes(event.key)) {
        event.preventDefault(); setPreview(cyclePreview(choices.edges, preview, ["ArrowRight", "ArrowDown"].includes(event.key) ? 1 : -1));
      } else if (event.key === "Enter") { event.preventDefault(); if (preview) selectChoice(preview); }
      return;
    }
    if (event.key === "ArrowRight" || event.key === "ArrowDown") { event.preventDefault(); if (current) stepFrom(current, "next"); }
    else if (event.key === "ArrowLeft" || event.key === "ArrowUp") { event.preventDefault(); if (current) stepFrom(current, "previous"); }
    else if (event.key === "Enter" && currentDefinition?.kind === "graph") {
      event.preventDefault();
      {
        useApp.getState().focus(currentDefinition.elementId, currentDefinition.nodeId);
        const node = [...(shell.current?.querySelectorAll<HTMLElement>(".lesson-node") ?? [])]
          .find(element => element.dataset.elementId === currentDefinition.elementId && element.dataset.nodeId === currentDefinition.nodeId);
        const anchor = node?.querySelector(".node-annotation") ?? node;
        beginAnnotation(undefined, anchor ? elementAnchor(anchor) : undefined);
      }
    }
  };
  const menuNode = menuState?.id ? definitionsById.get(menuState.id) : undefined;
  const menuEdge = menuState?.id ? model.edges.find(edge => edge.id === menuState.id) : undefined;
  const menuStyle: CSSProperties | undefined = menuState ? {
    left: Math.min(menuState.x, Math.max(8, window.innerWidth - 236)),
    top: Math.min(menuState.y, Math.max(8, window.innerHeight - 220)),
  } : undefined;

  return <div ref={shell} className="global-canvas-shell" tabIndex={0} aria-label="统一关系画布" onKeyDown={onKeyDown}
    style={{ "--canvas-content-width": contentWidth ? contentWidth + "px" : "100vw" } as CSSProperties}
    onPointerDownCapture={event => {
      if (event.button === 0 || event.button === 2) {
        cameraInteracted.current = true;
        cancelCamera();
      }
    }}>
    <ReactFlow<CanvasFlowNode, CanvasFlowEdge> defaultNodes={initialNodes} defaultEdges={initialEdges}
      nodeTypes={canvasNodeTypes} edgeTypes={canvasEdgeTypes} onNodesChange={onNodesChange} onNodeClick={onNodeClick}
      onEdgeClick={(_, edge) => {
        const candidate = choices?.edges.some(item => item.id === edge.id);
        if (candidate) selectChoice(edge.id);
        else edge.data?.onOpenEvidence?.();
      }}
      onNodeContextMenu={(event, node) => contextAt(event, definitionsById.get(node.id)?.kind === "graph" ? "node" : "canvas", node.id)}
      onEdgeContextMenu={(event, edge) => contextAt(event, "edge", edge.id)}
      onPaneContextMenu={event => contextAt(event, "canvas")}
      onPaneClick={() => {
        setMenuState(undefined);
        if (choices) { setChoices(undefined); setPreview(undefined); }
      }}
      onNodeDragStart={(_, node) => {
        clearTimeout(timer.current);
        window.dispatchEvent(new Event("lessplain:canvas-move"));
        const definition = definitionsById.get(node.id);
        if (definition?.parentId) {
          const group = model.groups.find(item => item.nodeId === definition.parentId);
          if (group) workspace?.selectCluster(group.id);
        }
      }}
      onNodeDrag={edgeRepair.schedule}
      onNodeDragStop={(_, node) => {
        edgeRepair.flush();
        manual.current = { ...manual.current, [node.id]: node.position };
        setManualRevision(value => value + 1);
        persist(manual.current);
      }}
      onMoveStart={event => { window.dispatchEvent(new Event("lessplain:canvas-move")); if (event) cameraInteracted.current = true; if (!animating.current) cancelCamera(); }}
      onMoveEnd={() => { if (!animating.current) schedulePersist(); }}
      zoomOnScroll={false} panOnScroll={false} panOnDrag={[2]} preventScrolling={false}
      zoomOnDoubleClick={false} minZoom={0.08} maxZoom={2} selectionOnDrag={false}
      elementsSelectable={false} nodesConnectable={false} deleteKeyCode={null} proOptions={{ hideAttribution: true }}>
      <Background gap={20} />
    </ReactFlow>
    {(error || menuState) && <div ref={menu} className={menuState ? "graph-context-menu" : "graph-status"}
      style={menuStyle} role={menuState ? "menu" : "status"} aria-label={menuState ? "画布菜单" : "画布状态"}
      onKeyDown={event => {
        if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); setMenuState(undefined); shell.current?.focus(); }
        else if (event.key === "ArrowDown" || event.key === "ArrowUp") {
          event.preventDefault();
          const buttons = [...menu.current!.querySelectorAll<HTMLButtonElement>("button:not(:disabled)")];
          const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
          buttons[(index + (event.key === "ArrowDown" ? 1 : -1) + buttons.length) % buttons.length]?.focus();
        }
      }}>
      {error && !menuState && error}
      {menuState?.kind === "canvas" && <>
        <CanvasButton role="menuitem" onClick={() => { fit(); setMenuState(undefined); }}>显示全图</CanvasButton>
        <CanvasButton role="menuitem" onClick={() => { arrange(); setMenuState(undefined); }}>自动排版</CanvasButton>
      </>}
      {menuState?.kind === "node" && menuNode && <>
        <CanvasButton role="menuitem" onClick={event => {
          focusDefinition(menuNode); beginAnnotation(undefined, elementAnchor(event.currentTarget)); setMenuState(undefined);
        }}>为节点添加注释</CanvasButton>
        {menuNode.kind === "graph" && <CanvasButton role="menuitem" onClick={() => copyNodeContext(menuNode.elementId, menuNode.nodeId, menuNode.label, menuNode.source)}>复制节点上下文</CanvasButton>}
        {menuNode.kind === "graph" && menuNode.source && <CanvasButton role="menuitem" onClick={() => navigateSource(menuNode)}>查看节点源码</CanvasButton>}
      </>}
      {menuState?.kind === "edge" && menuEdge?.evidence?.[0] && <>
        <CanvasButton role="menuitem" onClick={() => copyRelationSource(menuEdge.evidence?.[0])}>复制关系来源</CanvasButton>
        <CanvasButton role="menuitem" onClick={() => openEdgeEvidence(menuEdge, model, navigateSource)}>查看关系来源</CanvasButton>
      </>}
    </div>}
  </div>;
}

function toFlowEdge(edge: CanvasEdgeDefinition, direction: "RIGHT" | "DOWN"): CanvasFlowEdge {
  return {
    id: edge.id,
    source: edge.source,
    target: edge.target,
    sourceHandle: "out",
    targetHandle: "in",
    type: "elk",
    label: edge.label,
    markerEnd: { type: MarkerType.ArrowClosed, color: "var(--muted-foreground)" },
    data: {
      ...edge,
      sections: [],
      automatic: true,
      down: direction === "DOWN",
    },
  };
}

function initialOptions(model: CanvasModel): GraphOptions {
  const directions = [...new Set(Object.values(model.graphProps).map(props => props.direction).filter(Boolean))];
  return {
    direction: directions.length === 1 ? directions[0]! : "AUTO",
    jumpToSource: (window.__lessplainBoot?.host ?? (window.__lessplainBoot?.ref ? "editor" : "toolwindow")) !== "editor",
    flow: true,
    follow: true,
  };
}

function defaultSelectionPath(elementId: string) {
  const escaped = elementId.replace(/~/g, "~0").replace(/\//g, "~1");
  return "/graphs/" + escaped + "/selectedNode";
}

function copyNodeContext(elementId: string, nodeId: string, nodeLabel: string, sourceId?: string) {
  const state = useApp.getState();
  const view = currentView();
  const text = formatGraphNodeContext({
    viewRef: view?.ref ?? state.selected,
    elementId,
    nodeId,
    nodeLabel,
    sourceId,
    sourcePath: sourceId ? view?.sources[sourceId]?.path : undefined,
  });
  void call("copy", { text }).catch(error => useApp.getState().setError(error.message));
}

function copyRelationSource(sourceId?: string) {
  const source = sourceId ? currentView()?.sources[sourceId] : undefined;
  if (source) void call("copy", { text: source.text }).catch(error => useApp.getState().setError(error.message));
}

function openEdgeEvidence(edge: CanvasEdgeDefinition, model: CanvasModel, navigateSource: (definition: CanvasNodeDefinition) => void) {
  const sourceId = edge.evidence?.[0];
  const definition = model.nodes.find(node => node.kind === "graph" && node.elementId === edge.sourceElementId && node.nodeId === edge.sourceNodeId);
  if (!sourceId || !definition) return;
  navigateSource({ ...definition, source: sourceId });
}

function freezeSnapshot<T>(value: T): T {
  if (value && typeof value === "object" && !Object.isFrozen(value)) {
    Object.freeze(value);
    Object.values(value as Record<string, unknown>).forEach(freezeSnapshot);
  }
  return value;
}
