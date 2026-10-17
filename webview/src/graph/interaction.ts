export type Viewport = { x: number; y: number; zoom: number };
export type GraphBounds = { left: number; top: number; width: number; height: number };
export type WorldRect = { left: number; top: number; right: number; bottom: number };
export type WheelInput = {
  clientX: number;
  clientY: number;
  deltaX: number;
  deltaY: number;
  shiftKey: boolean;
  ctrlKey: boolean;
};

/** 将滚轮手势限定在图画布内，并在 Ctrl 缩放时固定指针下的图上位置。 */
export function wheelViewport(
  viewport: Viewport,
  bounds: GraphBounds,
  event: WheelInput,
  minZoom = 0.2,
  maxZoom = 2,
): Viewport | undefined {
  if (event.ctrlKey) {
    if (!event.deltaY) return undefined;
    const zoom = clamp(viewport.zoom * Math.pow(1.12, -event.deltaY / 100), minZoom, maxZoom);
    if (zoom === viewport.zoom) return undefined;
    const x = event.clientX - bounds.left;
    const y = event.clientY - bounds.top;
    const worldX = (x - viewport.x) / viewport.zoom;
    const worldY = (y - viewport.y) / viewport.zoom;
    return { x: x - worldX * zoom, y: y - worldY * zoom, zoom };
  }

  if (event.shiftKey) {
    const delta = event.deltaX || event.deltaY;
    return delta ? { ...viewport, x: viewport.x - delta } : undefined;
  }
  return event.deltaY ? { ...viewport, y: viewport.y - event.deltaY } : undefined;
}

/** 计算保留当前缩放级别的节点居中视口。 */
export function centeredViewport(
  viewport: Viewport,
  center: { x: number; y: number },
  bounds: Pick<GraphBounds, "width" | "height">,
): Viewport {
  return {
    x: bounds.width / 2 - center.x * viewport.zoom,
    y: bounds.height / 2 - center.y * viewport.zoom,
    zoom: viewport.zoom,
  };
}

/** 缩小全局镜头，使当前节点与所有分支候选同时落入可视区。 */
export function fitBoundsViewport(
  viewport: Viewport,
  rects: readonly WorldRect[],
  bounds: Pick<GraphBounds, "width" | "height">,
  padding = 72,
  minZoom = 0.08,
): Viewport | undefined {
  if (!rects.length) return undefined;
  const left = Math.min(...rects.map(rect => rect.left));
  const right = Math.max(...rects.map(rect => rect.right));
  const top = Math.min(...rects.map(rect => rect.top));
  const bottom = Math.max(...rects.map(rect => rect.bottom));
  const worldWidth = Math.max(1, right - left);
  const worldHeight = Math.max(1, bottom - top);
  const availableWidth = Math.max(1, bounds.width - padding * 2);
  const availableHeight = Math.max(1, bounds.height - padding * 2);
  const zoom = Math.max(minZoom, Math.min(viewport.zoom, availableWidth / worldWidth, availableHeight / worldHeight));
  const centerX = (left + right) / 2, centerY = (top + bottom) / 2;
  return { x: bounds.width / 2 - centerX * zoom, y: bounds.height / 2 - centerY * zoom, zoom };
}

export type FrameDriver = {
  request(callback: (time: number) => void): number;
  cancel(id: number): void;
};

const browserFrames: FrameDriver = {
  request: callback => requestAnimationFrame(callback),
  cancel: id => cancelAnimationFrame(id),
};

/** 可被手势取消、可从当前视口重新定向的相机动画。 */
export class ViewportAnimator {
  private generation = 0;
  private frame?: number;

  constructor(private readonly frames: FrameDriver = browserFrames) {}

  animate(from: Viewport, to: Viewport, write: (viewport: Viewport) => void, duration = 240, done?: () => void) {
    this.cancel();
    const generation = this.generation;
    let startedAt: number | undefined;
    const tick = (time: number) => {
      if (generation !== this.generation) return;
      startedAt ??= time;
      const progress = duration <= 0 ? 1 : Math.min(1, (time - startedAt) / duration);
      const eased = 1 - Math.pow(1 - progress, 3);
      write({
        x: from.x + (to.x - from.x) * eased,
        y: from.y + (to.y - from.y) * eased,
        zoom: from.zoom + (to.zoom - from.zoom) * eased,
      });
      if (progress >= 1) {
        this.frame = undefined;
        done?.();
      } else {
        this.frame = this.frames.request(tick);
      }
    };
    this.frame = this.frames.request(tick);
  }

  cancel() {
    this.generation++;
    if (this.frame !== undefined) this.frames.cancel(this.frame);
    this.frame = undefined;
  }
}

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value));
}
