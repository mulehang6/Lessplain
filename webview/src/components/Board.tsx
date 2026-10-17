import type { ReactNode } from "react";

/** Board composition is flattened by the page-wide React Flow canvas. */
export function Board({ children }: { children?: ReactNode }) {
  return <>{children}</>;
}
