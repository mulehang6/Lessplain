import type { Spec } from "@json-render/core";
export type Source = { path: string; text: string; symbol?: string; version: string; line: number; selectionRange?: { startOffset: number; endOffset: number } };
export type CodeSample = { title: string; language: string; code: string };
export type CodeSampleTarget = { id: string; startLine?: number; endLine?: number };
export type SampleSelection = { id: string; sample: CodeSample; version: string; selectionRange: { startOffset: number; endOffset: number } };
export type Origin = { entry?: "node" | "component" | "sourceSelection" | "sampleSelection"; viewRef?: string; elementId?: string; nodeId?: string; nodeLabel?: string; source?: Source; sample?: SampleSelection; state: Record<string, unknown> };
export type View = {
  ref: string; revision: number; parentRef?: string; createdAt: number; updatedAt: number;
  origin: Origin; sources: Record<string, Source>; answeredNote?: Note;
  document: { question: string; title?: string; spec: Spec; demos: Record<string, { html: string }>; codeSamples?: Record<string, CodeSample> };
};
export type Note = { ref: string; kind: "question" | "feedback"; text: string; ownerRef?: string; answerRef?: string; status: string; origin: Origin; createdAt: number };
export type Snapshot = { views: View[]; notes: Note[]; browse: Record<string, Record<string, unknown>> };
export type GraphOptions = { direction: "AUTO" | "RIGHT" | "DOWN"; jumpToSource: boolean; flow: boolean; follow: boolean };
export type GraphBrowse = { positions?: Record<string, { x: number; y: number }>; trail?: string[]; options?: GraphOptions; viewport?: { x: number; y: number; zoom: number } };
