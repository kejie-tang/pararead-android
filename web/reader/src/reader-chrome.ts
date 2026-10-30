/* SPDX-License-Identifier: Apache-2.0 */
import type { TranslationPanel } from "./upstream/panel";
import type { SelectionController } from "./upstream/selection";

/** Full-screen controls follow deliberate reading gestures, never synchronized scrolling. */
export class ReaderChrome {
  readonly #app: PdfjsApplication;
  readonly #selection: SelectionController;
  readonly #bars: HTMLElement[];
  readonly #reveal: HTMLButtonElement;
  #fullscreen = false;
  #hidden = false;
  #distance = 0;
  #lastInput = 0;

  constructor(app: PdfjsApplication, panel: TranslationPanel, selection: SelectionController, label: string) {
    this.#app = app;
    this.#selection = selection;
    this.#bars = [...document.querySelectorAll<HTMLElement>("#toolbarContainer, .bilingualHeader")];
    this.#reveal = document.createElement("button");
    this.#reveal.type = "button";
    this.#reveal.id = "readerShowControls";
    this.#reveal.hidden = true;
    const icon = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    icon.setAttribute("viewBox", "0 0 24 24");
    icon.setAttribute("aria-hidden", "true");
    const path = document.createElementNS(icon.namespaceURI, "path");
    path.setAttribute("d", "m6 9 6 6 6-6");
    icon.append(path);
    this.#reveal.append(icon);
    // The viewer's main container has a desktop minimum width. Anchor to the actual
    // PDF pane so the button stays reachable on narrow phones and split windows.
    document.querySelector("#outerContainer")!.append(this.#reveal);
    this.setLabel(label);
    this.#reveal.addEventListener("click", () => {
      this.show();
      const buttons = this.#bars[0]?.querySelectorAll<HTMLButtonElement>("button") ?? [];
      [...buttons].find(button => !button.disabled && button.getClientRects().length > 0)?.focus({ preventScroll: true });
    });

    this.#watch(app.pdfViewer.container);
    this.#watch(panel.body);
    selection.onChange(current => { if (current?.pinned) this.show(); });
    app.eventBus.on("annotationeditormodechanged", ({ mode }: { mode: number }) => {
      if (mode > 0) this.show();
    });
    document.addEventListener("selectionchange", () => {
      if (!window.getSelection()?.isCollapsed) this.show();
    });
    document.addEventListener("focusin", () => { if (this.#blocked()) this.show(); });
    // Keyboard navigation can always bring the hidden controls back into the tab order.
    document.addEventListener("keydown", event => { if (event.key === "Tab" || event.key === "Escape") this.show(); });
    window.addEventListener("resize", () => this.show());
  }

  setLabel(label: string): void {
    this.#reveal.setAttribute("aria-label", label);
    this.#reveal.title = label;
  }

  setFullscreen(enabled: boolean): void {
    if (this.#fullscreen === enabled) return;
    this.#fullscreen = enabled;
    document.body.classList.toggle("readerFullscreen", enabled);
    this.show();
  }

  show(): void {
    this.#distance = 0;
    this.#setHidden(false);
  }

  #blocked(): boolean {
    const active = document.activeElement;
    return this.#app.pdfViewer.annotationEditorMode > 0 || this.#selection.current?.pinned === true ||
      window.getSelection()?.isCollapsed === false || document.body.classList.contains("bilingualResizing") ||
      active?.matches("input, textarea, select, [contenteditable=true]") === true ||
      document.querySelector("dialog[open], #outerContainer.sidebarOpen, #findbar:not(.hidden), #secondaryToolbar:not(.hidden)") !== null;
  }

  #read(delta: number, container: HTMLElement): void {
    if (!this.#fullscreen || this.#blocked()) { this.show(); return; }
    if (!Number.isFinite(delta) || Math.abs(delta) < 1) return;
    // Ignore short pages, horizontal gestures and attempts to scroll beyond the last page.
    if (delta > 0 && container.scrollTop >= container.scrollHeight - container.clientHeight - 2) return;
    if (performance.now() - this.#lastInput > 300 || Math.sign(delta) !== Math.sign(this.#distance)) this.#distance = 0;
    this.#lastInput = performance.now();
    this.#distance += delta;
    if (!this.#hidden && this.#distance >= 40) this.#setHidden(true);
    else if (this.#hidden && this.#distance <= -16) this.show();
  }

  #watch(container: HTMLElement): void {
    let touch: { y: number; startX: number; startY: number } | undefined;
    container.addEventListener("touchstart", event => {
      this.#distance = 0;
      const first = event.touches[0];
      touch = event.touches.length === 1 ? { y: first.clientY, startX: first.clientX, startY: first.clientY } : undefined;
      if (!touch || this.#blocked()) this.show();
    }, { passive: true });
    container.addEventListener("touchmove", event => {
      if (event.touches.length !== 1) { touch = undefined; this.show(); return; }
      if (!touch) return;
      const current = event.touches[0];
      const delta = touch.y - current.clientY;
      const vertical = Math.abs(current.clientY - touch.startY) > Math.abs(current.clientX - touch.startX) * 1.2;
      touch.y = current.clientY;
      if (vertical) this.#read(delta, container);
    }, { passive: true });
    container.addEventListener("touchend", () => { touch = undefined; }, { passive: true });
    container.addEventListener("touchcancel", () => { touch = undefined; }, { passive: true });
    container.addEventListener("wheel", event => {
      if (event.ctrlKey || event.metaKey) { this.show(); return; }
      if (Math.abs(event.deltaY) <= Math.abs(event.deltaX)) return;
      const unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? container.clientHeight : 1;
      this.#read(event.deltaY * unit, container);
    }, { passive: true });
    container.addEventListener("keydown", event => {
      if (event.defaultPrevented || event.ctrlKey || event.metaKey || event.altKey) return;
      const delta = ["ArrowDown", "PageDown"].includes(event.key) || (event.key === " " && !event.shiftKey) ? 80 :
        ["ArrowUp", "PageUp"].includes(event.key) || (event.key === " " && event.shiftKey) ? -80 : 0;
      this.#read(delta, container);
    });
  }

  #setHidden(hidden: boolean): void {
    if (this.#hidden === hidden) return;
    this.#hidden = hidden;
    this.#distance = 0;
    document.body.classList.toggle("readerChromeHidden", hidden);
    for (const bar of this.#bars) bar.inert = hidden;
    this.#reveal.hidden = !hidden;
  }
}
