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

/**
 * The v3 backup document, without the DOM download around it.
 *
 * Split out so the serialized shape can be asserted directly — it is a cross-client wire format
 * (`contracts/backup/v3-web-export.json`), and the only other way to see it was to intercept a
 * Blob constructor.
 */
export function exportBackupPayload(
  notes: Note[],
  exportedAt: number = Date.now(),
): Record<string, unknown> {
  const labels = collectUniqueLabels(notes);
  return {
    version: BACKUP_VERSION,
    exportedAt,
    app: 'Notelikeus',
    appVersion: '1.0.0 (web)',
    labels: labels.map((label) => ({ id: label.id, name: label.name })),
    notes: notes.map(noteToBackupMap),
  };
}

export function exportNotesBackup(notes: Note[]): void {
  const payload = exportBackupPayload(notes);
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  const date = new Date().toISOString().slice(0, 10);
  anchor.href = url;
  anchor.download = `notelikeus_backup_${date}.json`;
  anchor.click();
  URL.revokeObjectURL(url);
}
