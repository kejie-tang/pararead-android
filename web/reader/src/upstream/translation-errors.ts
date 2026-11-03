/* SPDX-License-Identifier: Apache-2.0 */

function normalizeErrorCode(reason: string): string {
  return reason.toLowerCase().replace(/[^a-z0-9_.:-]+/g, "_").replace(/^_+|_+$/g, "").slice(0, 160) || "translation_failed";
}

/** Normalize legacy cached warnings for display without rewriting caches or regenerating. */
export function normalizeTranslationWarnings(translation: string): string {
  return formatTranslationWarnings(translation, []);
}

/** Merge trailing warning codes as ordinary parentheses, never literal placeholder stars. */
export function appendTranslationError(translation: string, reason: string): string {
  return formatTranslationWarnings(translation, [normalizeErrorCode(reason)]);
}

function formatTranslationWarnings(translation: string, additional: string[]): string {
  let body = translation.trimEnd();
  const codes: string[] = [];
  const suffix = /\s*(?:\*{5})?（([a-zA-Z0-9_.:-]{1,160}(?:,\s*[a-zA-Z0-9_.:-]{1,160})*)）$/;
  let match: RegExpExecArray | null;
  while ((match = suffix.exec(body)) !== null) {
    codes.unshift(...match[1].split(",").map(code => normalizeErrorCode(code.trim())));
    body = body.slice(0, match.index);
  }
  codes.push(...additional);
  if (!codes.length) return translation;
  const visible = [...new Set(codes)].filter(code => code !== "protected_token_mismatch" && code !== "translation_language_or_source_copy");
  return body + (visible.length ? `（${visible.join(", ")}）` : "");
}
