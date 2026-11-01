import type { Note } from '@/types/note';
import {
  BACKUP_VERSION,
  MAX_BACKUP_FILE_BYTES,
  MAX_BACKUP_LABELS,
  MAX_BACKUP_NOTES,
  MAX_NOTE_CHECKLIST_ITEMS,
  MAX_NOTE_CONTENT_CHARS,
  MAX_NOTE_LABELS,
  MAX_NOTE_TITLE_CHARS,
} from '@/lib/backup/constants';
import { version as appVersion } from '../../../package.json';
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
 * Validates that notes and labels do not exceed import limits.
 * Guarantees that exported data will not suffer silent truncation or rejection upon import.
 */
export function validateNotesForExport(notes: Note[]): void {
  if (notes.length > MAX_BACKUP_NOTES) {
    throw new Error(
      `Cannot export backup: library has ${notes.length} notes (maximum supported is ${MAX_BACKUP_NOTES})`,
    );
  }
  const labels = collectUniqueLabels(notes);
  if (labels.length > MAX_BACKUP_LABELS) {
    throw new Error(
      `Cannot export backup: library has ${labels.length} labels (maximum supported is ${MAX_BACKUP_LABELS})`,
    );
  }

  for (const note of notes) {
    if (note.title.length > MAX_NOTE_TITLE_CHARS) {
      throw new Error(
        `Cannot export backup: note "${note.id}" title exceeds maximum supported length (${note.title.length} chars > ${MAX_NOTE_TITLE_CHARS})`,
      );
    }
    if (note.content.length > MAX_NOTE_CONTENT_CHARS) {
      throw new Error(
        `Cannot export backup: note "${note.id}" content exceeds maximum supported length (${note.content.length} chars > ${MAX_NOTE_CONTENT_CHARS})`,
      );
    }
    if (note.labels.length > MAX_NOTE_LABELS) {
      throw new Error(
        `Cannot export backup: note "${note.id}" has too many labels (${note.labels.length} > ${MAX_NOTE_LABELS})`,
      );
    }
    for (const label of note.labels) {
      if (label.name.length > MAX_NOTE_TITLE_CHARS) {
        throw new Error(
          `Cannot export backup: label "${label.name}" in note "${note.id}" exceeds maximum supported length (${label.name.length} chars > ${MAX_NOTE_TITLE_CHARS})`,
        );
      }
    }
    if (note.checklist.length > MAX_NOTE_CHECKLIST_ITEMS) {
      throw new Error(
        `Cannot export backup: note "${note.id}" has too many checklist items (${note.checklist.length} > ${MAX_NOTE_CHECKLIST_ITEMS})`,
      );
    }
    for (const item of note.checklist) {
      if (item.text.length > MAX_NOTE_TITLE_CHARS) {
        throw new Error(
          `Cannot export backup: checklist item in note "${note.id}" exceeds maximum supported length (${item.text.length} chars > ${MAX_NOTE_TITLE_CHARS})`,
        );
      }
    }
  }
}

/**
 * Serializes the backup payload to formatted JSON and validates exact UTF-8 byte size.
 * Guarantees that exported JSON will not exceed the importer's MAX_BACKUP_FILE_BYTES limit.
 */
export function serializeBackupJson(payload: Record<string, unknown>): string {
  const json = JSON.stringify(payload, null, 2);
  const utf8ByteLength = new TextEncoder().encode(json).length;
  if (utf8ByteLength > MAX_BACKUP_FILE_BYTES) {
    throw new Error(
      `Cannot export backup: serialized file size (${utf8ByteLength} bytes) exceeds maximum supported limit (${MAX_BACKUP_FILE_BYTES} bytes)`,
    );
  }
  return json;
}

/**
 * The v3 backup document, without the DOM download around it.
 */
export function exportBackupPayload(
  notes: Note[],
  exportedAt: number = Date.now(),
  options?: { appVersion?: string },
): Record<string, unknown> {
  validateNotesForExport(notes);
  const labels = collectUniqueLabels(notes);
  return {
    version: BACKUP_VERSION,
    exportedAt,
    app: 'Notelikeus',
    appVersion: options?.appVersion ?? `${appVersion} (web)`,
    labels: labels.map((label) => ({ id: label.id, name: label.name })),
    notes: notes.map(noteToBackupMap),
  };
}

export function exportNotesBackup(notes: Note[]): void {
  const payload = exportBackupPayload(notes);
  const json = serializeBackupJson(payload);
  const blob = new Blob([json], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  const date = new Date().toISOString().slice(0, 10);
  anchor.href = url;
  anchor.download = `notelikeus_backup_${date}.json`;
  anchor.click();
  URL.revokeObjectURL(url);
}
