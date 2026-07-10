import { describe, expect, it } from 'vitest';
import {
  ensureCloudId,
  isValidCloudId,
  newCloudId,
  noteCloudDocumentId,
  resolveCloudIdFromDocument,
} from '@/lib/cloudIds';

describe('cloudIds', () => {
  it('generates valid UUIDs', () => {
    const id = newCloudId();
    expect(isValidCloudId(id)).toBe(true);
  });

  it('preserves existing valid cloud ids', () => {
    const existing = 'aaaaaaaa-bbbb-4ccc-dddd-eeeeeeeeeeee';
    expect(ensureCloudId(existing)).toBe(existing);
  });

  it('resolves cloud id from document data first', () => {
    const cloudId = '11111111-1111-4111-8111-111111111111';
    expect(resolveCloudIdFromDocument('legacy-42', { cloudId })).toBe(cloudId);
  });

  it('falls back to UUID document ids', () => {
    const cloudId = '22222222-2222-4222-8222-222222222222';
    expect(resolveCloudIdFromDocument(cloudId, {})).toBe(cloudId);
  });

  it('uses note cloud id for firestore document path', () => {
    const cloudId = '33333333-3333-4333-8333-333333333333';
    expect(noteCloudDocumentId({ cloudId })).toBe(cloudId);
  });
});
