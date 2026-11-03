/* SPDX-License-Identifier: Apache-2.0 */

function normalizeErrorCode(reason: string): string {
  return reason.toLowerCase().replace(/[^a-z0-9_.:-]+/g, "_").replace(/^_+|_+$/g, "").slice(0, 160) || "translation_failed";
}

/** Merge trailing warning codes without interpreting any translation text as markup. */
export function appendTranslationError(translation: string, reason: string): string {
  let body = translation.trimEnd();
  const codes: string[] = [];
  const suffix = /\s*\*{5}（([a-zA-Z0-9_.:-]+(?:,\s*[a-zA-Z0-9_.:-]+)*)）$/;
  let match: RegExpExecArray | null;
  while ((match = suffix.exec(body)) !== null) {
    codes.unshift(...match[1].split(",").map(code => normalizeErrorCode(code.trim())));
    body = body.slice(0, match.index);
  }
  codes.push(normalizeErrorCode(reason));
  return `${body} *****（${[...new Set(codes)].join(", ")}）`;
}
