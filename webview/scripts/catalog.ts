import { writeFileSync } from "node:fs";
import { catalog } from "../src/catalog.ts";
import { z } from "zod";
const dynamic = { type: "object", minProperties: 1, maxProperties: 1,
  properties: { $state: { type: "string" }, $bindState: { type: "string" }, $item: { type: "string" }, $bindItem: { type: "string" }, $index: { type: "boolean" }, $computed: {} },
  additionalProperties: false };
function withExpressions(raw: Record<string, unknown>, allowExpressions = true, allowCurrentExpression = allowExpressions): Record<string, unknown> {
  const schema = { ...raw };
  delete schema.$schema;
  if (schema.properties) {
    const properties = schema.properties as Record<string, Record<string, unknown>>;
    schema.properties = Object.fromEntries(Object.entries(properties).map(([k, v]) => [
      k, withExpressions(v, allowExpressions, allowExpressions && k !== "selectionPath"),
    ]));
    // shadcn catalog 的 nullable 参数在 React 组件中可省略。
    schema.required = ((schema.required as string[]) ?? []).filter(k => !JSON.stringify(properties[k]).includes('"type":"null"'));
  }
  if (schema.items && typeof schema.items === "object") schema.items = withExpressions(schema.items as Record<string, unknown>, allowExpressions);
  if (Array.isArray(schema.anyOf)) schema.anyOf = schema.anyOf.map(s => withExpressions(s as Record<string, unknown>, allowExpressions));
  return allowExpressions && allowCurrentExpression ? { anyOf: [schema, dynamic] } : schema;
}
const componentSchemas = Object.entries(catalog.data.components).map(([type, definition]) => {
  const slots = definition.slots ?? [];
  const namedSlots = slots.filter(slot => slot !== "default");
  const properties: Record<string, unknown> = {
    type: { const: type },
    props: withExpressions(z.toJSONSchema(definition.props), type !== "Cluster", !["Graph", "Board", "Cluster"].includes(type)),
    on: { type: "object" }, visible: {}, repeat: {},
  };
  const required = ["type", "props"];
  if (type === "Board" || slots.includes("default")) {
    properties.children = { type: "array", items: { type: "string" } };
    if (type === "Board") required.push("children");
  }
  if (namedSlots.length) {
    properties.slots = {
      type: "object",
      properties: Object.fromEntries(namedSlots.map(name => [name, {
        type: "array", items: { type: "string" }, ...(type === "Cluster" && name === "diagram" ? { maxItems: 1 } : {}),
      }])),
      additionalProperties: false,
    };
  }
  const schema: Record<string, unknown> = { type: "object", properties, required, additionalProperties: false };
  if (type === "Cluster") schema.allOf = [{
    anyOf: ["diagram", "companion"].map(slot => ({
      required: ["slots"],
      properties: { slots: { type: "object", properties: { [slot]: { minItems: 1 } }, required: [slot] } },
    })),
  }];
  return schema;
});
const schema = { type: "object", properties: {
  root: { type: "string" }, elements: { type: "object", additionalProperties: { oneOf: componentSchemas } },
  state: { type: "object" },
}, required: ["root", "elements"], additionalProperties: false };
writeFileSync("dist/catalog.json", JSON.stringify({
  schema,
  instructions: catalog.prompt({ customRules: [
    "通过 lessplain_present 提交完整 spec 对象，禁止 JSONL patch。", "示例值须明确标注；项目实现事实须先读取实际源码。", "Graph、SourceLink、CodeExcerpt 的 source 与 Demo.demo 必须为固定字面量 ID，不能从状态表达式计算。",
    "混合答案使用 Board 作为 root，并且 Board.children 只引用 Cluster；Board 不使用 named slots，不提交坐标。纯 Graph root 仍兼容。",
    "含图页面必须以 Board、Cluster 或 Graph 为根。Stack、Grid、Card 等普通容器放在 companion 中，不能作为含图页面的根或包裹 Graph。纯组件页面不受此限制。",
    "每个 Cluster.props.title 必填。Cluster.slots.diagram 最多包含一个 Graph；Cluster.slots.companion 可包含一个或多个普通 UI 组件。至少提供一个非空成员槽；companion 后代不能再包含 Graph、Board 或 Cluster。不要把 Cluster 列表放进理解层级。",
    "Graph.selectionPath 必须是固定字面量 RFC 6901 JSON Pointer，不能使用 $state 或 $bindState；每个 Graph 的路径互不重复。未提供时默认使用 /graphs/<JSON Pointer escaped element ID>/selectedNode。状态中的节点 ID 必须属于该 Graph。",
  ] }).replace(/OUTPUT FORMAT[\s\S]*?INITIAL STATE:/, "OUTPUT FORMAT: 完整 spec JSON 对象，包含 root、elements 和可选 state。\n\nINITIAL STATE:"),
  demoProtocol: {
    channel: "lessplain-demo",
    methods: ["resize", "setState", "openSource", "ask"],
    example: 'parent.postMessage({channel:"lessplain-demo",method:"openSource",params:{source:"actualSourceId"}},"*")',
  },
}));
