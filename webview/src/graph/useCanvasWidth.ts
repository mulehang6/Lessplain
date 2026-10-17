import { useLayoutEffect, useState, type RefObject } from "react";
import { recordCanvasEvent } from "./diagnostics";

/** 记录视口实际宽度；布局只在首次或显式排版时读取，不随 resize 失效。 */
export function useCanvasWidth(shell: RefObject<HTMLDivElement | null>) {
  const [width, setWidth] = useState(0);
  useLayoutEffect(() => {
    const element = shell.current;
    if (!element) return;
    setWidth(element.clientWidth);
    recordCanvasEvent("viewport", { width: element.clientWidth, height: element.clientHeight });
    const observer = new ResizeObserver(entries => {
      setWidth(Math.round(entries[0].contentRect.width));
      recordCanvasEvent("viewport", { width: Math.round(entries[0].contentRect.width), height: Math.round(entries[0].contentRect.height) });
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [shell]);
  return width;
}
