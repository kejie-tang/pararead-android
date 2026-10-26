// Narrow, fail-closed transformations of pinned Apache-2.0 PDF.js 6.2.108.
// Source is retained unchanged in .references. Android emits incremental suffixes only.
export function patchWorker(source) {
  function replace(before, after) {
    if (!source.includes(before)) throw new Error(`PDF.js patch drift: ${before.slice(0, 90)}`);
    source = source.replace(before, after);
  }
  replace('class ChunkedStream extends Stream {', `${sparseStore}\nclass ChunkedStream extends Stream {`);
  replace('super(new Uint8Array(length), 0, length, null);', 'super(new Uint8Array(0), 0, length, null);\n    this.bytes = new AndroidSparseBytes(length, chunkSize, this);');
  replace('if (chunk === this._lastSuccessfulEnsureByteChunk) {\n      return;\n    }', '/* Android: loaded chunks may be evicted; always recheck membership. */');
  // No shared mutable file cursor. No allocation proportional to total PDF length.
  replace('if (pos >= this.progressiveDataLength) {\n      this.ensureByte(pos);\n    }\n    return this.bytes[this.pos++];', 'if (pos >= this.progressiveDataLength) {\n      this.ensureByte(pos);\n    }\n    return this.bytes.byte(this.pos++);');
  replace('  getBaseStreams() {\n    return [this];\n  }\n}\nclass ChunkedStreamManager', '  clone() { return this.makeSubStream(this.start, this.length, this.dict?.clone()); }\n  getBaseStreams() {\n    return [this];\n  }\n}\nclass ChunkedStreamManager');
  replace('new Uint8Array(stream.bytes.buffer, stream.start, stream.end - stream.start)', 'stream.getByteRange(stream.start, stream.end)');
  replace('pdfManager.requestLoadedStream(true).then(stream => {\n          handler.send("DataLoaded", {\n            length: stream.bytes.byteLength\n          });\n        }, () => {});', 'handler.send("DataLoaded", { length: pdfManager.pdfDocument.stream.length });');
  replace('const globalPromises = [pdfManager.requestLoadedStream(),', 'const globalPromises = [pdfManager.ensure(pdfManager.pdfDocument.stream, "getByteRange", [pdfManager.pdfDocument.stream.end - 1, pdfManager.pdfDocument.stream.end]).then(() => pdfManager.pdfDocument.stream),');
  // Editor annotations are already saved by saveNewAnnotations on their actual pages.
  // The upstream second pass scans every page for form widgets and OOMs on large books.
  replace('      } else {\n        for (let pageIndex = 0; pageIndex < numPages; pageIndex++) {\n          promises.push(pdfManager.getPage(pageIndex).then(function (page) {',
    '      } else if (annotationStorage && [...annotationStorage.keys()].some(key => !key.startsWith(AnnotationEditorPrefix))) {\n        if (numPages > 512) throw new Error("LARGE_FORM_SAVE_NOT_SUPPORTED: widget edits require a bounded page index");\n        for (let pageIndex = 0; pageIndex < numPages; pageIndex++) {\n          promises.push(pdfManager.getPage(pageIndex).then(function (page) {');
  replace('originalData: stream.bytes,\n        xrefInfo:', 'originalData: stream.bytes,\n        suffixOnly: true,\n        xrefInfo:');
  replace('async function incrementalUpdate({\n  originalData,', 'async function incrementalUpdate({\n  originalData,\n  suffixOnly = false,');
  replace('const totalLength = originalData.length + Math.sumPrecise(buffer.map(str => str.length));\n  const array = new Uint8Array(totalLength);\n  array.set(originalData);\n  let offset = originalData.length;', 'const totalLength = (suffixOnly ? 0 : originalData.length) + Math.sumPrecise(buffer.map(str => str.length));\n  if (totalLength > 16 * 1024 * 1024) throw new Error("ANNOTATION_SUFFIX_TOO_LARGE");\n  const array = new Uint8Array(totalLength);\n  if (!suffixOnly) array.set(originalData);\n  let offset = suffixOnly ? 0 : originalData.length;');
  // Pure XFA / no-op saves must not return a virtual sparse array across structured clone.
  const start = source.indexOf('handler.on("SaveDocument"');
  const end = source.indexOf('handler.on("GetOperatorList"', start);
  source = source.slice(0, start) + source.slice(start, end).replaceAll('return stream.bytes;', 'return new Uint8Array(0);') + source.slice(end);
  replace('handler.on("GetData", async function () {', 'handler.on("GetData", async function () {\n      throw new Error("FULL_DOCUMENT_READ_DISABLED: use native streaming copy");');
  return source;
}
export const sparseStore = `
// ParaRead Android modification: sparse range cache, 64 MiB resident source bytes.
class AndroidSparseBytes {
  constructor(length, chunkSize, owner) {
    this.length = this.byteLength = length; this.chunkSize = chunkSize; this.owner = owner;
    this.chunks = new Map(); this.limit = Math.max(4, Math.floor(64 * 1024 * 1024 / chunkSize));
  }
  byte(pos) {
    const index = Math.floor(pos / this.chunkSize);
    const data = this.chunks.get(index);
    if (!data) throw new MissingDataException(pos, pos + 1);
    this.chunks.delete(index); this.chunks.set(index, data);
    return data[pos % this.chunkSize];
  }
  at(pos) { return this.byte(pos < 0 ? this.length + pos : pos); }
  set(data, begin) {
    if (begin % this.chunkSize) throw new Error("UNALIGNED_SPARSE_WRITE");
    for (let offset = 0; offset < data.length; offset += this.chunkSize) {
      const index = Math.floor((begin + offset) / this.chunkSize);
      this.chunks.delete(index); this.chunks.set(index, data.slice(offset, offset + this.chunkSize));
    }
    while (this.chunks.size > this.limit) {
      const index = this.chunks.keys().next().value;
      this.chunks.delete(index); this.owner._loadedChunks.delete(index); this.owner._lastSuccessfulEnsureByteChunk = -1;
    }
  }
  subarray(begin, end = this.length) {
    if (end - begin > 16 * 1024 * 1024) throw new Error("PDF_OBJECT_EXCEEDS_16MIB_BUDGET");
    const output = new Uint8Array(end - begin);
    for (let pos = begin; pos < end;) {
      const index = Math.floor(pos / this.chunkSize); const data = this.chunks.get(index);
      if (!data) throw new MissingDataException(pos, Math.min(end, (index + 1) * this.chunkSize));
      const length = Math.min(end - pos, this.chunkSize - pos % this.chunkSize);
      output.set(data.subarray(pos % this.chunkSize, pos % this.chunkSize + length), pos - begin);
      this.chunks.delete(index); this.chunks.set(index, data); pos += length;
    }
    return output;
  }
  get buffer() { throw new Error("CONTIGUOUS_PDF_BUFFER_DISABLED"); }
}
`;
