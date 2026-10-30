/*
 * SPDX-License-Identifier: Apache-2.0
 * Derived from Chuanyunux/ParaRead 0.6.1; original attribution is retained in
 * third_party/pararead/NOTICE (source commit c9d103481e4e3fa065cee6a52e611b8be20866c6).
 * Modified for ParaRead Android in 2026: Priority requests, streamed drafts and durable whole-document job recovery.
 */
/**
 * Webview side of translation: tracks the state of every sentence, requests
 * translations from the extension host according to the translate range, and
 * drives whole-document jobs.
 */

import type { HostToWebview, TranslateRange, WebviewToHost } from "../messages";
import type { Block, PageSegmentation, Sentence } from "./segmenter/types";
import type { SentenceStore } from "./sentence-store";

export type TranslationState =
  | { status: "none" }
  | { status: "pending"; draft?: string }
  | { status: "done"; translation: string }
  | { status: "error"; message: string };

const NONE: TranslationState = { status: "none" };

export interface TranslationClientOptions {
  app: PdfjsApplication;
  store: SentenceStore;
  post: (message: WebviewToHost) => void | Promise<unknown>;
  range: TranslateRange;
  restoreCached?: (sentences: readonly Sentence[]) => Promise<readonly { id: string; translation: string }[]>;
}

export class TranslationClient {
  readonly #app: PdfjsApplication;
  readonly #store: SentenceStore;
  readonly #post: (message: WebviewToHost) => void | Promise<unknown>;
  readonly #states = new Map<string, TranslationState>();
  readonly #requests = new Map<number, { done: () => void; ids: string[]; settled: Promise<void>; epoch: number; priority: number }>();
  readonly #owners = new Map<string, number>();
  readonly #listeners = new Set<(id: string, state: TranslationState) => void>();
  readonly #cancelledJobs = new Set<number>();
  readonly #restoreCached: TranslationClientOptions["restoreCached"];
  readonly #restores = new Map<number, Promise<void>>();
  #range: TranslateRange;
  #nextRequestId = 1;
  #viewportEpoch = 0;
  #runningJobId: number | null = null;
  #jobPage: number | null = null;
  /** Bumped on document reload so that stale work stops. */
  #generation = 0;

  constructor({ app, store, post, range, restoreCached }: TranslationClientOptions) {
    this.#app = app;
    this.#store = store;
    this.#post = post;
    this.#range = range;
    this.#restoreCached = restoreCached;

    // Restore saved results even in manual mode, without requesting new translations.
    store.onPageReady((page) => { void this.#preparePage(page); });
    app.eventBus.on("pagechanging", ({ pageNumber }: { pageNumber: number }) => {
      this.#viewportEpoch++;
      this.#applyRange(pageNumber);
    });
  }

  state(id: string): TranslationState {
    return this.#states.get(id) ?? NONE;
  }

  onChange(listener: (id: string, state: TranslationState) => void): void {
    this.#listeners.add(listener);
  }

  trim(current: number, radius: number): void {
    for (const page of this.#restores.keys()) {
      if (page !== this.#jobPage && Math.abs(page - current) > radius) this.#restores.delete(page);
    }
    for (const id of this.#states.keys()) {
      const page = Number(id.match(/^p(\d+)/)?.[1]);
      if (page !== this.#jobPage && this.state(id).status !== "pending" && Math.abs(page - current) > radius) {
        this.#states.delete(id); this.#owners.delete(id);
      }
    }
  }

  reset(): void {
    this.#generation++;
    this.#states.clear();
    this.#owners.clear();
    this.#restores.clear();
    for (const done of this.#requests.values()) {
      done.done();
    }
    this.#requests.clear();
  }

  /** Handles host messages; returns whether the message was consumed. */
  handle(message: HostToWebview): boolean {
    switch (message.type) {
      case "translationDraft":
        if (this.#requests.has(message.requestId)) for (const {id, translation} of message.items) {
          if (this.#owners.get(id) === message.requestId && this.state(id).status === "pending") this.#set(id, {status: "pending", draft: translation});
        }
        return true;
      case "translations":
        if (this.#requests.has(message.requestId)) {
          for (const { id, translation } of message.items) {
            if (this.#owners.get(id) === message.requestId) this.#set(id, { status: "done", translation });
          }
        }
        return true;
      case "translationDone":
        if (this.#requests.has(message.requestId)) {
          for (const { id, message: text } of message.failures) {
            if (this.#owners.get(id) === message.requestId) this.#set(id, { status: "error", message: text });
          }
          const request = this.#requests.get(message.requestId);
          for (const id of request?.ids ?? []) if (this.#owners.get(id) === message.requestId) {
            if (this.state(id).status === "pending") this.#set(id, NONE);
            this.#owners.delete(id);
          }
          request?.done();
          this.#requests.delete(message.requestId);
        }
        return true;
      case "translateCurrentPage":
        void this.translatePage(this.#app.pdfViewer.currentPageNumber);
        return true;
      case "translateAll":
        void this.#translateAll(message.jobId, message.startPage ?? 1);
        return true;
      case "cancelTranslateAll":
        if (this.#runningJobId !== null) this.#cancelledJobs.add(this.#runningJobId);
        return true;
      case "settings":
        this.#range = message.translateRange;
        this.#applyRange(this.#app.pdfViewer.currentPageNumber);
        return true;
      case "retryFailed":
        // Clear stale errors (e.g. "API key not set"), then translate the range again.
        for (const [id, state] of this.#states) {
          if (state.status === "error") {
            this.#set(id, NONE);
          }
        }
        this.#applyRange(this.#app.pdfViewer.currentPageNumber);
        return true;
      default:
        return false;
    }
  }

  /** Applies the translate range to the current page, e.g. after a document loads. */
  refresh(): void {
    this.#applyRange(this.#app.pdfViewer.currentPageNumber);
  }

  /** Translates the paragraph containing `sentence` (explicit user action). */
  async translateBlockOf(sentence: Sentence): Promise<void> {
    const generation = this.#generation;
    const page = await this.#store.ensure(sentence.page);
    if (generation !== this.#generation) return;
    if (this.#restoreCached) await this.#restorePage(page);
    if (generation !== this.#generation) return;
    const block = page.blocks.find((b) => b.id === sentence.blockId);
    if (block !== undefined) {
      await this.#request([sentence, ...block.sentences.filter(s => s.id !== sentence.id)], { retryErrors: true, priority: 0 });
    }
  }

  async translatePage(pageNumber: number): Promise<void> {
    const generation = this.#generation;
    const page = await this.#store.ensure(pageNumber);
    if (generation !== this.#generation) return;
    if (this.#restoreCached) await this.#restorePage(page);
    if (generation !== this.#generation) return;
    await this.#request(textSentences(page), { retryErrors: true, priority: 0 });
  }

  #pagesInRange(current: number): number[] {
    const count = this.#app.pdfViewer.pagesCount;
    const pages =
      this.#range === "page"
        ? [current]
        : this.#range === "nearby"
          ? [current, current + 1, current - 1]
          : [];
    return pages.filter((n) => n >= 1 && n <= count);
  }

  #restorePage(page: PageSegmentation): Promise<void> {
    const existing = this.#restores.get(page.page);
    if (existing) return existing;
    const generation = this.#generation;
    const sentences = textSentences(page);
    const ids = new Set(sentences.map(s => s.id));
    const pending = Promise.resolve().then(() => this.#restoreCached?.(sentences) ?? []).then(items => {
      if (generation !== this.#generation || this.#restores.get(page.page) !== pending) return;
      for (const { id, translation } of items) {
        if (ids.has(id) && this.state(id).status === "none") this.#set(id, { status: "done", translation });
      }
    }).catch(() => {
      // A failed lookup must not block explicit translation or poison future restores.
      if (this.#restores.get(page.page) === pending) this.#restores.delete(page.page);
    });
    this.#restores.set(page.page, pending);
    return pending;
  }

  async #preparePage(page: PageSegmentation): Promise<void> {
    const generation = this.#generation;
    if (this.#restoreCached) await this.#restorePage(page);
    if (generation !== this.#generation) return;
    const current = this.#app.pdfViewer.currentPageNumber;
    if (this.#pagesInRange(current).includes(page.page)) {
      await this.#request(textSentences(page), { retryErrors: false, priority: page.page === current ? 1 : 2 });
    }
  }

  #applyRange(current: number): void {
    const generation = this.#generation;
    // Manual mode still loads the current page's persisted results.
    const pages = this.#restoreCached ? new Set([current, ...this.#pagesInRange(current)]) : this.#pagesInRange(current);
    for (const n of pages) {
      if (n < 1 || n > this.#app.pdfViewer.pagesCount) continue;
      void this.#store
        .ensure(n)
        .then((page) => generation === this.#generation ? this.#preparePage(page) : undefined)
        .catch(() => {
          // Reloaded while segmenting.
        });
    }
  }

  async #translateAll(jobId: number, startPage: number): Promise<void> {
    if (this.#runningJobId !== null) return;
    this.#runningJobId = jobId;
    const generation = this.#generation;
    const total = this.#app.pdfViewer.pagesCount;
    let status: "COMPLETED" | "CANCELLED" | "ERROR" = "COMPLETED";
    const cancelled = () => this.#cancelledJobs.has(jobId) || generation !== this.#generation;
    try {
      if (!Number.isInteger(startPage) || startPage < 1 || startPage > total + 1) throw new Error("INVALID_JOB_CURSOR");
      for (let n = startPage; n <= total; n++) {
        if (cancelled()) break;
        this.#jobPage = n;
        const page = await this.#store.ensure(n);
        if (cancelled()) break;
        if (this.#restoreCached) await this.#restorePage(page);
        if (cancelled()) break;
        const sentences = textSentences(page);
        // A visible-page request may already own these sentences. Do not advance past it.
        await this.#waitPending(sentences);
        if (cancelled()) break;
        await this.#request(sentences, { retryErrors: true, jobId });
        await this.#waitPending(sentences);
        if (cancelled()) break;
        const failed = sentences.some(s => this.state(s.id).status !== "done");
        // The native host acknowledges the durable cursor before the next page is extracted.
        await this.#post({ type: "translateAllProgress", jobId, done: n, total, failed });
        window.paraRead.evict?.();
      }
      if (cancelled()) status = "CANCELLED";
    } catch {
      status = cancelled() ? "CANCELLED" : "ERROR";
    } finally {
      this.#runningJobId = null; this.#jobPage = null;
      try { await this.#post({ type: "translateAllDone", jobId, total, status }); }
      catch { /* The last durable cursor is retained if the host has gone away. */ }
      finally { this.#cancelledJobs.delete(jobId); }
    }
  }

  async #waitPending(sentences: readonly Sentence[]): Promise<void> {
    while (true) {
      const pending = new Set(sentences.map(s => this.#requests.get(this.#owners.get(s.id) ?? -1)?.settled).filter(p => p !== undefined));
      if (!pending.size) return;
      await Promise.all(pending);
    }
  }

  /** Requests sentences that are not translated or pending yet. */
  #request(
    sentences: readonly Sentence[],
    { retryErrors, jobId, priority = 1 }: { retryErrors: boolean; jobId?: number; priority?: number },
  ): Promise<void> {
    const todo = sentences.filter((s) => {
      const { status } = this.state(s.id);
      const owner = this.#requests.get(this.#owners.get(s.id) ?? -1);
      // Native cancels speculative work when the viewport changes. Its terminal
      // reply is asynchronous: reclaim old pending rows now, rather than letting
      // them suppress this page's request until the user clicks a sentence.
      const reclaimAuto = jobId === undefined && (priority === 1 || priority === 2) && owner !== undefined &&
        (owner.priority === 1 || owner.priority === 2) &&
        (owner.epoch !== this.#viewportEpoch || (priority === 1 && owner.priority === 2));
      return status === "none" || (retryErrors && status === "error") ||
        (status === "pending" && jobId === undefined && (priority === 0 || reclaimAuto));
    });
    if (todo.length === 0) {
      return Promise.resolve();
    }
    const requestId = this.#nextRequestId++;
    for (const s of todo) {
      this.#owners.set(s.id, requestId);
      this.#set(s.id, { status: "pending" });
    }
    let resolve!: () => void;
    const settled = new Promise<void>(r => { resolve = r; });
    this.#requests.set(requestId, {done: resolve, ids: todo.map(s => s.id), settled,
      epoch: this.#viewportEpoch, priority: jobId === undefined ? priority : 3});
    try {
      this.#post({
        type: "translate",
        priority,
        requestId,
        sentences: todo.map(({ id, text, blockId }) => ({ id, text, group: blockId })),
        ...(jobId === undefined ? {} : { jobId }),
      });
    } catch {
      this.handle({type: "translationDone", requestId, failures: todo.map(s => ({id: s.id, message: "REQUEST_FAILED"}))});
    }
    return settled;
  }

  #set(id: string, state: TranslationState): void {
    this.#states.set(id, state);
    for (const listener of this.#listeners) {
      listener(id, state);
    }
  }
}

function textSentences(page: PageSegmentation): Sentence[] {
  return page.blocks.filter((b: Block) => b.kind === "text").flatMap((b) => b.sentences);
}
