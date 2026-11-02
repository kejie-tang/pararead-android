/* SPDX-License-Identifier: Apache-2.0 */
import type { Session } from "./bridge";
import type { HostToWebview, WebviewToHost } from "./messages";
import { ColumnOrderSegmenter } from "./reading-order";
import { SentenceStore } from "./upstream/sentence-store";
import { TranslationClient } from "./upstream/translation-client";

interface BackgroundBridge {
  post(type: string, payload: Record<string, unknown>): unknown;
  makeRangeTransport(pdfjs: PdfjsLibrary, session: Session, onError: (error: unknown) => void): PdfjsRangeTransport;
  cachedTranslations: NonNullable<import("./upstream/translation-client").TranslationClientOptions["restoreCached"]>;
  opened?: boolean;
}

/** Text-only PDF.js session: no viewer, rendering, selection or reading-history writes. */
export async function startBackgroundTranslation({ pdfjs, session, config, bridge, post }: {
  pdfjs: PdfjsLibrary;
  session: Session;
  config: Record<string, unknown>;
  bridge: BackgroundBridge;
  post: (message: WebviewToHost) => void | Promise<unknown>;
}): Promise<() => Promise<void>> {
  let task: PdfjsLoadingTask | undefined;
  let transport: PdfjsRangeTransport | undefined;
  let store: SentenceStore | undefined;
  let client: TranslationClient | undefined;
  let lastCompletedPage = 0;
  let closed = false;
  let fatal = false;
  let stopping: Promise<void> | undefined;
  const pages = new Map<number, PdfjsPageProxy>();
  const onMessage = (event: MessageEvent<HostToWebview>) => {
    if (closed || event.origin !== window.origin) return;
    const message = event.data;
    if (["translateAll", "cancelTranslateAll", "translations", "translationDraft", "translationDone"].includes(message?.type)) {
      client?.handle(message);
    }
  };
  const stop = (): Promise<void> => {
    if (stopping) return stopping;
    closed = true;
    bridge.opened = false;
    window.removeEventListener("message", onMessage);
    window.removeEventListener("pagehide", onPageHide);
    client?.reset();
    store?.reset();
    stopping = (async () => {
      try {
        transport?.abort();
        for (const page of pages.values()) page.cleanup();
      } finally {
        pages.clear();
        await task?.destroy();
      }
    })();
    return stopping;
  };
  const fail = (error: unknown) => {
    if (fatal) return;
    fatal = true;
    bridge.post("session.error", { message: String(error) });
    void stop().catch(cleanupError => console.error("Background PDF cleanup failed", cleanupError));
  };
  const onPageHide = () => { void stop().catch(fail); };
  window.addEventListener("pagehide", onPageHide);
  try {
    transport = bridge.makeRangeTransport(pdfjs, session, fail);
    task = pdfjs.getDocument({
      ...config, url: undefined, length: session.length, range: transport,
      disableAutoFetch: true, disableStream: true, rangeChunkSize: 262144,
      enableScripting: false, isEvalSupported: false,
    });
    const doc = await task.promise;
    if (closed) return stop;
    store = new SentenceStore({ pdfDocument: {
      async getPage(pageNumber) {
        const page = await doc.getPage(pageNumber);
        if (closed) { page.cleanup(); throw new Error("BACKGROUND_SESSION_CLOSED"); }
        pages.set(pageNumber, page);
        return page;
      },
    } }, new ColumnOrderSegmenter());
    client = new TranslationClient({
      app: { pageCount: () => doc.numPages, currentPage: () => lastCompletedPage || 1 },
      store,
      range: "manual",
      restoreCached: sentences => bridge.cachedTranslations(sentences),
      post: message => {
        if (closed) return Promise.reject(new Error("BACKGROUND_SESSION_CLOSED"));
        // Preserve the native job.saved barrier, rather than fire-and-forget progress.
        if (message.type !== "translateAllDone") return post(message);
        return Promise.resolve(post(message)).then(stop, error => { fail(error); throw error; });
      },
      onPageCompleted: pageNumber => {
        lastCompletedPage = pageNumber;
        store!.trim(lastCompletedPage, 0);
        client!.trim(lastCompletedPage, 0);
        for (const [number, page] of pages) if (number <= lastCompletedPage) {
          if (!page.cleanup()) throw new Error(`PAGE_CLEANUP_FAILED_${number}`);
          pages.delete(number);
        }
      },
      onFatal: fail,
    });
    // The host may synchronously enqueue translateAll in response to session.opened.
    window.addEventListener("message", onMessage);
    bridge.opened = true;
    bridge.post("session.opened", { pages: doc.numPages });
    return stop;
  } catch (error) {
    fail(error);
    return stop;
  }
}
