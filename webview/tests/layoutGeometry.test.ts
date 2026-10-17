import { expect, it } from "vitest";
import { anchorLayout } from "../src/graph/layoutGeometry";
import type { Layout } from "../src/graph/layout";

it("扩展组件后固定所属分组，全局边同步平移而子节点保留相对坐标", () => {
  const layout: Layout = { positions: { a: { x: 20, y: 40 }, child: { x: 10, y: 60 }, b: { x: 400, y: 40 } }, sizes: {},
    sections: { edge: [{ id: "s", startPoint: { x: 30, y: 100 }, endPoint: { x: 400, y: 100 }, bendPoints: [{ x: 250, y: 100 }] }] } };
  const anchored = anchorLayout(layout, ["a", "b"], "a", { x: 120, y: 140 });
  expect(anchored.positions.a).toEqual({ x: 120, y: 140 });
  expect(anchored.positions.b).toEqual({ x: 500, y: 140 });
  expect(anchored.positions.child).toEqual(layout.positions.child);
  expect(anchored.sections.edge[0]).toMatchObject({ startPoint: { x: 130, y: 200 }, bendPoints: [{ x: 350, y: 200 }], endPoint: { x: 500, y: 200 } });
  expect(layout.positions.a).toEqual({ x: 20, y: 40 });
});
