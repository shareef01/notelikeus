import type { Note } from '@/types/note';
import { BACKUP_VERSION } from '@/lib/backup/constants';
import { collectUniqueLabels } from '@/types/label';

/** Plain backup DTO — mirrors Android NoteBackupExporter, no Firestore write sentinels. */
function noteToBackupMap(note: Note): Record<string, unknown> {
  const payload: Record<string, unknown> = {
    id: note.localId,
    title: note.title,
    content: note.content,
    timestamp: note.timestamp,
    color: note.color,
    isPinned: note.isPinned,
    isArchived: note.isArchived,
    isTrashed: note.isTrashed,
    position: note.position,
    labels: note.labels.map((label) => label.name),
    checklist: note.checklist.map((item) => ({
      text: item.text,
      isChecked: item.isChecked,
      position: item.position,
    })),
  };
  if (note.reminderTimestamp != null) {
    payload.reminderTimestamp = note.reminderTimestamp;
  }
  if (note.serverUpdatedAt != null) {
    payload.serverUpdatedAt = note.serverUpdatedAt;
  }
  return payload;
}

export function exportNotesBackup(notes: Note[]): void {
  const labels = collectUniqueLabels(notes);
  const payload = {
    version: BACKUP_VERSION,
    exportedAt: Date.now(),
    app: 'Notelikeus',
    appVersion: '1.0.0 (web)',
    labels: labels.map((label) => ({ id: label.id, name: label.name })),
    notes: notes.map(noteToBackupMap),
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
