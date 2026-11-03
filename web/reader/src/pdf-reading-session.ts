/* SPDX-License-Identifier: Apache-2.0
 * PDF position/history logic extracted from upstream/main.ts; see its retained attribution.
 */
import type { Session } from "./bridge";

export interface PdfReadingSessionOptions {
  app: PdfjsApplication;
  config: Record<string, unknown> & { url: string };
  session: Session;
  restoreSelection?: () => Promise<void>;
  trim?: (page: number) => void;
}

/** The same range transport is used for initial loads and annotation-save reloads. */
export async function openPdfDocument({ app, config, session }: PdfReadingSessionOptions): Promise<void> {
  await app.open({ ...config, url: undefined, length: session.length, range: window.paraRead.makeRangeTransport((globalThis as any).pdfjsLib, session), disableAutoFetch: true, disableStream: true, rangeChunkSize: 262144 });
  await app.pdfViewer.pagesPromise;
  await new Promise<void>(resolve => {
    const initialized = () => { if ((app as any).isInitialViewSet) resolve(); else requestAnimationFrame(initialized); };
    initialized();
  });
}

/** Room's PDF position is independent of the optional bilingual selection. */
export async function openPdfReadingSession(options: PdfReadingSessionOptions): Promise<void> {
  const { app, config, session, restoreSelection, trim } = options;
  // PDF.js initializes its view asynchronously. Its initial pass must use Room's bookmark too.
  const savedPage = Math.max(1, Number(session.reading?.page) || 1);
  (app as any).initialBookmark = session.reading?.location || `page=${savedPage}&zoom=${session.reading?.zoom || "auto"}`;
  (app as any).initialRotation = session.reading?.rotation ?? 0;
  await openPdfDocument(options);
  const [, hash] = config.url.split("#");
  if (hash !== undefined && hash !== "") app.pdfLinkService.setHash(decodeURIComponent(hash));
  app.pdfViewer.currentPageNumber = Math.min(session.reading?.page ?? 1, app.pdfViewer.pagesCount);
  if (session.reading?.zoom) (app.pdfViewer as any).currentScaleValue = session.reading.zoom;
  if (session.reading?.rotation) (app.pdfViewer as any).pagesRotation = session.reading.rotation;
  if (session.reading?.location) app.pdfLinkService.setHash(session.reading.location);
  await restoreSelection?.();
  app.eventBus.on("pagechanging", ({pageNumber}: any) => {
    window.paraRead.post("reading.save", {page: pageNumber});
    trim?.(pageNumber);
  });
  let readingTimer: ReturnType<typeof setTimeout>;
  app.eventBus.on("updateviewarea", ({location}: any) => {
    clearTimeout(readingTimer);
    readingTimer = setTimeout(() => window.paraRead.post("reading.save", {page: location.pageNumber, zoom: String((app.pdfViewer as any).currentScaleValue), rotation: location.rotation, location: location.pdfOpenParams?.replace(/^#/, "")}), 250);
  });
  window.paraRead.setReadingStateFlusher(async () => {
    // Let PDF.js settle immediate navigation/zoom before reading its location snapshot.
    clearTimeout(readingTimer);
    await new Promise<void>(resolve => requestAnimationFrame(() => resolve()));
    clearTimeout(readingTimer);
    const viewer = app.pdfViewer as PdfjsViewer & {
      currentScaleValue: string | number;
      pagesRotation: number;
      _location?: { pageNumber?: number; scale?: string | number; rotation?: number; pdfOpenParams?: string };
    };
    const zoom = String(viewer.currentScaleValue);
    // PDF.js stores numeric location scales as percentages rounded to two decimals,
    // while named zooms (auto, page-width, page-fit...) remain strings.
    const numericZoom = Number(zoom);
    const locationScale = Number.isFinite(numericZoom) && numericZoom > 0 ? Math.round(numericZoom * 10000) / 100 : zoom;
    const location = viewer._location;
    const locationMatches = location?.pageNumber === viewer.currentPageNumber &&
      location.rotation === viewer.pagesRotation && location.scale === locationScale;
    return {
      page: viewer.currentPageNumber,
      zoom,
      rotation: viewer.pagesRotation,
      // Explicitly clear an unavailable/stale bookmark: omission would retain it in Room.
      location: locationMatches ? location?.pdfOpenParams?.replace(/^#/, "") || "" : "",
    };
  });
  window.paraRead.post("session.opened", {pages: app.pdfViewer.pagesCount});
  window.paraRead.opened = true;
}
