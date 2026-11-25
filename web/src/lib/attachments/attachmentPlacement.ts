import { sha256 } from '@noble/hashes/sha256';
import { bytesToHex } from '@noble/hashes/utils';
import type { Node } from '@tiptap/pm/model';
import type { Mapping } from '@tiptap/pm/transform';
import { serializeNoteDocument } from '@/lib/text/richNoteDocument';
import type { Attachment, AttachmentPlacement } from '@/types/attachment';

export function bodyDigest(body: string): string {
  return bytesToHex(sha256(new TextEncoder().encode(body)));
}

export function parseAttachmentPlacement(value: unknown): AttachmentPlacement | undefined {
  if (!value || typeof value !== 'object') return undefined;
  const { bodySha256, offset, ordinal } = value as Partial<AttachmentPlacement>;
  if (typeof bodySha256 !== 'string' || !/^[a-f0-9]{64}$/.test(bodySha256) ||
      typeof offset !== 'number' || !Number.isSafeInteger(offset) || offset <= 0 ||
      typeof ordinal !== 'number' || !Number.isSafeInteger(ordinal) || ordinal < 0) return undefined;
  return { bodySha256, offset, ordinal };
}

function scalarLength(text: string): number {
  let length = 0;
  for (const _ of text) length++;
  return length;
}

/** Serialized boundaries that will still be available after reopening the note. */
export function placementBodyOffsets(body: string): Set<number> {
  const lines = body.split('\n');
  const offsets = new Set<number>();
  let offset = 0;
  for (let index = 0; index < lines.length; index++) {
    const line = lines[index]!;
    const next = lines[index + 1];
    offset += scalarLength(line) + (next === undefined ? 0 : 1);
    if (offset > 0 && !(line.startsWith('• ') && next?.startsWith('• '))) offsets.add(offset);
  }
  return offsets;
}

export function placementBody(body: string) {
  return { digest: bodyDigest(body), offsets: placementBodyOffsets(body) };
}

export function matchesPlacementBody(placement: AttachmentPlacement, body: ReturnType<typeof placementBody>): boolean {
  return placement.bodySha256 === body.digest && body.offsets.has(placement.offset);
}

export function comparePlacementOrder(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

/** Between-block ProseMirror positions keyed by the Unicode scalar offset in the note body. */
export function placementPositions(doc: Node, body: string): Map<number, number> {
  const offsets = placementBodyOffsets(body);
  const positions = new Map<number, number>();
  const blocks: string[] = [];
  let offset = 0;
  doc.forEach((node, start, index) => {
    const block = serializeNoteDocument(node.toJSON());
    blocks.push(block);
    offset += scalarLength(block) + (index === doc.childCount - 1 ? 0 : 1);
    if (offsets.has(offset) && !positions.has(offset)) positions.set(offset, start + node.nodeSize);
  });
  return blocks.join('\n') === body ? positions : new Map();
}

export function rebaseAttachmentPlacements(
  attachments: Attachment[], before: { doc: Node; body: string }, after: { doc: Node; body: string }, mapping: Mapping,
): Attachment[] {
  if (!attachments.some((attachment) => attachment.placement)) return attachments;
  const beforeDigest = bodyDigest(before.body);
  const beforePositions = placementPositions(before.doc, before.body);
  const afterOffsets = new Map(Array.from(placementPositions(after.doc, after.body), ([offset, position]) => [position, offset]));
  const afterDigest = bodyDigest(after.body);
  return attachments.map((attachment) => {
    const placement = attachment.placement;
    if (placement?.bodySha256 !== beforeDigest) return attachment;
    const position = beforePositions.get(placement.offset);
    if (position === undefined) return attachment;
    const mapped = mapping.mapResult(position, -1);
    const offset = afterOffsets.get(mapped.pos);
    // When the boundary disappears, keep its old digest so undo can recover the placement.
    if (mapped.deleted || offset === undefined) return attachment;
    return { ...attachment, placement: { ...placement, bodySha256: afterDigest, offset } };
  });
}
