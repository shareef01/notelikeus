import type { Note } from '@/types/note';
import { noteToCloudMap } from '@/lib/mappers/noteCloudMapper';
import { BACKUP_VERSION } from '@/lib/backup/constants';
import { labelFromName } from '@/types/label';

function uniqueLabels(notes: Note[]) {
  const map = new Map<string, ReturnType<typeof labelFromName>>();
  for (const note of notes) {
    for (const label of note.labels) {
      map.set(label.name.toLowerCase(), label);
    }
  }
  return Array.from(map.values()).sort((a, b) => a.name.localeCompare(b.name));
}

export function exportNotesBackup(notes: Note[]): void {
  const labels = uniqueLabels(notes);
  const payload = {
    version: BACKUP_VERSION,
    exportedAt: Date.now(),
    app: 'Notelikeus',
    appVersion: '1.0.0 (web)',
    labels: labels.map((label) => ({ id: label.id, name: label.name })),
    notes: notes.map((note) => {
      const cloud = noteToCloudMap(note);
      return {
        ...cloud,
        labels: note.labels.map((label) => label.name),
      };
    }),
  };
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  const date = new Date().toISOString().slice(0, 10);
  anchor.href = url;
  anchor.download = `notelikeus_backup_${date}.json`;
  anchor.click();
  URL.revokeObjectURL(url);
}
