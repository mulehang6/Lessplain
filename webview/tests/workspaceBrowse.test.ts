import { describe, expect, it } from "vitest";
import { canvasBrowseKey, mergeWorkspaceBrowseState, restoreWorkspaceBrowseState, workspaceBrowseKey } from "../src/components/WorkspaceBrowseState";

describe("page canvas browse state", () => {
  it("isolates one canvas camera and group selection by host", () => {
    const editorCanvas = canvasBrowseKey("editor"), toolCanvas = canvasBrowseKey("toolwindow");
    const editorWorkspace = workspaceBrowseKey("editor"), toolWorkspace = workspaceBrowseKey("toolwindow");
    expect(editorCanvas).not.toBe(toolCanvas);
    expect(editorWorkspace).not.toBe(toolWorkspace);
    const saved = {
      [editorCanvas]: { viewport: { x: 18, y: -4, zoom: 0.8 }, trail: ["node/a"] },
      [toolCanvas]: { viewport: { x: -60, y: 12, zoom: 1.2 }, trail: ["node/b"] },
    };
    expect(saved[editorCanvas].viewport).not.toEqual(saved[toolCanvas].viewport);
  });

  it("restores only reachable groups and falls back to the first one", () => {
    const groups = [{ id: "cluster/a", title: "A" }, { id: "cluster/b", title: "B" }];
    expect(restoreWorkspaceBrowseState({ activeClusterId: "cluster/b" }, groups)).toEqual({ activeClusterId: "cluster/b" });
    expect(restoreWorkspaceBrowseState({ activeClusterId: "deleted" }, groups)).toEqual({ activeClusterId: "cluster/a" });
    expect(restoreWorkspaceBrowseState(undefined, [])).toEqual({ activeClusterId: undefined });
  });

  it("merges group selection without copying an old independent offset or camera", () => {
    expect(mergeWorkspaceBrowseState({ offset: { x: 1, y: 2 }, activeGraphId: "old" }, { activeClusterId: "cluster/b" }))
      .toEqual({ activeClusterId: "cluster/b" });
  });
});
