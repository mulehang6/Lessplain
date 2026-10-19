import { describe, expect, it } from "vitest";
import { centeredViewport, fitBoundsViewport, ViewportAnimator, wheelPanDelta, wheelViewport, type FrameDriver, type Viewport } from "../src/graph/interaction";

describe("图表相机手势", () => {
  const bounds = { left: 20, top: 30, width: 600, height: 400 };

  it("普通滚轮纵向平移，Shift 仅转换没有横向分量的滚轮", () => {
    const initial = { x: 12, y: 34, zoom: 1 };
    expect(wheelViewport(initial, bounds, { clientX: 100, clientY: 100, deltaX: 0, deltaY: 80, shiftKey: false, ctrlKey: false }))
      .toEqual({ x: 12, y: -46, zoom: 1 });
    expect(wheelViewport(initial, bounds, { clientX: 100, clientY: 100, deltaX: 25, deltaY: 80, shiftKey: true, ctrlKey: false }))
      .toEqual({ x: -13, y: -46, zoom: 1 });
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

  it("直接横向与斜向输入不丢失，行和页面单位先归一化", () => {
    const initial = { x: 0, y: 0, zoom: 1 };
    const event = { clientX: 100, clientY: 100, deltaX: -30, deltaY: 0, shiftKey: false, ctrlKey: false };
    expect(wheelViewport(initial, bounds, event)).toEqual({ x: 30, y: 0, zoom: 1 });
    expect(wheelViewport(initial, bounds, { ...event, deltaY: 10 })).toEqual({ x: 30, y: -10, zoom: 1 });
    expect(wheelViewport(initial, bounds, { ...event, deltaMode: 1, deltaX: 2 })).toEqual({ x: -32, y: 0, zoom: 1 });
    expect(wheelViewport(initial, bounds, { ...event, deltaMode: 2, deltaX: 1 })).toEqual({ x: -600, y: 0, zoom: 1 });
  });

  it("居中节点时保持当前缩放级别", () => {
    expect(centeredViewport({ x: 0, y: 0, zoom: 0.75 }, { x: 200, y: 100 }, bounds))
      .toEqual({ x: 150, y: 125, zoom: 0.75 });
  });

  it.each([[30, 20], [-30, 20], [30, -20], [-30, -20]])("双轴 Shift 输入保留方向 %s/%s", (deltaX, deltaY) => {
    const event = { clientX: 100, clientY: 100, deltaX, deltaY, shiftKey: true, ctrlKey: false };
    expect(wheelViewport({ x: 10, y: 20, zoom: 0.5 }, bounds, event))
      .toEqual({ x: 10 - deltaX, y: 20 - deltaY, zoom: 0.5 });
    expect(wheelPanDelta(event, bounds)).toEqual({ x: deltaX, y: deltaY });
    expect(wheelPanDelta({ ...event, deltaMode: 1 }, bounds)).toEqual({ x: deltaX * 16, y: deltaY * 16 });
    expect(wheelPanDelta({ ...event, deltaMode: 2 }, bounds)).toEqual({ x: deltaX * 600, y: deltaY * 400 });
  });

  it("连续双轴及纯轴事件累计位移，OSR 只校正真实横向通道", () => {
    let viewport = { x: 10, y: 20, zoom: 1 };
    for (let i = 0; i < 100; i++) {
      viewport = wheelViewport(viewport, bounds, { clientX: 100, clientY: 100,
        deltaX: -2, deltaY: 3, shiftKey: true, ctrlKey: false }, 0.08, 2, -1)!;
    }
    expect(viewport).toEqual({ x: -190, y: -280, zoom: 1 });
    expect(wheelViewport(viewport, bounds, { clientX: 100, clientY: 100,
      deltaX: 0, deltaY: 0, shiftKey: true, ctrlKey: false })).toBeUndefined();
    expect(wheelPanDelta({ deltaX: 0, deltaY: 2, deltaMode: 2, shiftKey: true }, bounds, -1))
      .toEqual({ x: 1200, y: 0 });
  });

  it("Windows OSR 横向通道校正符号，保留纵向与 Shift 的 deltaY 回退", () => {
    const initial = { x: 0, y: 0, zoom: 1 };
    const event = { clientX: 100, clientY: 100, deltaX: -132, deltaY: 0, shiftKey: true, ctrlKey: false };
    expect(wheelViewport(initial, bounds, event, 0.08, 2, -1)).toEqual({ x: -132, y: 0, zoom: 1 });
    expect(wheelViewport(initial, bounds, { ...event, shiftKey: false, deltaY: 20 }, 0.08, 2, -1)).toEqual({ x: -132, y: -20, zoom: 1 });
    expect(wheelViewport(initial, bounds, { ...event, deltaX: 0, deltaY: 80 }, 0.08, 2, -1)).toEqual({ x: -80, y: 0, zoom: 1 });
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
