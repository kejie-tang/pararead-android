/* SPDX-License-Identifier: Apache-2.0 */
import { findSentenceEnds } from "./upstream/segmenter/protect";
import type { Block, PageSegmentation, Sentence } from "./upstream/segmenter/types";

export interface TextBridge {
  request(type: string, payload: Record<string, unknown>): Promise<{ type: string; payload: any }>;
  pageText(page: PageSegmentation): Promise<void>;
}

function chapterHeading(text: string): boolean {
  const title = text.trim();
  return title.length <= 120 && !/[\r\n]/u.test(title) &&
    /^(?:第[^\s]{1,30}[章节回卷部篇].*|(?:序章|楔子|序言|前言|尾声|后记|终章)(?:\s.*)?|(?:chapter|part|book)\s+(?:\d+|[ivxlcdm]+)(?:\b.*)|(?:prologue|epilogue|preface|introduction))$/iu.test(title);
}

/** Preserve native newlines verbatim; blank lines belong to their preceding paragraph. */
function paragraphs(text: string): string[] {
  const result: string[] = [];
  let leading = "";
  for (const match of text.matchAll(/[^\r\n]*(?:\r\n|[\r\n])|[^\r\n]+$/gu)) {
    const line = match[0];
    if (!line.trim()) {
      if (result.length) result[result.length - 1] += line;
      else leading += line;
    } else { result.push(leading + line); leading = ""; }
  }
  if (leading) result.push(leading);
  if (result.length <= 128) return result;
  // Only densely line-broken pages merge adjacent tiny paragraphs. Keep a heading separate.
  const grouped: string[] = [];
  if (chapterHeading(result[0])) grouped.push(result.shift()!);
  const budget = 128 - grouped.length;
  const minimum = Math.ceil(result.reduce((sum, part) => sum + part.length, 0) / budget);
  let pending = "";
  for (const part of result) {
    if (pending.length >= minimum && grouped.length < 127) { grouped.push(pending); pending = ""; }
    pending += part;
  }
  if (pending) grouped.push(pending);
  return grouped;
}

/** Native pages and per-block UTF-16 offsets are independent of font size and layout. */
export function segmentTextPage(page: number, text: string): PageSegmentation {
  const parts = paragraphs(text);
  const candidates = parts.map(part => findSentenceEnds(part.trim()));
  const dense = candidates.reduce((sum, ends) => sum + ends.length, 0) > 128;
  const blocks: Block[] = parts.map((part, index) => {
    const blockId = `p${page}-b${index}`;
    const sentences: Sentence[] = [];
    const content = part.trim();
    const offset = part.length - part.trimStart().length;
    let start = 0;
    for (const boundary of candidates[index]) {
      // Bound the whole page's metadata, including dense punctuation across many paragraphs.
      if (dense && boundary < content.length && boundary - start < 32) continue;
      while (start < boundary) {
        let end = Math.min(boundary, start + 2000);
        if (end < content.length && /[\uD800-\uDBFF]/u.test(content[end - 1])) end--;
        const sentence = content.slice(start, end);
        if (sentence.trim()) sentences.push({ id: `${blockId}-s${sentences.length}`, page, blockId, text: sentence,
          spanRange: { startSpan: index, startChar: offset + start, endSpan: index, endChar: offset + end }, rects: [] });
        start = end;
      }
    }
    const heading = index === 0 && chapterHeading(part);
    return { id: blockId, page, kind: "text", role: heading ? "heading" : "paragraph", ...(heading ? { level: 1 } : {}), text: part, sentences, lines: [] };
  });
  return { page, blocks };
}

export class TextSentenceStore {
  readonly #pages = new Map<number, Promise<PageSegmentation>>();
  readonly #ready = new Map<number, PageSegmentation>();
  readonly #byId = new Map<string, Sentence>();
  readonly #listeners = new Set<(page: PageSegmentation) => void>();
  #generation = 0;
  readonly pageCount: number;
  readonly bridge: TextBridge;
  readonly onError?: (error: unknown) => void;
  constructor(pageCount: number, bridge: TextBridge, onError?: (error: unknown) => void) {
    this.pageCount = pageCount; this.bridge = bridge; this.onError = onError;
  }
  onPageReady(listener: (page: PageSegmentation) => void): void { this.#listeners.add(listener); }
  cached(page: number): PageSegmentation | undefined { return this.#ready.get(page); }
  get(id: string): Sentence | undefined { return this.#byId.get(id); }
  readyPages(): PageSegmentation[] { return [...this.#ready.values()].sort((a, b) => a.page - b.page); }
  reset(): void { this.#generation++; this.#pages.clear(); this.#ready.clear(); this.#byId.clear(); }
  /** Keep the active whole-job page pinned until its native job.saved checkpoint. */
  trim(current: number, radius: number, keepPage?: number): void {
    for (const [page, value] of this.#ready) if (page !== keepPage && Math.abs(page - current) > radius) {
      this.#ready.delete(page); this.#pages.delete(page);
      for (const block of value.blocks) for (const sentence of block.sentences) this.#byId.delete(sentence.id);
    }
  }
  ensure(page: number): Promise<PageSegmentation> {
    if (!Number.isInteger(page) || page < 1 || page > this.pageCount) return Promise.reject(new Error("INVALID_TEXT_PAGE"));
    let pending = this.#pages.get(page);
    if (!pending) {
      pending = this.#load(page);
      this.#pages.set(page, pending);
      const original = pending;
      void pending.catch(error => {
        if (this.#pages.get(page) === original) { this.#pages.delete(page); this.onError?.(error); }
      });
    }
    return pending;
  }
  async #load(page: number): Promise<PageSegmentation> {
    const generation = this.#generation;
    const response = await this.bridge.request("text.page", { page });
    if (response.type === "protocol.error") throw new Error(response.payload.code ?? "TEXT_PAGE_FAILED");
    const payload = response.payload;
    if (payload.page !== page || typeof payload.text !== "string" || payload.text.length > 4000) throw new Error("INVALID_TEXT_PAGE_PAYLOAD");
    if (generation !== this.#generation) throw new Error("TEXT_SESSION_RESET");
    const result = segmentTextPage(page, payload.text);
    await this.bridge.pageText(result);
    if (generation !== this.#generation) throw new Error("TEXT_SESSION_RESET");
    this.#ready.set(page, result);
    for (const block of result.blocks) for (const sentence of block.sentences) this.#byId.set(sentence.id, sentence);
    for (const listener of this.#listeners) listener(result);
    return result;
  }
}
