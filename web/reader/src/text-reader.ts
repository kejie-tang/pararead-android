/* SPDX-License-Identifier: Apache-2.0 */
import type { Session } from "./bridge";
import type { HostToWebview, TranslateRange, WebviewToHost } from "./messages";
import { setStrings } from "./upstream/i18n";
import { renderSentence } from "./upstream/panel";
import type { PageSegmentation, Sentence } from "./upstream/segmenter/types";
import { TranslationClient } from "./upstream/translation-client";
import { TextSentenceStore, type TextBridge } from "./text-store";
import { TextNavigationPanel } from "./text-navigation";
import { watchTextSwipes } from "./text-gestures";

export type TextSession = Session & { text?: { pages: number; chapters: { title: string; page: number }[]; chapterCount?: number; encoding: string; indexVersion: number } };
export interface TextReaderBridge extends TextBridge {
  post(type: string, payload: Record<string, unknown>): unknown;
  viewport(page: number): void;
  cachedTranslations(sentences: readonly Sentence[]): Promise<readonly { id: string; translation: string }[]>;
  opened?: boolean;
  setReadingStateFlusher?(snapshot?: () => Record<string, unknown>): void;
}
export interface TextReading { page: number; txt: { fontSize: number; showSource: boolean; scrollTop: number; columnRatio?: number } }
export function restoreTextReading(reading: any, pages: number): TextReading {
  const number = (value: unknown, fallback: number) => typeof value === "number" && Number.isFinite(value) ? value : fallback;
  return { page: Math.max(1, Math.min(pages, Math.trunc(number(reading?.page, 1)))), txt: {
    fontSize: Math.max(12, Math.min(32, number(reading?.txt?.fontSize, 18))),
    showSource: reading?.txt?.showSource !== false,
    scrollTop: Math.max(0, number(reading?.txt?.scrollTop, 0)),
    ...(typeof reading?.txt?.columnRatio === "number" && Number.isFinite(reading.txt.columnRatio)
      ? { columnRatio: Math.max(.25, Math.min(.75, reading.txt.columnRatio)) } : {}),
  } };
}
export function parseTextPage(value: string, pages: number): number | undefined {
  if (!/^\d+$/u.test(value.trim())) return undefined;
  const page = Number(value);
  return Number.isSafeInteger(page) && page >= 1 && page <= pages ? page : undefined;
}
const labels = {
  en: { title: "TXT reader", previous: "Previous", next: "Next", page: "Page", of: "of", jump: "Go", chapters: "Chapters",
    beginning: "No chapters / Start", more: "More chapters…", font: "Font size", source: "Show original", loading: "Loading…", ready: "Ready", invalid: "Invalid page number",
    translate: "Translate page", controls: "Show controls", hideControls: "Hide reading toolbar", split: "Original column width", translating: "Translating…", done: "Translation finished", cancelled: "Translation cancelled", error: "Error", failed: "Translation failed, click to retry: {0}" },
  zh: { title: "TXT 阅读器", previous: "上一页", next: "下一页", page: "页码", of: "/", jump: "跳转", chapters: "章节",
    beginning: "无章节 / 开始", more: "更多章节…", font: "字号", source: "显示原文", loading: "加载中…", ready: "就绪", invalid: "页码无效",
    translate: "翻译当前页", controls: "显示控件", hideControls: "收起阅读工具栏", split: "原文栏宽度", translating: "翻译中…", done: "翻译完成", cancelled: "翻译已取消", error: "错误", failed: "翻译失败，点击重试：{0}" },
};
function translateRange(value: unknown): TranslateRange { return value === "page" || value === "nearby" ? value : "manual"; }

/** A standalone TXT session. Background mode never creates UI or writes reading history. */
export function startTextReader({ session, bridge, post, root = document.body }: {
  session: TextSession; bridge: TextReaderBridge; post: (message: WebviewToHost) => void | Promise<unknown>; root?: HTMLElement;
}): { stop(): void; store: TextSentenceStore; client: TranslationClient } {
  const pages = session.text?.pages;
  if (!Number.isSafeInteger(pages) || !pages || pages < 1) throw new Error("INVALID_TEXT_SESSION");
  const count = pages;
  const lang = String(session.settings.uiLanguage ?? document.documentElement.lang).toLowerCase().startsWith("zh") ? "zh" : "en";
  document.documentElement.lang = lang;
  const strings = labels[lang];
  document.title = strings.title;
  setStrings({ translationFailed: strings.failed });
  const background = session.settings.backgroundTranslation === true;
  const readingOnly = !background && session.settings.readingOnly === true;
  const state = restoreTextReading(session.reading, count);
  let closed = false, renderVersion = 0, saveTimer: ReturnType<typeof setTimeout> | undefined;
  const pageListeners = new Set<(page: number) => void>();
  let view: TextReaderView | undefined;
  let viewReady = false;
  let jobStatus = "", jobActive = false;
  let jobPage: number | undefined;
  const fail = (error: unknown) => {
    if (closed) return;
    view?.status(`${strings.error}: ${String(error)}`);
    try { bridge.post("session.error", { message: String(error) }); } catch (reportError) { console.error(reportError); }
  };
  const store = new TextSentenceStore(count, bridge, fail);
  const trim = () => {
    store.trim(background ? 0 : state.page, background ? 0 : 1, jobPage);
    client.trim(background ? 0 : state.page, background ? 0 : 1);
  };
  const save = () => {
    if (saveTimer !== undefined) { clearTimeout(saveTimer); saveTimer = undefined; }
    if (background || closed) return;
    if (view && viewReady) state.txt.scrollTop = view.scrollTop;
    try { bridge.post("reading.save", { page: state.page, txt: { ...state.txt } }); } catch (error) { fail(error); }
  };
  const scheduleSave = () => { if (saveTimer === undefined) saveTimer = setTimeout(save, 250); };
  const stop = () => {
    if (closed) return;
    save(); closed = true; renderVersion++;
    bridge.opened = false;
    window.removeEventListener("message", onMessage);
    window.removeEventListener("pagehide", stop);
    bridge.setReadingStateFlusher?.();
    view?.dispose(); client.reset(); store.reset();
  };
  const client = new TranslationClient({
    app: { pageCount: () => count, currentPage: () => state.page, onPageChanging: listener => { pageListeners.add(listener); } },
    store, range: background ? "manual" : translateRange(session.settings.translateRange), readingOnly,
    restoreCached: sentences => bridge.cachedTranslations(sentences),
    post: message => {
      if (message.type === "translate") {
        // TranslationClient expects translationDone, not a rejected post promise.
        const rejected = (error: unknown) => {
          fail(error);
          client.handle({ type: "translationDone", requestId: message.requestId,
            failures: message.sentences.map(sentence => ({ id: sentence.id, message: String(error) })) });
        };
        try {
          if (closed) throw new Error("TEXT_SESSION_CLOSED");
          const pending = post(message);
          if (pending) void Promise.resolve(pending).catch(rejected);
        } catch (error) { rejected(error); }
        return;
      }
      return (async () => {
        if (closed) throw new Error("TEXT_SESSION_CLOSED");
        if (message.type === "translateAllProgress") {
          // Do not evict or advance extraction until native acknowledges job.saved.
          await post(message);
          jobStatus = `${strings.translating} ${message.done} / ${message.total}`;
          view?.status(jobStatus);
        } else if (message.type === "translateAllDone") {
          try { await post(message); }
          catch (error) {
            fail(error); jobActive = false; jobPage = undefined;
            if (background) stop(); else trim();
            throw error;
          }
          jobActive = false; jobPage = undefined;
          jobStatus = message.status === "COMPLETED" ? strings.done : message.status === "CANCELLED" ? strings.cancelled : strings.error;
          view?.status(jobStatus);
          if (background) stop(); else trim();
        } else await post(message);
      })();
    },
    onPageCompleted: page => { jobPage = page + 1; trim(); }, onFatal: fail,
  });
  // Late page turns cannot grow the cache; the whole-job page stays pinned until job.saved.
  store.onPageReady(trim);
  const showPage = async (page: number, scrollTop = 0, initial = false) => {
    if (closed || document.body.inert || page < 1 || page > count || !Number.isInteger(page)) return;
    const version = ++renderVersion;
    state.page = page; state.txt.scrollTop = scrollTop; viewReady = false;
    view?.loading(page);
    if (!initial && view) view.scrollTop = scrollTop;
    if (!initial) save();
    bridge.viewport(page);
    for (const listener of pageListeners) listener(page);
    try {
      const result = await store.ensure(page);
      if (closed || version !== renderVersion) return;
      view?.render(result, client);
      if (view) view.scrollTop = scrollTop;
      viewReady = true;
      view?.status(jobStatus || strings.ready);
      trim();
    } catch { /* ensure() has already reported the failure. */ }
  };
  const onMessage = (event: MessageEvent<HostToWebview>) => {
    if (closed || event.origin !== window.origin || !event.data || typeof event.data.type !== "string") return;
    const message = event.data;
    try {
      if (message.type === "readerFullscreen") { view?.fullscreen(message.enabled, message.showControlsLabel); return; }
      if (readingOnly && ["translateCurrentPage", "translateAll", "retryFailed", "translationDraft", "translations", "translationDone"].includes(message.type)) return;
      if (message.type === "translateCurrentPage") {
        if (!background) void client.translatePage(state.page).catch(fail);
        return;
      }
      if (message.type === "settings") {
        // Some hosts send partial settings updates (e.g. only the language).
        client.handle({ ...message, translateRange: background ? "manual" : translateRange(message.translateRange ?? session.settings.translateRange) });
        return;
      }
      if (["translateAll", "cancelTranslateAll", "translations", "translationDraft", "translationDone", "retryFailed"].includes(message.type)) {
        if (message.type === "translateAll" && !jobActive) {
          jobActive = true; jobPage = message.startPage ?? 1;
          jobStatus = strings.translating; view?.status(jobStatus);
        }
        client.handle(message);
      }
    } catch (error) { fail(error); }
  };
  if (!background) {
    const chapters = [...(session.text?.chapters ?? [])];
    let chapterTotal = session.text?.chapterCount ?? chapters.length;
    let loadingChapters = false;
    const moreChapters = async () => {
      if (loadingChapters || closed || chapters.length >= chapterTotal) return;
      loadingChapters = true;
      try {
        const response = await bridge.request("text.chapters", { offset: chapters.length, count: 100 });
        if (closed) return;
        if (response.type === "protocol.error") throw new Error(response.payload.code ?? "TEXT_CHAPTERS_FAILED");
        const { chapters: next, total } = response.payload;
        if (!Array.isArray(next) || next.length < 1 || next.length > 100 || !Number.isSafeInteger(total) || total < chapters.length + next.length ||
          next.some(chapter => typeof chapter.title !== "string" || !Number.isInteger(chapter.page) || chapter.page < 1 || chapter.page > count)) throw new Error("INVALID_TEXT_CHAPTERS");
        chapterTotal = total; chapters.push(...next);
        view?.addChapters(next, chapters.length < chapterTotal);
      } finally { loadingChapters = false; }
    };
    view = new TextReaderView(root, strings, state, count, chapters, {
      go: page => { void showPage(page).catch(fail); }, save: scheduleSave,
      translate: () => { void client.translatePage(state.page).catch(fail); },
      retry: sentence => { void client.translateBlockOf(sentence).catch(fail); },
      moreChapters: async () => { try { await moreChapters(); } catch (error) { fail(error); throw error; } },
    }, readingOnly);
    view.addChapters([], chapters.length < chapterTotal);
    view.fullscreen(session.settings.readerFullscreen === true, session.settings.showControlsLabel);
    client.onChange((id, translation) => view?.update(id, translation));
  }
  if (!background) bridge.setReadingStateFlusher?.(() => {
    view?.prepareToFlush();
    if (view && viewReady) state.txt.scrollTop = view.scrollTop;
    return { page: state.page, txt: { ...state.txt } };
  });
  window.addEventListener("message", onMessage);
  window.addEventListener("pagehide", stop);
  bridge.opened = true;
  // Host can immediately send translateAll: the client and listener now exist.
  bridge.post("session.opened", { pages: count });
  if (!background) { void showPage(state.page, state.txt.scrollTop, true).catch(fail); client.refresh(); }
  return { stop, store, client };
}

type Labels = typeof labels.en;
type ViewActions = { go(page: number): void; save(): void; translate(): void; retry(sentence: Sentence): void; moreChapters(): Promise<void> };
class TextReaderView {
  readonly #container = document.createElement("div");
  readonly #toolbar = document.createElement("header");
  readonly #content = document.createElement("main");
  readonly #status = document.createElement("div");
  readonly #page = document.createElement("input");
  readonly #chapters = document.createElement("button");
  readonly #navigation: TextNavigationPanel;
  readonly #split = document.createElement("div");
  readonly #stopSwipes: () => void;
  readonly #finishResize: () => void;
  #fullscreen = false;
  readonly #previous: HTMLButtonElement;
  readonly #next: HTMLButtonElement;
  readonly #showControls: HTMLButtonElement;
  readonly #hideControls: HTMLButtonElement;
  readonly #rows = new Map<string, { element: HTMLElement; sentence: Sentence }>();
  readonly #key: (event: KeyboardEvent) => void;
  readonly strings: Labels;
  readonly state: TextReading;
  readonly count: number;
  readonly chapters: { title: string; page: number }[];
  readonly actions: ViewActions;
  readonly readingOnly: boolean;
  constructor(root: HTMLElement, strings: Labels, state: TextReading, count: number,
    chapters: { title: string; page: number }[], actions: ViewActions, readingOnly = false) {
    this.readingOnly = readingOnly;
    this.strings = strings; this.state = state; this.count = count; this.chapters = chapters; this.actions = actions;
    const button = (text: string, action: () => void) => {
      const el = document.createElement("button"); el.type = "button"; el.textContent = text; el.onclick = action; return el;
    };
    const label = (text: string, control: HTMLElement) => {
      const el = document.createElement("label"); const caption = document.createElement("span"); caption.textContent = text;
      control.setAttribute("aria-label", text); el.append(caption, control); return el;
    };
    this.#container.className = "textReader";
    this.#toolbar.className = "textToolbar";
    this.#content.className = "textContent"; this.#content.tabIndex = 0;
    this.#content.setAttribute("aria-label", strings.title);
    this.#content.style.fontSize = `${state.txt.fontSize}px`;
    this.#container.classList.toggle("textHideSource", readingOnly || !state.txt.showSource);
    this.#status.className = "textStatus"; this.#status.setAttribute("role", "status");
    this.#previous = button(strings.previous, () => actions.go(state.page - 1)); this.#previous.className = "textPrevious";
    this.#next = button(strings.next, () => actions.go(state.page + 1)); this.#next.className = "textNext";
    this.#page.type = "text"; this.#page.inputMode = "numeric"; this.#page.value = String(state.page);
    this.#page.setAttribute("aria-label", strings.page);
    const form = document.createElement("form"); form.className = "textPageForm";
    const total = document.createElement("span"); total.textContent = `${strings.of} ${count}`;
    form.append(label(strings.page, this.#page), total, button(strings.jump, () => jump()));
    const jump = () => {
      const page = parseTextPage(this.#page.value, count);
      if (page === undefined) { this.status(strings.invalid); this.#page.setAttribute("aria-invalid", "true"); return; }
      this.#page.removeAttribute("aria-invalid"); actions.go(page);
    };
    form.onsubmit = event => { event.preventDefault(); jump(); };
    this.#navigation = new TextNavigationPanel(this.#container, {
      language: document.documentElement.lang.startsWith("zh") ? "zh" : "en", pages: count, chapters,
      go: page => { if (!document.body.inert) actions.go(page); }, moreChapters: actions.moreChapters,
    });
    this.#chapters.type = "button";
    this.#chapters.textContent = strings.chapters; this.#chapters.setAttribute("aria-label", strings.chapters);
    this.#chapters.setAttribute("aria-haspopup", "dialog");
    this.#chapters.onclick = () => this.#navigation.open(state.page);
    const font = document.createElement("input"); font.type = "range"; font.min = "12"; font.max = "32"; font.step = "1"; font.value = String(state.txt.fontSize);
    const fontValue = document.createElement("output"); fontValue.textContent = font.value;
    font.oninput = () => {
      this.#preserveAnchor(() => { state.txt.fontSize = Math.max(12, Math.min(32, Number(font.value))); this.#content.style.fontSize = `${state.txt.fontSize}px`; });
      fontValue.textContent = String(state.txt.fontSize); actions.save();
    };
    const source = document.createElement("input"); source.type = "checkbox"; source.checked = state.txt.showSource;
    source.onchange = () => {
      if (readingOnly) return;
      this.#preserveAnchor(() => { state.txt.showSource = source.checked; this.#container.classList.toggle("textHideSource", !source.checked); });
      actions.save();
    };
    const sourceLabel = label(strings.source, source); sourceLabel.className = "textSourceToggle"; sourceLabel.hidden = readingOnly;
    const translateButton = button(strings.translate, actions.translate); translateButton.className = "textTranslate"; translateButton.hidden = readingOnly;
    const navigation = document.createElement("div"); navigation.className = "textNavigation"; navigation.append(this.#previous, form, this.#next);
    const preferences = document.createElement("div"); preferences.className = "textPreferences";
    this.#chapters.className = "textChapterControl";
    const fontLabel = label(strings.font, font); fontLabel.className = "textFontControl"; fontLabel.append(fontValue);
    preferences.append(this.#chapters, fontLabel, sourceLabel, translateButton);
    this.#hideControls = button(strings.hideControls, () => this.#setControlsVisible(false));
    this.#hideControls.className = "textHideControls"; this.#hideControls.hidden = true;
    this.#toolbar.append(navigation, preferences, this.#hideControls);
    this.#showControls = button(strings.controls, () => this.#setControlsVisible(true)); this.#showControls.className = "textShowControls"; this.#showControls.hidden = true;
    const area = document.createElement("div"); area.className = "textReadingArea";
    this.#split.className = "textSplitHandle"; this.#split.tabIndex = 0;
    this.#split.setAttribute("role", "separator"); this.#split.setAttribute("aria-orientation", "vertical");
    this.#split.setAttribute("aria-label", strings.split); this.#split.setAttribute("aria-valuemin", "25"); this.#split.setAttribute("aria-valuemax", "75");
    const setRatio = (ratio: number, save = true) => {
      if (document.body.inert) return;
      ratio = Math.max(.25, Math.min(.75, ratio));
      this.#preserveAnchor(() => this.#container.style.setProperty("--text-source-share", String(ratio)));
      this.#split.setAttribute("aria-valuenow", String(Math.round(ratio * 100)));
      this.#split.setAttribute("aria-valuetext", `${Math.round(ratio * 100)}%`);
      if (save) { state.txt.columnRatio = ratio; actions.save(); }
    };
    let dragId: number | undefined;
    this.#finishResize = () => {
      if (dragId !== undefined && this.#split.hasPointerCapture(dragId)) this.#split.releasePointerCapture(dragId);
      dragId = undefined; this.#container.classList.toggle("textResizing", false);
    };
    const drag = (event: PointerEvent) => {
      if (dragId !== event.pointerId || document.body.inert) return;
      const rect = this.#content.getBoundingClientRect(), style = getComputedStyle(this.#content);
      const left = parseFloat(style.paddingLeft), right = parseFloat(style.paddingRight), width = rect.width - left - right - 28;
      if (width > 0) setRatio((event.clientX - rect.left - left - 14) / width);
    };
    this.#split.addEventListener("pointerdown", event => {
      if (event.button !== 0 || !event.isPrimary || document.body.inert) return;
      event.preventDefault(); dragId = event.pointerId; this.#split.setPointerCapture(event.pointerId);
      this.#container.classList.toggle("textResizing", true); drag(event);
    });
    this.#split.addEventListener("pointermove", drag);
    this.#split.addEventListener("pointerup", this.#finishResize);
    this.#split.addEventListener("pointercancel", this.#finishResize);
    this.#split.addEventListener("lostpointercapture", () => { dragId = undefined; this.#container.classList.toggle("textResizing", false); });
    this.#split.addEventListener("keydown", event => {
      const ratio = state.txt.columnRatio ?? .5;
      if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
      event.preventDefault(); event.stopPropagation();
      setRatio(event.key === "Home" ? .25 : event.key === "End" ? .75 : ratio + (event.key === "ArrowLeft" ? -.05 : .05));
    });
    area.append(this.#content, this.#split);
    this.#container.append(this.#toolbar, this.#showControls, this.#status, area); root.append(this.#container);
    setRatio(state.txt.columnRatio ?? .5, false);
    this.#stopSwipes = watchTextSwipes(this.#content, direction => actions.go(state.page + direction));
    this.#content.addEventListener("scroll", actions.save, { passive: true });
    this.#key = event => {
      const target = event.target as HTMLElement | null;
      if (document.body.inert || event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey ||
        target?.closest("input, select, textarea, button, dialog, [contenteditable], [role=separator]")) return;
      if (event.key === "Escape" && this.#fullscreen) { this.#setControlsVisible(false); return; }
      if (event.key === "ArrowLeft" || event.key === "ArrowRight") { event.preventDefault(); actions.go(state.page + (event.key === "ArrowLeft" ? -1 : 1)); }
    };
    window.addEventListener("keydown", this.#key);
  }
  addChapters(chapters: { title: string; page: number }[], more: boolean): void {
    this.#navigation.addChapters(chapters, more);
  }
  get scrollTop(): number { return this.#content.scrollTop; }
  set scrollTop(value: number) { this.#content.scrollTop = value; }
  status(text: string): void { this.#status.textContent = text; }
  fullscreen(enabled: boolean, controlsLabel?: string): void {
    if (this.#fullscreen !== enabled) {
      this.#fullscreen = enabled; this.#setControlsVisible(!enabled);
    }
    this.#hideControls.hidden = !enabled;
    this.#showControls.textContent = controlsLabel || this.strings.controls;
  }
  #setControlsVisible(visible: boolean): void {
    this.#toolbar.hidden = !visible && this.#fullscreen;
    this.#showControls.hidden = visible || !this.#fullscreen;
    if (!visible) this.#navigation.close();
  }
  prepareToFlush(): void { this.#navigation.close(); this.#finishResize(); }
  loading(page: number): void {
    this.#rows.clear(); this.#content.replaceChildren(); this.#content.setAttribute("aria-busy", "true");
    this.#page.value = String(page); this.#previous.disabled = page <= 1; this.#next.disabled = page >= this.count;
    this.#selectChapter(page); this.status(this.strings.loading);
  }
  #selectChapter(page: number): void {
    let chapterPage = 1;
    for (const chapter of this.chapters) if (chapter.page <= page && chapter.page > chapterPage) chapterPage = chapter.page;
    this.#navigation.setPage(page);
    const chapter = this.chapters.findLast(chapter => chapter.page === chapterPage);
    this.#chapters.textContent = chapter?.title || this.strings.chapters;
    this.#chapters.title = chapter?.title || this.strings.chapters;
  }
  render(page: PageSegmentation, client: TranslationClient): void {
    this.#content.replaceChildren(); this.#rows.clear(); this.#content.removeAttribute("aria-busy");
    for (const block of page.blocks) {
      const section = document.createElement("section"); section.className = block.role === "heading" ? "textBlock textHeading" : "textBlock";
      if (block.role === "heading") { section.setAttribute("role", "heading"); section.setAttribute("aria-level", String(block.level ?? 1)); }
      const target = document.createElement("div"); target.className = "bilingualTarget";
      if (this.readingOnly || !block.sentences.length) {
        target.textContent = block.text; section.append(target); this.#content.append(section); continue;
      }
      const whitespace = (text: string) => {
        if (!text) return;
        const span = document.createElement("span"); span.className = "textWhitespace"; span.textContent = text; target.append(span);
      };
      let cursor = 0;
      for (const sentence of block.sentences) {
        whitespace(block.text.slice(cursor, sentence.spanRange.startChar));
        cursor = sentence.spanRange.endChar;
        const element = document.createElement("span"); element.className = "bilingualSentence";
        element.dataset.sentenceId = sentence.id;
        renderSentence(element, sentence, client.state(sentence.id), true);
        if (!this.readingOnly) element.onclick = () => this.actions.retry(sentence);
        target.append(element); this.#rows.set(sentence.id, { element, sentence });
      }
      whitespace(block.text.slice(cursor));
      const source = document.createElement("div"); source.className = "bilingualSource"; source.textContent = block.text;
      section.append(target, source); this.#content.append(section);
    }
  }
  update(id: string, state: Parameters<typeof renderSentence>[2]): void {
    const row = this.#rows.get(id); if (row) this.#preserveAnchor(() => renderSentence(row.element, row.sentence, state, true));
  }
  #preserveAnchor(change: () => void): void {
    const top = this.#content.getBoundingClientRect().top;
    const anchor = [...this.#rows.values()].find(row => row.element.getBoundingClientRect().bottom > top)?.element;
    const offset = anchor ? anchor.getBoundingClientRect().top - top : 0;
    const oldHeight = this.#content.scrollHeight, oldTop = this.scrollTop;
    change();
    if (anchor) this.scrollTop += anchor.getBoundingClientRect().top - top - offset;
    else this.scrollTop = oldHeight ? oldTop * this.#content.scrollHeight / oldHeight : oldTop;
  }
  dispose(): void {
    window.removeEventListener("keydown", this.#key); this.#stopSwipes(); this.#finishResize();
    this.#navigation.dispose(); this.#container.remove();
  }
}
