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

  it('parses worker attachment routes', () => {
    expect(parseAttachmentPath('/v1/attachments/note-1/att-1')).toEqual({
      noteId: 'note-1',
      attachmentId: 'att-1',
    });
    expect(parseAttachmentPath('/v1/attachments')).toBeNull();
  });

  it('validates object keys for an owner', () => {
    const key = buildAttachmentObjectKey(ownerId, 'note-1', 'att-1');
    expect(isAttachmentObjectKeyForOwner(key, ownerId)).toBe(true);
    expect(isAttachmentObjectKeyForOwner(key, 'other-owner')).toBe(false);
  });
});
