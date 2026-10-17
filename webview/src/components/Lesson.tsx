import { Component, type ReactNode, useCallback, useMemo } from "react";
import { JSONUIProvider, Renderer, defineRegistry, type ComponentRegistry } from "@json-render/react";
import { shadcnComponents as ui } from "@json-render/shadcn";
import type { Spec } from "@json-render/core";
import { catalog } from "../catalog";
import { GlobalCanvas } from "../graph/GlobalCanvas";
import { ComponentFrame } from "./ComponentFrame";
import { openCodeSample } from "../runtime/samples";
import { Demo } from "./Demo";
import { call } from "../runtime/bridge";
import { currentView, lessonStore, useApp } from "../runtime/store";
import type { View } from "../runtime/types";
import { WorkspaceHost } from "./WorkspaceHost";
import { WorkspaceProvider } from "./WorkspaceContext";
import { wrapRootContent } from "./WorkspaceGeometry";

const built = defineRegistry(catalog, {
  components: {
    Stack: ui.Stack, Grid: ui.Grid, Card: ui.Card, Heading: ui.Heading, Text: ui.Text,
    Badge: ui.Badge, Separator: ui.Separator, Tabs: ui.Tabs, Accordion: ui.Accordion, Collapsible: ui.Collapsible,
    Button: ui.Button, Input: ui.Input, Select: ui.Select, Slider: ui.Slider, Switch: ui.Switch,
    // Board, Cluster and Graph are represented by the single GlobalCanvas, never nested Renderers.
    Board: () => null, Cluster: () => null, Graph: () => null,
    SourceLink: ({ props }) => <button className="source-button" onClick={() => open(props.source, elementId(props))}>{props.label} ↗</button>,
    CodeExcerpt: ({ props }) => <section><button className="source-button" onClick={() => open(props.source, elementId(props))}>{props.label ?? currentView()?.sources[props.source]?.path} ↗</button>
      <pre className="code">{currentView()?.sources[props.source]?.text}</pre></section>,
    JsonView: ({ props }) => <section>{props.label && <div className="component-title">{props.label}</div>}<pre className="code">{JSON.stringify(props.value, null, 2)}</pre></section>,
    CodeSample: ({ props }) => <button className="sample-button" onClick={() => openCodeSample({ id: props.sample, startLine: props.startLine, endLine: props.endLine }, elementId(props))}>
      <span className="sample-kind">教学代码</span><span>{props.label ?? currentView()?.document.codeSamples?.[props.sample]?.title ?? props.sample} ↗</span>
    </button>,
    Demo: ({ props }) => <Demo {...props} elementId={elementId(props)} />,
  },
  actions: {
    openSource: async params => { if (params) open(params.source); },
    openSample: async params => { if (params) openCodeSample(params); },
  },
});

function elementId(props: object): string | undefined {
  return (props as { __elementId?: string }).__elementId;
}

function open(source: string, id?: string) {
  const state = useApp.getState();
  const view = state.snapshot.views.find(candidate => candidate.ref === state.selected);
  if (!view?.sources[source]) return;
  const lesson = freezeSnapshot(structuredClone(state.lesson));
  state.focus(id, undefined, source);
  void call("source", { ref: view.ref, source, elementId: id, revision: view.revision, state: lesson })
    .catch(error => useApp.getState().setError(error instanceof Error ? error.message : String(error)));
}

function freezeSnapshot<T>(value: T): T {
  if (value && typeof value === "object" && !Object.isFrozen(value)) {
    Object.freeze(value);
    Object.values(value as Record<string, unknown>).forEach(freezeSnapshot);
  }
  return value;
}

class LessonBoundary extends Component<{ children: ReactNode }, { error?: string }> {
  state: { error?: string } = {};
  static getDerivedStateFromError(error: Error) { return { error: error.message }; }
  render() { return this.state.error ? <p role="alert">页面组件加载失败：{this.state.error}</p> : this.props.children; }
}

function withElementIds(spec: Spec): Spec {
  return {
    ...spec,
    elements: Object.fromEntries(Object.entries(spec.elements).map(([id, element]) => [id, {
      ...element,
      props: { ...element.props, __elementId: id },
    }])),
  };
}

export function Lesson({ view }: { view: View }) {
  const contentKey = useMemo(() => JSON.stringify([view.document.spec, view.document.demos, view.document.codeSamples, view.sources]),
    [view.document.spec, view.document.demos, view.document.codeSamples, view.sources]);
  return <LessonBoundary key={view.ref + ":" + contentKey}><LessonContent view={view} contentKey={contentKey} /></LessonBoundary>;
}

function LessonContent({ view, contentKey }: { view: View; contentKey: string }) {
  const spec = useMemo(() => withElementIds(wrapRootContent(view.document.spec, view.document.title ?? view.document.question)),
    [view.ref, contentKey]);
  const handlers = useMemo(() => built.handlers(() => update => {
    useApp.setState(state => ({ lesson: update(state.lesson) }));
  }, () => useApp.getState().lesson), [view.ref, contentKey]);

  const registry = useMemo(() => {
    const registry: ComponentRegistry = {};
    Object.entries(built.registry).forEach(([type, Render]) => {
      registry[type] = component => {
        if (["Board", "Cluster", "Graph"].includes(type)) return null;
        const id = String(component.element.props.__elementId ?? "");
        const source = typeof component.element.props.source === "string" ? component.element.props.source : undefined;
        return <ComponentFrame id={id} type={type} source={source}>
          <Render {...component} />
        </ComponentFrame>;
      };
    });
    return registry;
  }, [spec]);

  const renderCompanion = useCallback((elementId: string) => <Renderer
    spec={{ ...spec, root: elementId }} registry={registry} />, [registry, spec]);

  return <div className="lesson-surface">
    <WorkspaceProvider spec={spec}>
      <WorkspaceHost>
        <LessonBoundary key={view.ref}>
          <JSONUIProvider registry={registry} store={lessonStore} handlers={handlers}>
            <GlobalCanvas key={view.ref} spec={spec} view={view} renderCompanion={renderCompanion} />
          </JSONUIProvider>
        </LessonBoundary>
      </WorkspaceHost>
    </WorkspaceProvider>
  </div>;
}
