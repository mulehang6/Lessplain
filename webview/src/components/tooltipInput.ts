/** 只让用户主动的键盘聚焦打开提示；弹层内部的程序聚焦不冒出提示。 */
let keyboardFocus = false;
document.addEventListener("keydown", event => { keyboardFocus = ["Tab", "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight"].includes(event.key); }, true);
document.addEventListener("pointerdown", () => { keyboardFocus = false; }, true);
export const isKeyboardFocus = () => keyboardFocus;
export function dismissTooltips() {
  keyboardFocus = false;
  window.dispatchEvent(new Event("lessplain:popup-open"));
}
