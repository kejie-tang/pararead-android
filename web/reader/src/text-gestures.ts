/* SPDX-License-Identifier: Apache-2.0 */
/** One horizontal swipe turns one content page; the OS owns screen-edge gestures. */
export function textSwipePage(dx: number, dy: number): -1 | 0 | 1 {
  return Math.abs(dx) >= 60 && Math.abs(dx) > Math.abs(dy) * 1.5 ? (dx < 0 ? 1 : -1) : 0;
}
export function watchTextSwipes(content: HTMLElement, turn: (direction: -1 | 1) => void): () => void {
  let gesture: { x: number; y: number; horizontal: boolean } | undefined;
  const selected = () => window.getSelection()?.isCollapsed === false;
  const start = (event: TouchEvent) => {
    gesture = undefined;
    if (event.touches.length !== 1 || document.body.inert || selected() || content.hasAttribute("aria-busy")) return;
    const target = event.target as HTMLElement | null, touch = event.touches[0];
    if (target?.closest("button, input, select, textarea, a, [contenteditable], [role=separator]")) return;
    if (touch.clientX < 28 || touch.clientX > window.innerWidth - 28) return;
    gesture = { x: touch.clientX, y: touch.clientY, horizontal: false };
  };
  const move = (event: TouchEvent) => {
    if (!gesture) return;
    if (event.touches.length !== 1 || selected() || document.body.inert) { gesture = undefined; return; }
    const dx = event.touches[0].clientX - gesture.x, dy = event.touches[0].clientY - gesture.y;
    // Once vertical reading/pinch/selection owns the gesture it cannot become a page turn.
    if (!gesture.horizontal && Math.abs(dy) > 12 && Math.abs(dy) >= Math.abs(dx)) { gesture = undefined; return; }
    if (Math.abs(dx) > 12 && Math.abs(dx) > Math.abs(dy) * 1.5) gesture.horizontal = true;
    if (gesture.horizontal && event.cancelable) event.preventDefault();
  };
  const end = (event: TouchEvent) => {
    const current = gesture; gesture = undefined;
    if (!current || event.touches.length || event.changedTouches.length !== 1 || selected() || document.body.inert) return;
    const touch = event.changedTouches[0], direction = textSwipePage(touch.clientX - current.x, touch.clientY - current.y);
    if (direction) turn(direction);
  };
  const cancel = () => { gesture = undefined; };
  content.addEventListener("touchstart", start, { passive: true });
  content.addEventListener("touchmove", move, { passive: false });
  content.addEventListener("touchend", end, { passive: true });
  content.addEventListener("touchcancel", cancel, { passive: true });
  return () => {
    content.removeEventListener("touchstart", start); content.removeEventListener("touchmove", move);
    content.removeEventListener("touchend", end); content.removeEventListener("touchcancel", cancel);
  };
}
