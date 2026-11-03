/* SPDX-License-Identifier: Apache-2.0 */
export interface TextNavigationChapter { readonly title: string; readonly page: number }
export interface TextNavigationOptions {
  language: "en" | "zh";
  pages: number;
  chapters: readonly TextNavigationChapter[];
  go(page: number): void;
  /** Fetch ONE native batch (count: 100), then call addChapters(batch, more).
   * Reject on failure so the panel can offer an explicit retry. Never prefetch in a loop. */
  moreChapters(): Promise<void>;
}

function pageCount(pages: number): number {
  return Number.isSafeInteger(pages) && pages > 0 ? pages : 1;
}
function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, Number.isFinite(value) ? value : min));
}
/** One-based pages: first = 0%, last = 100%. A one-page book is always 0%. */
export function pageToPercent(page: number, pages: number): number {
  const count = pageCount(pages);
  return count === 1 ? 0 : (clamp(Math.round(page), 1, count) - 1) / (count - 1) * 100;
}
/** Round to the nearest one-based page; invalid percentages fall back to page 1. */
export function percentToPage(percent: number, pages: number): number {
  return 1 + Math.round(clamp(percent, 0, 100) / 100 * (pageCount(pages) - 1));
}

const labels = {
  en: { title: "Chapters & progress", close: "Close", chapters: "Chapters", progress: "Reading progress",
    percent: "Position (%)", go: "Go", more: "Load more chapters", retry: "Retry loading chapters",
    loading: "Loading chapters…", failed: "Chapters could not be loaded. Please retry.",
    empty: "No chapters available. You can still navigate by percentage below.",
    single: "This book has one page. You are already at the beginning and end.",
    invalid: "Enter a percentage from 0 to 100.", page: (page: number, pages: number) => `Page ${page} of ${pages}`,
    chapterPage: (page: number) => `Page ${page}` },
  zh: { title: "章节与进度", close: "关闭", chapters: "章节", progress: "阅读进度",
    percent: "阅读位置（%）", go: "跳转", more: "加载更多章节", retry: "重试加载章节",
    loading: "正在加载章节…", failed: "章节加载失败，请重试。",
    empty: "暂无章节，仍可通过下方百分比跳转阅读位置。",
    single: "本书仅有一页，当前已是开头和结尾。",
    invalid: "请输入 0 到 100 之间的百分比。", page: (page: number, pages: number) => `第 ${page} 页，共 ${pages} 页`,
    chapterPage: (page: number) => `第 ${page} 页` },
};
let nextPanelId = 0;

/** Standalone native modal. Load text-navigation.css alongside the reader token stylesheet.
 * Construction copies initial chapters and starts with more=false. Call addChapters([], more)
 * to initialize availability. addChapters appends only unseen (title, page) pairs, in supplied order.
 * open/setPage take one-based pages; progress inputs only preview until Go is submitted.
 * No loading occurs on open/append: each near-bottom scroll or button activation requests at
 * most one batch, with one request in flight. After failure, only the retry button can retry.
 * The caller MUST close() in its reading-state flusher before setting body.inert (native
 * modal dialogs escape inherited inertness). Navigation is additionally gated on body.inert.
 * dispose() closes/removes only this panel, detaches handlers, and ignores late async results.
 */
export class TextNavigationPanel {
  readonly #options: TextNavigationOptions;
  readonly #strings: typeof labels.en;
  readonly #document: Document;
  readonly #dialog: HTMLDialogElement;
  readonly #list: HTMLOListElement;
  readonly #empty: HTMLParagraphElement;
  readonly #load: HTMLButtonElement;
  readonly #status: HTMLParagraphElement;
  readonly #range: HTMLInputElement;
  readonly #number: HTMLInputElement;
  readonly #preview: HTMLOutputElement;
  readonly #error: HTMLParagraphElement;
  readonly #go: HTMLButtonElement;
  readonly #events = new AbortController();
  readonly #chapters: TextNavigationChapter[] = [];
  readonly #seen = new Set<string>();
  readonly #buttons: HTMLButtonElement[] = [];
  #page = 1;
  #previewPage = 1;
  #more = false;
  #loading = false;
  #failed = false;
  #disposed = false;

  constructor(root: HTMLElement, options: TextNavigationOptions) {
    if (!Number.isSafeInteger(options.pages) || options.pages < 1) throw new RangeError("Invalid TXT page count");
    this.#options = { ...options, chapters: [] };
    this.#strings = labels[options.language];
    this.#document = root.ownerDocument;
    const text = this.#strings;
    const prefix = `text-navigation-${++nextPanelId}`;
    const make = <K extends keyof HTMLElementTagNameMap>(tag: K, className: string, content?: string): HTMLElementTagNameMap[K] => {
      const element = this.#document.createElement(tag);
      element.className = className;
      if (content !== undefined) element.textContent = content;
      return element;
    };
    this.#dialog = make("dialog", "textNavigationPanel");
    this.#dialog.lang = options.language;
    const header = make("header", "textNavigationPanelHeader");
    const heading = make("h2", "textNavigationPanelTitle", text.title);
    heading.id = `${prefix}-title`;
    this.#dialog.setAttribute("aria-labelledby", heading.id);
    const close = make("button", "textNavigationPanelClose", text.close);
    close.type = "button";
    close.autofocus = true;
    header.append(heading, close);
    const body = make("div", "textNavigationPanelBody");
    const chapters = make("section", "textNavigationPanelChapters");
    const chapterHeading = make("h3", "textNavigationPanelSubtitle", text.chapters);
    chapterHeading.id = `${prefix}-chapters`;
    this.#list = make("ol", "textNavigationPanelList");
    this.#list.setAttribute("aria-labelledby", chapterHeading.id);
    this.#list.tabIndex = 0;
    this.#empty = make("p", "textNavigationPanelHint", text.empty);
    this.#load = make("button", "textNavigationPanelLoad", text.more);
    this.#load.type = "button";
    this.#status = make("p", "textNavigationPanelStatus");
    this.#status.setAttribute("role", "status");
    chapters.append(chapterHeading, this.#empty, this.#list, this.#load, this.#status);
    const form = make("form", "textNavigationPanelProgress");
    const progressHeading = make("h3", "textNavigationPanelSubtitle", text.progress);
    progressHeading.id = `${prefix}-progress`;
    form.setAttribute("aria-labelledby", progressHeading.id);
    const rangeLabel = make("label", "textNavigationPanelLabel", text.percent);
    this.#range = make("input", "textNavigationPanelRange");
    this.#range.type = "range";
    this.#range.id = `${prefix}-range`;
    rangeLabel.htmlFor = this.#range.id;
    const controls = make("div", "textNavigationPanelControls");
    const numberLabel = make("label", "textNavigationPanelNumberLabel", text.percent);
    this.#number = make("input", "textNavigationPanelNumber");
    this.#number.type = "number";
    this.#number.inputMode = "decimal";
    this.#number.id = `${prefix}-number`;
    numberLabel.htmlFor = this.#number.id;
    for (const input of [this.#range, this.#number]) {
      input.min = "0"; input.max = "100"; input.step = "any";
      input.disabled = options.pages === 1;
    }
    this.#number.required = true;
    this.#go = make("button", "textNavigationPanelGo", text.go);
    this.#go.type = "submit";
    this.#go.disabled = options.pages === 1;
    controls.append(numberLabel, this.#number, this.#go);
    this.#preview = make("output", "textNavigationPanelPreview");
    this.#preview.id = `${prefix}-preview`;
    this.#preview.setAttribute("for", `${this.#range.id} ${this.#number.id}`);
    this.#error = make("p", "textNavigationPanelError");
    this.#error.id = `${prefix}-error`;
    this.#error.setAttribute("role", "status");
    this.#number.setAttribute("aria-describedby", `${this.#preview.id} ${this.#error.id}`);
    this.#range.setAttribute("aria-describedby", this.#preview.id);
    form.append(progressHeading, rangeLabel, this.#range, controls, this.#preview, this.#error);
    if (options.pages === 1) form.append(make("p", "textNavigationPanelHint", text.single));
    body.append(chapters, form);
    this.#dialog.append(header, body);
    root.append(this.#dialog);
    const signal = this.#events.signal;
    close.addEventListener("click", () => this.close(), { signal });
    // Contain reader shortcuts only while this modal is open. Keep native Escape and
    // input/list keyboard defaults intact; no document/window key handlers are installed.
    this.#dialog.addEventListener("keydown", event => {
      if (this.#dialog.open) event.stopPropagation();
    }, { signal });
    this.#list.addEventListener("click", event => {
      const target = event.target instanceof Element ? event.target.closest<HTMLButtonElement>("button[data-chapter]") : null;
      if (target && this.#list.contains(target)) this.#commit(this.#chapters[Number(target.dataset.chapter)].page);
    }, { signal });
    this.#list.addEventListener("scroll", () => {
      if (this.#list.scrollHeight > this.#list.clientHeight &&
        this.#list.scrollHeight - this.#list.scrollTop - this.#list.clientHeight <= 100 && !this.#failed) void this.#loadMore();
    }, { signal, passive: true });
    this.#load.addEventListener("click", () => { void this.#loadMore(); }, { signal });
    this.#range.addEventListener("input", () => this.#input(this.#range), { signal });
    this.#number.addEventListener("input", () => this.#input(this.#number), { signal });
    // Custom validation also gives a persistent, localized error beside the numeric field.
    form.noValidate = true;
    form.addEventListener("submit", event => {
      event.preventDefault();
      if (options.pages === 1 || !this.#validate()) return;
      this.#commit(this.#previewPage);
    }, { signal });
    this.addChapters(options.chapters, false);
    this.setPage(1);
  }

  open(page: number): void {
    if (this.#disposed || this.#document.body.inert) return;
    this.setPage(page);
    if (!this.#dialog.open) this.#dialog.showModal();
  }

  close(): void {
    if (this.#dialog.open) this.#dialog.close();
  }

  setPage(page: number): void {
    if (this.#disposed) return;
    this.#page = clamp(Math.round(page), 1, this.#options.pages);
    this.#previewPage = this.#page;
    const percent = pageToPercent(this.#page, this.#options.pages);
    this.#range.value = String(percent);
    this.#number.value = String(Math.round(percent * 100) / 100);
    this.#error.textContent = "";
    this.#number.removeAttribute("aria-invalid");
    this.#go.disabled = this.#options.pages === 1;
    this.#updatePreview();
    this.#updateActive();
  }

  addChapters(chapters: readonly TextNavigationChapter[], more: boolean): void {
    if (this.#disposed) return;
    const fragment = this.#document.createDocumentFragment();
    for (const chapter of chapters) {
      const key = JSON.stringify([chapter.title, chapter.page]);
      if (this.#seen.has(key)) continue;
      this.#seen.add(key);
      const index = this.#chapters.length;
      this.#chapters.push({ title: chapter.title, page: chapter.page });
      const item = this.#document.createElement("li");
      const button = this.#document.createElement("button");
      button.type = "button";
      button.className = "textNavigationPanelChapter";
      button.dataset.chapter = String(index);
      const title = this.#document.createElement("span");
      title.textContent = chapter.title;
      const page = this.#document.createElement("span");
      page.className = "textNavigationPanelChapterPage";
      page.textContent = this.#strings.chapterPage(chapter.page);
      button.append(title, page);
      item.append(button);
      fragment.append(item);
      this.#buttons.push(button);
    }
    this.#list.append(fragment);
    this.#more = more;
    this.#empty.hidden = this.#chapters.length > 0;
    this.#list.hidden = this.#chapters.length === 0;
    this.#updateLoad();
    this.#updateActive();
  }

  dispose(): void {
    if (this.#disposed) return;
    this.#disposed = true;
    this.close();
    this.#events.abort();
    this.#dialog.remove();
  }

  #commit(page: number): void {
    if (this.#disposed || !this.#dialog.open || this.#document.body.inert) return;
    this.close();
    // Closing restores focus; focus handlers may initiate the save freeze.
    if (!this.#document.body.inert && !this.#disposed) this.#options.go(clamp(Math.round(page), 1, this.#options.pages));
  }

  #validate(): boolean {
    const percent = this.#number.valueAsNumber;
    const valid = this.#number.value.trim() !== "" && Number.isFinite(percent) && percent >= 0 && percent <= 100;
    this.#number.setAttribute("aria-invalid", String(!valid));
    this.#error.textContent = valid ? "" : this.#strings.invalid;
    this.#go.disabled = !valid || this.#options.pages === 1;
    return valid;
  }

  #input(input: HTMLInputElement): void {
    if (input === this.#range) this.#number.value = input.value;
    if (!this.#validate()) return;
    this.#range.value = this.#number.value;
    this.#previewPage = percentToPage(this.#number.valueAsNumber, this.#options.pages);
    this.#updatePreview();
  }

  #updatePreview(): void {
    const text = this.#strings.page(this.#previewPage, this.#options.pages);
    this.#preview.textContent = text;
    this.#range.setAttribute("aria-valuetext", text);
  }

  #updateActive(): void {
    let active = -1;
    for (let index = 0; index < this.#chapters.length; index++) {
      if (this.#chapters[index].page <= this.#page && (active < 0 || this.#chapters[index].page >= this.#chapters[active].page)) active = index;
    }
    this.#buttons.forEach((button, index) => {
      if (index === active) button.setAttribute("aria-current", "location");
      else button.removeAttribute("aria-current");
    });
  }

  #updateLoad(): void {
    this.#load.hidden = !this.#more && !this.#failed;
    this.#load.disabled = this.#loading;
    this.#load.textContent = this.#loading ? this.#strings.loading : this.#failed ? this.#strings.retry : this.#strings.more;
    this.#list.setAttribute("aria-busy", String(this.#loading));
    this.#status.textContent = this.#loading ? this.#strings.loading : this.#failed ? this.#strings.failed : "";
  }

  async #loadMore(): Promise<void> {
    if (this.#disposed || !this.#dialog.open || this.#document.body.inert || (!this.#more && !this.#failed) || this.#loading) return;
    this.#loading = true;
    this.#failed = false;
    this.#updateLoad();
    try { await this.#options.moreChapters(); }
    catch { if (!this.#disposed) this.#failed = true; }
    finally {
      if (!this.#disposed) { this.#loading = false; this.#updateLoad(); }
    }
  }
}
