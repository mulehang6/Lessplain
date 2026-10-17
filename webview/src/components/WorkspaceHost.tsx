import { type ReactNode } from "react";
import { GraphControls } from "../graph/GraphControls";
import { WorkspaceGroupNavigation } from "./WorkspaceGroupNavigation";
import { useWorkspace } from "./WorkspaceContext";

/** One fixed toolbar controls the page-wide React Flow camera and topology. */
export function WorkspaceHost({ children }: { children: ReactNode }) {
  const controller = useWorkspace()?.activeController;
  return <div className="workspace-host">
    <div className="workspace-scene">{children}</div>
    {controller && <div className="workspace-toolbar-layer">
      <GraphControls controller={controller} groupNavigation={<WorkspaceGroupNavigation />} />
    </div>}
  </div>;
}
