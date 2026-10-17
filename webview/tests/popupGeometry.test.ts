import { describe, expect, it } from "vitest";
import { popupPosition } from "../src/components/popupGeometry";

describe("弹层屏幕定位", () => {
  const size = { width: 340, height: 240 }, viewport = { width: 1000, height: 780 };
  it("优先在图标下方保持6px间距，不额外应用画布缩放", () => {
    expect(popupPosition({ left: 320, right: 336, top: 260, bottom: 276 }, size, viewport)).toEqual({ left: 320, top: 282 });
  });
  it("底部空间不足时翻到图标上方，右侧限制在窗口内", () => {
    expect(popupPosition({ left: 980, right: 996, top: 700, bottom: 716 }, size, viewport)).toEqual({ left: 652, top: 454 });
  });
  it("窄窗、矮窗和触发点移出视口时仍有8px边距", () => {
    expect(popupPosition({ left: -100, right: 0, top: -20, bottom: 10 }, { width: 304, height: 200 }, { width: 320, height: 220 })).toEqual({ left: 8, top: 12 });
  });
  it("没有鼠标锚点时显式居中，不采用浏览器默认定位", () => {
    expect(popupPosition(undefined, size, viewport)).toEqual({ left: 330, top: 270 });
  });
});
