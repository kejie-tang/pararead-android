/* SPDX-License-Identifier: Apache-2.0 */
import type { HostToWebview } from "./messages";
import { ReaderChrome } from "./reader-chrome";
import { openPdfDocument, openPdfReadingSession, type PdfReadingSessionOptions } from "./pdf-reading-session";
import type { SaveBridge } from "./upstream/save-bridge";

/** Immutable for this host session; changing mode requires a native reopen. */
export async function startPdfReadingOnly(options: PdfReadingSessionOptions & { saveBridge: SaveBridge }): Promise<void> {
  const { app, session, saveBridge } = options;
  const chrome = new ReaderChrome(app, null, null, session.settings.showControlsLabel ?? "Show reader controls");
  chrome.setFullscreen(session.settings.readerFullscreen === true);
  window.addEventListener("message", async (event: MessageEvent<HostToWebview>) => {
    if (event.origin !== window.origin) return;
    const message = event.data;
    if (message.type === "readerFullscreen") {
      chrome.setFullscreen(message.enabled);
      if (message.showControlsLabel) chrome.setLabel(message.showControlsLabel);
      return;
    }
    if (await saveBridge.handle(message)) return;
    if (message.type === "reload") {
      const viewer = app.pdfViewer as PdfjsViewer & { currentScaleValue: string; pagesRotation: number };
      const currentPageNumber = viewer.currentPageNumber;
      const zoom = viewer.currentScaleValue;
      const rotation = viewer.pagesRotation;
      await openPdfDocument(options);
      // Rotation/scale recalculate visible pages; restore navigation only after that layout.
      viewer.pagesRotation = rotation;
      viewer.currentScaleValue = zoom;
      viewer.currentPageNumber = Math.min(currentPageNumber, viewer.pagesCount);
    }
  });
  // No SentenceStore, cache restoration, text extraction or translation UI is mounted.
  // In particular, never clear/save selectedId: it belongs to bilingual history.
  await openPdfReadingSession({ app, config: options.config, session });
}
