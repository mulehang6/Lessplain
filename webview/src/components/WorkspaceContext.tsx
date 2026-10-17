import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import type { Spec } from "@json-render/core";
import type { GraphOptions } from "../runtime/types";
import { saveWorkspaceState, useApp } from "../runtime/store";
import type { CanvasEdgeDefinition, CanvasNodeDefinition } from "../graph/canvasModel";
import { restoreWorkspaceBrowseState, workspaceBrowseKey, workspaceGroups, type WorkspaceGroup } from "./WorkspaceBrowseState";

export type GraphRouteChoice = { direction: "previous" | "next"; edges: CanvasEdgeDefinition[] };

/** The single controller for the page-wide React Flow camera and global topology. */
export type GraphController = {
  options: GraphOptions;
  nodes: CanvasNodeDefinition[];
  edges: CanvasEdgeDefinition[];
  currentNodeId?: string;
  choices?: GraphRouteChoice;
  preview?: string;
  canPrevious: boolean;
  canNext: boolean;
  zoomIn(): void;
  zoomOut(): void;
  fit(): void;
  resetZoom(): void;
  arrange(): void;
  stepPrevious(): void;
  stepNext(): void;
  focusGroup(groupId: string): void;
  getOptions(): GraphOptions;
  setOptions(options: GraphOptions): void;
  onPreview(id: string): void;
  onChoose(id: string): void;
  onCancelChoice(): void;
};

type WorkspaceContextValue = {
  activeController?: GraphController;
  activeControllerId?: string;
  activeClusterId?: string;
  groups: WorkspaceGroup[];
  registerController(id: string, getController: () => GraphController): () => void;
  refreshController(id: string): void;
  activateController(id: string): void;
  selectCluster(clusterId: string): void;
  activateCluster(clusterId: string): void;
  deactivateController(): void;
};

const WorkspaceContext = createContext<WorkspaceContextValue | undefined>(undefined);

/** Saves the selected cluster while exposing one shared canvas controller. */
export function WorkspaceProvider({ children, spec }: { children: ReactNode; spec: Spec }) {
  const view = useApp(state => state.snapshot.views.find(item => item.ref === state.selected));
  const viewRef = view?.ref;
  const viewRevision = view?.revision;
  const host = window.__lessplainBoot?.host ?? (window.__lessplainBoot?.ref ? "editor" : "toolwindow");
  const browseKey = workspaceBrowseKey(host);
  const saved = useApp(state => viewRef ? state.snapshot.browse[viewRef]?.[browseKey] : undefined);
  const groups = useMemo(() => workspaceGroups(spec), [spec]);
  const restored = useMemo(() => restoreWorkspaceBrowseState(saved, groups), [saved, groups]);
  const controllers = useRef(new Map<string, () => GraphController>());
  const [activeControllerId, setActiveControllerId] = useState<string | undefined>("canvas");
  const [activeClusterId, setActiveClusterId] = useState<string | undefined>(restored.activeClusterId);
  const activeControllerIdRef = useRef(activeControllerId);
  const activeClusterIdRef = useRef(activeClusterId);
  activeControllerIdRef.current = activeControllerId;
  activeClusterIdRef.current = activeClusterId;
  const [, setRevision] = useState(0);

  const changeFocus = useCallback((clusterId?: string) => {
    if (activeClusterIdRef.current === clusterId) return;
    activeClusterIdRef.current = clusterId;
    setActiveClusterId(clusterId);
    saveWorkspaceState(viewRef, browseKey, { activeClusterId: clusterId });
  }, [viewRef, browseKey]);

  useEffect(() => {
    activeClusterIdRef.current = restored.activeClusterId;
    setActiveClusterId(restored.activeClusterId);
  }, [viewRef, viewRevision]);

  const registerController = useCallback((id: string, getController: () => GraphController) => {
    controllers.current.set(id, getController);
    activeControllerIdRef.current = id;
    setActiveControllerId(id);
    setRevision(value => value + 1);
    return () => {
      if (controllers.current.get(id) !== getController) return;
      controllers.current.delete(id);
      if (activeControllerIdRef.current === id) {
        activeControllerIdRef.current = undefined;
        setActiveControllerId(undefined);
      }
      setRevision(value => value + 1);
    };
  }, []);
  const refreshController = useCallback((id: string) => {
    if (controllers.current.has(id)) setRevision(value => value + 1);
  }, []);
  const activateController = useCallback((id: string) => {
    if (controllers.current.has(id)) {
      activeControllerIdRef.current = id;
      setActiveControllerId(id);
      setRevision(value => value + 1);
    }
  }, []);
  const selectCluster = useCallback((clusterId: string) => changeFocus(clusterId), [changeFocus]);
  const activateCluster = useCallback((clusterId: string) => {
    changeFocus(clusterId);
    const controller = activeControllerIdRef.current
      ? controllers.current.get(activeControllerIdRef.current)?.()
      : undefined;
    controller?.focusGroup(clusterId);
  }, [changeFocus]);
  const deactivateController = useCallback(() => {
    activeControllerIdRef.current = undefined;
    setActiveControllerId(undefined);
  }, []);
  const activeController = activeControllerId ? controllers.current.get(activeControllerId)?.() : undefined;
  const value = useMemo(() => ({
    activeController, activeControllerId, activeClusterId, groups, registerController, refreshController,
    selectCluster,
    activateController, activateCluster, deactivateController,
  }), [activeController, activeControllerId, activeClusterId, registerController, refreshController,
    groups, selectCluster, activateController, activateCluster, deactivateController]);
  return <WorkspaceContext.Provider value={value}>{children}</WorkspaceContext.Provider>;
}

/** Returns undefined outside a workspace so isolated component previews remain usable. */
export function useWorkspace() {
  return useContext(WorkspaceContext);
}
