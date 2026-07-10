import { describe, expect, it } from 'vitest';
import { importNotesFromBackup } from '@/lib/backup/importBackup';

describe('importNotesFromBackup', () => {
  it('sanitizes locked notes imported from tampered backups', () => {
    const { merged, result } = importNotesFromBackup(
      {
        version: 3,
        notes: [
          {
            cloudId: 'aaaaaaaa-bbbb-4ccc-dddd-eeeeeeeeeeee',
            isLocked: true,
            title: 'Leaked title',
            content: 'Leaked body',
            timestamp: 1000,
            labels: ['Private'],
            checklist: [{ text: 'Secret task', isChecked: false, position: 0 }],
          },
        ],
      },
      [],
    );

    expect(result.notesImported).toBe(1);
    const imported = merged[0];
    expect(imported?.isLocked).toBe(true);
    expect(imported?.title).toBe('');
    expect(imported?.content).toBe('');
    expect(imported?.labels).toEqual([]);
    expect(imported?.checklist).toEqual([]);
  });
});
