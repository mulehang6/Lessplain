import { describe, expect, it } from "vitest";
import { formatGraphNodeContext } from "../src/graph/nodeContext";

describe("图节点上下文复制载荷", () => {
  it("包含当前 view、图元素、节点和来源定位，不包含源码正文", () => {
    const payload = formatGraphNodeContext({
      viewRef: "view:root",
      elementId: "graph/main",
      nodeId: "load-config",
      nodeLabel: "读取配置",
      sourceId: "source:config",
      sourcePath: "src/config.ts",
    });

    expect(JSON.parse(payload)).toEqual({
      type: "lessplain-node-context",
      viewRef: "view:root",
      elementId: "graph/main",
      nodeId: "load-config",
      nodeLabel: "读取配置",
      sourceId: "source:config",
      sourcePath: "src/config.ts",
    });
    expect(payload).not.toContain("const secret");
  });

  it("无来源的概念节点也能生成可复制上下文", () => {
    expect(JSON.parse(formatGraphNodeContext({
      viewRef: "view:root",
      elementId: "graph/main",
      nodeId: "concept",
      nodeLabel: "核心概念",
    }))).toEqual({
      type: "lessplain-node-context",
      viewRef: "view:root",
      elementId: "graph/main",
      nodeId: "concept",
      nodeLabel: "核心概念",
    });
  });
});
