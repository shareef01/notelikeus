import { describe, expect, it } from 'vitest';
import { cloudMapToNote, noteToCloudMap } from '@/lib/mappers/noteCloudMapper';
import type { Note } from '@/types/note';

const sampleNote = (partial: Partial<Note> = {}): Note => ({
  id: '7',
  localId: 7,
  cloudId: 'aaaaaaaa-bbbb-4ccc-dddd-eeeeeeeeeeee',
  title: 'Trip',
  content: 'Pack bags',
  timestamp: 900,
  color: 3,
  isPinned: true,
  isArchived: false,
  isTrashed: false,
  position: 0,
  isLocked: false,
  reminderTimestamp: null,
  labels: [{ id: 'label-travel', name: 'Travel' }],
  attachments: [],
  checklist: [{ id: 'chk-7-0', text: 'Passport', isChecked: true, position: 0 }],
  ...partial,
});

describe('noteCloudMapper', () => {
  it('includes cloudId in cloud map', () => {
    const map = noteToCloudMap(sampleNote());
    expect(map.cloudId).toBe('aaaaaaaa-bbbb-4ccc-dddd-eeeeeeeeeeee');
    expect(map.localId).toBe(7);
  });

  it('roundtrips core fields', () => {
    const original = sampleNote();
    const map = noteToCloudMap(original);
    const restored = cloudMapToNote(original.cloudId, map);
    expect(restored.title).toBe('Trip');
    expect(restored.content).toBe('Pack bags');
    expect(restored.cloudId).toBe(original.cloudId);
    expect(restored.checklist[0]?.text).toBe('Passport');
  });

  it('supports legacy numeric firestore document ids', () => {
    const restored = cloudMapToNote('42', {
      localId: 42,
      title: 'Legacy',
      content: 'Body',
      timestamp: 100,
    });
    expect(restored.localId).toBe(42);
    expect(restored.id).toBe('42');
    expect(restored.title).toBe('Legacy');
  });
});
