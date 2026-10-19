import { defineCatalog } from "@json-render/core";
import { schema } from "@json-render/react/schema";
import { shadcnComponentDefinitions as definitions } from "@json-render/shadcn/catalog";
import { z } from "zod";

export const codeSampleTarget = z.object({ id: z.string().min(1), startLine: z.number().int().min(1).optional(), endLine: z.number().int().min(1).optional() });
const tone = z.enum(["neutral", "info", "success", "warning", "danger"]).optional();
export const graphNode = z.object({
  id: z.string().min(1), label: z.string().min(1),
  detail: z.string().optional(), source: z.string().optional(),
  sample: codeSampleTarget.optional(),
  roles: z.array(z.enum(["start", "end"])).optional(),
  outcome: z.enum(["success", "failure"]).optional(),
});
export const graphEdge = z.object({
  id: z.string().min(1), source: z.string(), target: z.string(),
  kind: z.enum(["control_flow", "call", "data_flow", "dependency", "structure"]),
  label: z.string().optional(), summary: z.string().optional(),
  evidence: z.array(z.string()).optional(), inferred: z.boolean().optional(),
});
export const boardEdgeEndpoint = z.object({ elementId: z.string().min(1), nodeId: z.string().min(1) });
export const boardEdge = z.object({
  id: z.string().min(1), source: boardEdgeEndpoint, target: boardEdgeEndpoint,
  kind: z.enum(["control_flow", "call", "data_flow", "dependency", "structure"]),
  label: z.string().optional(), summary: z.string().optional(),
  evidence: z.array(z.string()).optional(), inferred: z.boolean().optional(),
});
const jsonPointer = z.string().regex(/^\/(?:[^~/]|~[01])*(?:\/(?:[^~/]|~[01])*)*$/, "必须是有效的 RFC 6901 JSON Pointer。");

export const graphProps = z.object({
  nodes: z.array(graphNode).min(1).max(100),
  edges: z.array(graphEdge).max(300),
  direction: z.enum(["RIGHT", "DOWN"]).optional(),
  selectionPath: jsonPointer.optional(),
});
export type GraphProps = z.infer<typeof graphProps>;

// 同一份 catalog 用于 Agent schema、JVM 校验和 React registry。
export const catalog = defineCatalog(schema, {
  components: {
    Stack: definitions.Stack,
    Grid: definitions.Grid,
    Card: { ...definitions.Card, props: definitions.Card.props.extend({ tone }) },
    Heading: definitions.Heading,
    Text: definitions.Text,
    Badge: { ...definitions.Badge, props: definitions.Badge.props.extend({ tone }) },
    Separator: definitions.Separator,
    Tabs: definitions.Tabs,
    Accordion: definitions.Accordion,
    Collapsible: definitions.Collapsible,
    Button: definitions.Button,
    Input: definitions.Input,
    Select: definitions.Select,
    Slider: definitions.Slider,
    Switch: definitions.Switch,
    Board: {
      props: z.object({ edges: z.array(boardEdge).max(300).optional() }),
      description: "统一关系画布。children 只能包含 Cluster；可选 edges 使用 {elementId,nodeId} 端点表达跨分组关系。",
    },
    Cluster: {
      props: z.object({ title: z.string().min(1) }),
      slots: ["diagram", "companion"],
      description: "按用户问题组织的具名分组。先选能讲清问题的最小表达；需比较的对象同时可见，无关系可用无边源码节点。title 必填；diagram 最多一个 Graph，companion 放必要 UI；至少一个成员。",
    },
    Graph: {
      props: graphProps,
      description: "关系或流程图。无关系的概念可使用无边节点；不提交坐标。selectionPath 可用固定 RFC 6901 JSON Pointer 与页面状态双向同步；默认路径为 /graphs/<JSON Pointer escaped element ID>/selectedNode。",
    },
    SourceLink: { props: z.object({ source: z.string(), label: z.string() }), description: "点击定位到 sources 中的实际源码。" },
    CodeExcerpt: { props: z.object({ source: z.string(), label: z.string().optional() }), description: "在 IDEA 只读标签查看保存时的源码原文，另可定位当前文件。用于有价值的快照对照；普通结构解释直接定位实际声明。label 使用短用途名，保留各文件的独立来源。" },
    CodeSample: { props: z.object({ sample: z.string().min(1), label: z.string().optional(), startLine: z.number().int().min(1).optional(), endLine: z.number().int().min(1).optional() }), description: "在 IDEA 原生只读编辑器打开 codeSamples 中的教学代码；可定位一基闭区间。样例不代表项目源码或真实执行。" },
    JsonView: { props: z.object({ value: z.unknown(), label: z.string().optional(), format: z.enum(["json", "text"]).optional() }), description: "点击在 IDEA 只读标签查看打开时的数据快照；支持状态表达式。label 使用短用途名，示例值在附近说明；不为展示 JSON 而添无关示例。format=text 仅用于明确原始字符串，不自动解析嵌套 JSON。少量结果用 Text。" },
    Demo: { props: z.object({ demo: z.string(), title: z.string(), height: z.number().min(120).max(1200).optional() }), description: "demos 中的隔离 HTML 教学示例。仅用约定的 postMessage 与父页面交互。" },
  },
  actions: {
    openSource: { params: z.object({ source: z.string() }), description: "定位实际源码。" },
    openSample: { params: codeSampleTarget, description: "打开已保存的教学代码，支持 startLine/endLine 定位。" },
  },
});
