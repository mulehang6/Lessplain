import { test } from "node:test";
import assert from "node:assert/strict";
import { anchor, currentSources, initialFrames, sources } from "./data";
import {
  captureContext,
  initialVisit,
  layout,
  presentFrame,
  replay,
  resolveAnchor,
  validateTrace,
} from "./model";

test("retries preserve each hit and stepping backwards cannot expose future values", () => {
  const trace = initialFrames[3].trace!;
  assert.deepEqual(
    trace.hits.filter((h) => h.nodeId === "save").map((h) => h.values.result),
    ["timeout", "busy", "ok"],
  );
  assert.equal(replay(trace, 3).latest.get("save")?.values.attempt, "2");
  assert.equal(replay(trace, 0).latest.get("save")?.values.result, "timeout");
  assert.equal(replay(trace, 6).counts.get("save"), 3);
  assert.equal(replay(trace, 3).transitions.has("retry->save"), true);
  assert.equal(replay(trace, 3).transitions.has("check->done"), false);
  assert.equal(replay(trace, -1).latest.size, 0);
});

test("duplicate and unknown hits are rejected, feedback paths are accepted", () => {
  const frame = initialFrames[3];
  assert.doesNotThrow(() => validateTrace(frame, frame.trace!));
  const duplicate = structuredClone(frame.trace!);
  duplicate.hits[1].seq = 1;
  assert.throws(() => validateTrace(frame, duplicate));
  const unknown = structuredClone(frame.trace!);
  unknown.hits[0].nodeId = "invented";
  assert.throws(() => validateTrace(frame, unknown));
  const skipped = structuredClone(frame.trace!);
  skipped.hits.splice(1, 1);
  assert.throws(() => validateTrace(frame, skipped));
});

test("anchor relocation does not claim the old explanation is current", () => {
  const a = anchor("branch", 8);
  assert.deepEqual(resolveAnchor(a, sources[0]), {
    line: 8,
    status: "located",
    evidenceStale: false,
  });
  assert.deepEqual(resolveAnchor(a, currentSources("moved")[0]), {
    line: 10,
    status: "relocated",
    evidenceStale: true,
  });
  assert.equal(resolveAnchor(a, currentSources("missing")[0]).line, null);
  assert.equal(
    resolveAnchor(a, currentSources("ambiguous")[0]).status,
    "ambiguous",
  );
  assert.equal(resolveAnchor(a, sources[1]).status, "missing");
});

test("text staying on its original line cannot hide a revision change", () => {
  const a = anchor("branch", 14);
  assert.deepEqual(resolveAnchor(a, currentSources("missing")[0]), {
    line: 14,
    status: "located",
    evidenceStale: true,
  });
});

test("context captures trace and source independently from subsequent navigation", () => {
  const frame = structuredClone(initialFrames[0]);
  const visit = { ...initialVisit(frame), nodeId: "child", cursor: 2 };
  const token = captureContext(frame, visit, "context-1");
  visit.nodeId = "show";
  visit.cursor = 3;
  frame.nodes[2].anchor.lineHint = 999;
  assert.equal(token.origin.nodeId, "child");
  assert.equal(token.origin.seq, 3);
  assert.equal(token.origin.sourceAnchor.lineHint, 8);
  assert.equal(token.origin.traceId, "branch-demo-01");
});

test("omitted parent uses frozen frame; explicit root and sibling are distinct", () => {
  const token = captureContext(
    initialFrames[1],
    initialVisit(initialFrames[1]),
    "context-2",
  );
  const output = initialFrames[2];
  assert.equal(presentFrame(output, token).parentId, "B");
  assert.equal(presentFrame(output, token, "A").parentId, "A");
  assert.equal(presentFrame(output, token, "A").origin?.frameId, "B");
  assert.equal(presentFrame(output, token, null).parentId, null);
  assert.throws(() => presentFrame(output, null));
  assert.equal(presentFrame(output, null, null).parentId, null);
});

test("node anchors and observation anchors can differ after multiline initialization", () => {
  const frame = initialFrames[0];
  assert.equal(frame.nodes.find((n) => n.id === "child")?.anchor.lineHint, 8);
  assert.equal(
    frame.trace!.hits.find((h) => h.nodeId === "child")?.observation.lineHint,
    13,
  );
});

test("layered layout terminates with cycles and gives each node a separate position", () => {
  for (const frame of initialFrames) {
    const result = layout(frame.nodes, frame.edges);
    assert.equal(result.positions.size, frame.nodes.length);
    assert.equal(
      new Set([...result.positions.values()].map((p) => `${p.x},${p.y}`)).size,
      frame.nodes.length,
    );
    for (const p of result.positions.values()) {
      assert.ok(p.x >= 0 && p.x + 180 <= result.width);
      assert.ok(p.y >= 0 && p.y + 78 <= result.height);
    }
  }
});
