import { describe, expect, it } from "vitest";
import { centeredViewport, fitBoundsViewport, ViewportAnimator, wheelViewport, type FrameDriver, type Viewport } from "../src/graph/interaction";

describe("图表相机手势", () => {
  const bounds = { left: 20, top: 30, width: 600, height: 400 };

  it("普通滚轮只平移纵向，Shift 滚轮只平移横向", () => {
    const initial = { x: 12, y: 34, zoom: 1 };
    expect(wheelViewport(initial, bounds, { clientX: 100, clientY: 100, deltaX: 0, deltaY: 80, shiftKey: false, ctrlKey: false }))
      .toEqual({ x: 12, y: -46, zoom: 1 });
    expect(wheelViewport(initial, bounds, { clientX: 100, clientY: 100, deltaX: 25, deltaY: 80, shiftKey: true, ctrlKey: false }))
      .toEqual({ x: -13, y: 34, zoom: 1 });
    expect(wheelViewport(initial, bounds, { clientX: 100, clientY: 100, deltaX: 0, deltaY: 80, shiftKey: true, ctrlKey: false }))
      .toEqual({ x: -68, y: 34, zoom: 1 });
  });

  it("Ctrl 滚轮以指针为锚点，并限制缩放范围", () => {
    const initial = { x: -180, y: -90, zoom: 1 };
    const event = { clientX: 260, clientY: 180, deltaX: 0, deltaY: -100, shiftKey: false, ctrlKey: true };
    const next = wheelViewport(initial, bounds, event)!;
    const before = { x: (event.clientX - bounds.left - initial.x) / initial.zoom, y: (event.clientY - bounds.top - initial.y) / initial.zoom };
    const after = { x: (event.clientX - bounds.left - next.x) / next.zoom, y: (event.clientY - bounds.top - next.y) / next.zoom };
    expect(next.zoom).toBeCloseTo(1.12);
    expect(after.x).toBeCloseTo(before.x);
    expect(after.y).toBeCloseTo(before.y);
    expect(wheelViewport({ x: 0, y: 0, zoom: 2 }, bounds, { ...event, deltaY: -1000 })).toBeUndefined();
  });

  it("居中节点时保持当前缩放级别", () => {
    expect(centeredViewport({ x: 0, y: 0, zoom: 0.75 }, { x: 200, y: 100 }, bounds))
      .toEqual({ x: 150, y: 125, zoom: 0.75 });
  });

  it("分叉候选镜头缩小以让当前节点和全部候选落在窄窗可视区", () => {
    const fitted = fitBoundsViewport({ x: 0, y: 0, zoom: 1 }, [
      { left: 20, top: 40, right: 220, bottom: 100 },
      { left: 620, top: 160, right: 820, bottom: 220 },
    ], { width: 420, height: 300 }, 30);
    expect(fitted?.zoom).toBeCloseTo(0.45);
    const projected = [20, 820].map(x => x * fitted!.zoom + fitted!.x);
    expect(projected[0]).toBeGreaterThanOrEqual(30);
    expect(projected[1]).toBeLessThanOrEqual(390);
  });

  it("新焦点重定向动画，手势取消后不再写旧视口", () => {
    let nextFrame = 0;
    const callbacks = new Map<number, (time: number) => void>();
    const frames: FrameDriver = {
      request: callback => { const id = ++nextFrame; callbacks.set(id, callback); return id; },
      cancel: id => { callbacks.delete(id); },
    };
    const animator = new ViewportAnimator(frames), writes: Viewport[] = [];
    const runFrame = (time: number) => {
      const [id, callback] = [...callbacks.entries()][0];
      callbacks.delete(id);
      callback(time);
    };
    animator.animate({ x: 0, y: 0, zoom: 1 }, { x: 100, y: 0, zoom: 1 }, value => writes.push(value));
    runFrame(0);
    animator.animate({ x: 10, y: 0, zoom: 1 }, { x: 0, y: 100, zoom: 1 }, value => writes.push(value));
    runFrame(100);
    expect(writes.at(-1)?.x).toBe(10);
    expect(writes.at(-1)?.y).toBe(0);
    animator.cancel();
    const count = writes.length;
    expect(callbacks.size).toBe(0);
    expect(writes).toHaveLength(count);
  });
});
