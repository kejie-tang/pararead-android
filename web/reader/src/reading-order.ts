import { HeuristicSegmenter } from './upstream/segmenter/heuristic';
import type { Block, PageTextInput, PageSegmentation, Segmenter } from './upstream/segmenter/types';

/** Conservative spatial pass over ParaRead blocks; PDF text span indices and rectangles stay untouched. */
export class ColumnOrderSegmenter implements Segmenter {
  private readonly original = new HeuristicSegmenter();
  segmentPage(input: PageTextInput): PageSegmentation {
    const page = this.original.segmentPage(input);
    return { ...page, blocks: orderColumns(page.blocks, input.view) };
  }
}
export function orderColumns(blocks: Block[], view: number[]): Block[] {
  const middle = (view[0] + view[2]) / 2;
  const width = view[2] - view[0];
  const bounds = (b: Block) => ({ left: Math.min(...b.lines.map(r => r.x)), right: Math.max(...b.lines.map(r => r.x + r.w)), top: Math.max(...b.lines.map(r => r.y + r.h)) });
  const side = (b: Block) => { const r = bounds(b); return r.right < middle + width * .02 ? 0 : r.left > middle - width * .02 ? 1 : 2; };
  // Require multiple blocks on each side and a meaningful central gap; tables/figures remain in their band.
  const left = blocks.filter(b => side(b) === 0 && b.kind === 'text');
  const right = blocks.filter(b => side(b) === 1 && b.kind === 'text');
  if (left.length < 2 || right.length < 2 || Math.min(...right.map(b => bounds(b).left)) - Math.max(...left.map(b => bounds(b).right)) < width * .025) return blocks;
  const rows = [...blocks].sort((a, b) => bounds(b).top - bounds(a).top);
  const output: Block[] = []; let band: Block[] = [];
  const flush = () => { output.push(...band.sort((a, b) => side(a) - side(b) || bounds(b).top - bounds(a).top)); band = []; };
  for (const b of rows) { if (side(b) === 2) { flush(); output.push(b); } else band.push(b); }
  flush(); return output;
}
