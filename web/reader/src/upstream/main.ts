/*
 * SPDX-License-Identifier: Apache-2.0
 * Derived from Chuanyunux/ParaRead 0.6.1; original attribution is retained in
 * third_party/pararead/NOTICE (source commit c9d103481e4e3fa065cee6a52e611b8be20866c6).
 * Modified for ParaRead Android in 2026: Android session/bootstrap, native bridge, reading restoration and bounded rendering.
 */
/*
 * Copyright 2021 Mathematic, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified by chuanyun, 2026: ported from assets/main.mjs to TypeScript and
 * extended with annotation saving and the bilingual reading features.
 */

// The viewer is loaded only for interactive sessions, never for background work.
import { startBackgroundTranslation } from "../background-translation";
import type { Session } from "../bridge";
import { DEFAULT_TARGET_LANGUAGE } from "../languages";
import type { HostToWebview, TranslateRange, WebviewToHost } from "../messages";
import { Connector } from "./connector";
import { HighlightOverlay, scrollToSentence } from "./highlight-overlay";
import { setStrings } from "./i18n";
import { TranslationPanel } from "./panel";
import { installPointerSelect } from "./pointer-select";
import { SaveBridge } from "./save-bridge";
import { ScrollSync } from "./scroll-sync";
import { ColumnOrderSegmenter } from "../reading-order";
import { SelectionController } from "./selection";
import { SentenceStore } from "./sentence-store";
import { TranslationClient } from "./translation-client";
import { ReaderChrome } from "../reader-chrome";
import { startPdfReadingOnly } from "../pdf-reading-only";
import { openPdfDocument, openPdfReadingSession } from "../pdf-reading-session";

function isTranslateRange(value: unknown): value is TranslateRange {
  return value === "page" || value === "nearby" || value === "manual";
}

// The patched viewer calls acquireVsCodeApi() itself, which may only be called
// once per webview. This module runs before the viewer initializes, so acquire
// the API here and hand the same instance to the viewer.
const vscode = acquireVsCodeApi();
window.acquireVsCodeApi = () => vscode;
const post = (message: WebviewToHost) => vscode.postMessage(message);

function loadConfig(): Record<string, unknown> & { url: string } {
  const elem = document.querySelector<HTMLElement>("#pdf-view-config");
  const raw = elem?.dataset["config"];
  if (raw !== undefined) {
    return JSON.parse(raw) as Record<string, unknown> & { url: string };
  }
  throw new Error("Could not load configuration.");
}

const pdfjs = (globalThis as typeof globalThis & { pdfjsLib: PdfjsLibrary }).pdfjsLib;
pdfjs.GlobalWorkerOptions.workerSrc = new URL('./pdfjs/build/pdf.worker.mjs', location.href).href;
const config = loadConfig();
for (const key of ["sandboxBundleSrc", "cMapUrl", "iccUrl", "standardFontDataUrl", "wasmUrl", "imageResourcesPath"]) {
  if (typeof config[key] === "string") config[key] = new URL(config[key] as string, location.href).href;
}
let android: Session;
setStrings(config["strings"]);

async function start() {
  android = await window.paraRead.ready;
  Object.assign(config, android.settings);
  config["debug"] = android.debug === true;
  if (android.settings.backgroundTranslation === true) {
    await startBackgroundTranslation({ pdfjs, session: android, config, bridge: window.paraRead, post });
    return;
  }
  const configureViewer = () => {
    const options = window.PDFViewerApplicationOptions;
    options.set("defaultUrl", "");
    options.set("workerSrc", new URL("./pdfjs/build/pdf.worker.mjs", location.href).href);
    options.set("disablePreferences", true);
    options.set("enableScripting", false);
    options.set("maxCanvasPixels", 4 * 1024 * 1024);
    options.set("maxCanvasDim", 8192);
    options.set("rangeChunkSize", 262144);
    options.set("disableAutoFetch", true);
    options.set("disableStream", true);
    options.set("defaultZoomValue", config["defaultZoomValue"] ?? "auto");
    options.set("sidebarViewOnLoad", config["sidebarViewOnLoad"] ?? 0);
    for (const key of ["sandboxBundleSrc", "cMapUrl", "iccUrl", "standardFontDataUrl", "wasmUrl", "imageResourcesPath"]) {
      options.set(key, config[key]);
    }
  };
  // A late dynamic import can start PDF.js immediately. Configure it at its
  // pre-run event, before preferences or viewer components are initialized.
  document.addEventListener("webviewerloaded", configureViewer, { once: true });
  await import("../../assets/pdf.js/web/viewer.mjs");
  configureViewer();
  const options = window.PDFViewerApplicationOptions;

  // Prevent pdf.js from intercepting Ctrl+P/Cmd+P and triggering the print dialog.
  document.addEventListener("keydown", (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key === "p") {
      e.preventDefault();
      e.stopImmediatePropagation();
    }
  }, true);

  const app = window.PDFViewerApplication;

  await app.initializedPromise;
  options.set("workerSrc", new URL("./pdfjs/build/pdf.worker.mjs", location.href).href);
  options.set("enableScripting", false);
  // Room owns reading history; PDF.js localStorage history must not override its restored page.
  options.set("viewOnLoad", 1);

  let firstRender = true;
  app.eventBus.on("pagerendered", ({pageNumber}: any) => {
    (app.pdfViewer as any)._buffer?.resize(4);
    window.paraRead.post("telemetry.event", {name: firstRender ? "first_render_ms" : "page_render_ms", value: performance.now(), page: pageNumber}); firstRender = false;
  });
  const saveBridge = new SaveBridge(app, post);
  app.eventBus.on("pagechanging", ({pageNumber}: any) => window.paraRead.viewport(pageNumber));
  const readingOnly = android.settings.readingOnly === true;
  if (readingOnly) {
    await startPdfReadingOnly({ app, config, session: android, saveBridge });
    return;
  }
  const store = new SentenceStore(app, new ColumnOrderSegmenter());
  const overlay = new HighlightOverlay(app);
  const translations = new TranslationClient({
    app,
    store,
    post,
    readingOnly,
    range: isTranslateRange(config["translateRange"]) ? config["translateRange"] : "page",
    restoreCached: sentences => window.paraRead.cachedTranslations(sentences),
  });
  const selection = new SelectionController();
  let hoverEnabled = config["selectOnHover"] === true;
  let targetLanguage =
    typeof config["targetLanguage"] === "string"
      ? config["targetLanguage"]
      : DEFAULT_TARGET_LANGUAGE;
  // An annotation editor tool (highlight, ink, free text, comment...) is active.
  const editing = () => app.pdfViewer.annotationEditorMode > 0;
  // Entering an editor clears the selection so it does not cover the annotation work.
  app.eventBus.on("annotationeditormodechanged", ({ mode }: { mode: number }) => {
    if (mode > 0) {
      selection.clear();
    }
  });
  const panel = new TranslationPanel({
    store,
    translations,
    selection,
    targetLanguage,
    onChooseLanguage: () => post({ type: "chooseTargetLanguage" }),
    vscode,
    hoverEnabled: () => hoverEnabled,
    selectionEnabled: () => !editing(),
    onActivate: (sentence) => {
      // Scroll first: bringing a page back into view resets its DOM.
      scrollToSentence(app, sentence);
      overlay.show(sentence);
      void translations.translateBlockOf(sentence);
    },
  });
  installPointerSelect({
    app,
    store,
    selection,
    hoverEnabled: () => hoverEnabled,
    onPin: (sentence) => {
      if (!panel.isOpen) {
        panel.setOpen(true);
      }
      // An untranslated sentence gets its paragraph translated right away.
      void translations.translateBlockOf(sentence);
    },
  });
  selection.onChange((current) => {
    window.paraRead.post("reading.save", {selectedId: current?.sentence.id ?? null, selectedSource: current?.source ?? null});
    if (current === null) {
      overlay.clear();
    } else if (overlay.active?.id !== current.sentence.id) {
      overlay.show(current.sentence);
    }
  });
  const sync = new ScrollSync({ app, store, panel, selection });
  const chrome = new ReaderChrome(app, panel, selection, android.settings.showControlsLabel ?? "Show reader controls");
  chrome.setFullscreen(android.settings.readerFullscreen === true);
  const connector = new Connector(app, panel, selection);
  window.paraRead.evict = () => {
    const current = app.pdfViewer.currentPageNumber;
    store.trim(current, 5); panel.trim(current, 5); translations.trim(current, 5);
  };
  // Translations change the panel layout.
  translations.onChange(() => {
    sync.schedule();
    connector.schedule();
  });

  // Exposed for the development harness (tools/harness.mjs) only.
  if (config["debug"] === true) {
    Object.assign(window, {
      __bilingual: { store, overlay, panel, translations, selection, sync, connector },
    });
  }

  // Segment pages lazily, as the viewer renders their text layers.
  app.eventBus.on("textlayerrendered", ({ pageNumber }: { pageNumber: number }) => {
    void store.ensure(pageNumber).catch(() => {
      // Reloaded while segmenting.
    });
  });

  window.addEventListener("message", async (event: MessageEvent<HostToWebview>) => {
    if (event.origin !== window.origin) {
      return;
    }
    const message = event.data;
    if (message.type === "readerFullscreen") {
      chrome.setFullscreen(message.enabled);
      if (message.showControlsLabel) chrome.setLabel(message.showControlsLabel);
      return;
    }
    if (message.type === "settings") {
      hoverEnabled = message.selectOnHover;
      if (message.targetLanguage !== targetLanguage) {
        // Translations into the previous language no longer apply.
        targetLanguage = message.targetLanguage;
        selection.clear();
        translations.reset();
        panel.setTargetLanguage(targetLanguage, store.readyPages());
      }
    }
    if (translations.handle(message) || (await saveBridge.handle(message))) {
      return;
    }
    if (message.type === "reload") {
      const currentPageNumber = app.pdfViewer.currentPageNumber;
      selection.clear();
      store.reset();
      translations.reset();
      panel.reset();
      overlay.clear();
      await openPdfDocument({ app, config, session: android });
      app.pdfViewer.currentPageNumber = Math.min(currentPageNumber, app.pdfViewer.pagesCount);
      translations.refresh();
    }
  });

  await openPdfReadingSession({
    app, config, session: android,
    restoreSelection: async () => {
      if (android.reading?.selectedId) {
        const pageNumber = Number(android.reading.selectedId.match(/^p(\d+)/)?.[1]);
        if (pageNumber >= 1 && pageNumber <= app.pdfViewer.pagesCount) {
          await store.ensure(pageNumber);
          const sentence = store.get(android.reading.selectedId);
          if (sentence) selection.pin(sentence, android.reading.selectedSource === "panel" ? "panel" : "pdf");
        }
      }
    },
    trim: pageNumber => {
      store.trim(pageNumber, 5);
      panel.trim(pageNumber, 5);
      translations.trim(pageNumber, 5);
    },
  });
  translations.refresh();
}

void start().catch(e => window.paraRead.post("session.error", {message: String(e)}));

window.addEventListener("error", (error) => {
  console.error(error);
});
