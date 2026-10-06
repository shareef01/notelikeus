import { describe, expect, it } from 'vitest';
import {
  buildAttachmentObjectKey,
  isAttachmentObjectKeyForOwner,
  parseAttachmentPath,
} from './objectKey';

describe('attachment object keys', () => {
  const ownerId = '11111111-2222-4333-8444-555555555555';

  it('builds owner-scoped keys', () => {
    expect(buildAttachmentObjectKey(ownerId, 'note-1', 'att-1')).toBe(
      `owners/${ownerId}/notes/note-1/att-1`,
    );
  });

  it('rejects invalid path segments', () => {
    expect(() => buildAttachmentObjectKey(ownerId, '../note', 'att')).toThrow();
  });

  it('parses worker attachment routes, with the commitment protocol they ask for', () => {
    // R19.2: the version is part of the route. `v1` is the committed-at-upload protocol every
    // deployed client speaks; `v2` asks for the deferred one. A Worker that only knows `v1` -- i.e.
    // one deployed before the deferred protocol -- answers 404 for a `v2` path before it touches
    // storage or the database, which is what makes the rollout order safe.
    expect(parseAttachmentPath('/v1/attachments/note-1/att-1')).toEqual({
      noteId: 'note-1',
      attachmentId: 'att-1',
      deferred: false,
    });
    expect(parseAttachmentPath('/v2/attachments/note-1/att-1')).toEqual({
      noteId: 'note-1',
      attachmentId: 'att-1',
      deferred: true,
    });
    expect(parseAttachmentPath('/v1/attachments')).toBeNull();
    expect(parseAttachmentPath('/v3/attachments/note-1/att-1')).toBeNull();
    expect(parseAttachmentPath('/attachments/note-1/att-1')).toBeNull();
  });

  it('validates object keys for an owner', () => {
    const key = buildAttachmentObjectKey(ownerId, 'note-1', 'att-1');
    expect(isAttachmentObjectKeyForOwner(key, ownerId)).toBe(true);
    expect(isAttachmentObjectKeyForOwner(key, 'other-owner')).toBe(false);
  });

  it('safely handles malformed percent-encoding without crashing', () => {
    expect(parseAttachmentPath('/v1/attachments/%/att')).toBeNull();
    expect(parseAttachmentPath('/v1/attachments/%ZZ/att')).toBeNull();
    expect(parseAttachmentPath('/v1/attachments/%E0%A4%A/att')).toBeNull();
    expect(parseAttachmentPath('/v1/attachments/note/%C3%28')).toBeNull();
  });
});
