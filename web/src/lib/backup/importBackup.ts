import type { FirestoreNoteDocument } from '@/lib/mappers/noteCloudMapper';
import { cloudMapToNote } from '@/lib/mappers/noteCloudMapper';
import { createEmptyNote, nextLocalNoteIdAfter, type Note } from '@/types/note';
import { labelFromName } from '@/types/label';
import {
  BACKUP_VERSION,
  MAX_BACKUP_FILE_BYTES,
  MAX_BACKUP_NOTES,
  MAX_NOTE_CHECKLIST_ITEMS,
  MAX_NOTE_CONTENT_CHARS,
  MAX_NOTE_LABELS,
  MAX_NOTE_TITLE_CHARS,
} from '@/lib/backup/constants';

export interface BackupImportResult {
  notesImported: number;
  labelsCreated: number;
}

interface BackupRoot {
  version?: number;
  notes?: unknown[];
  labels?: unknown[];
}

function nextNotePosition(notes: Note[]): number {
  const active = notes.filter((note) => !note.isArchived && !note.isTrashed);
  return active.reduce((max, note) => Math.max(max, note.position), -1) + 1;
}

function parseLabelName(entry: unknown): string {
  if (typeof entry === 'string') return entry.trim().slice(0, MAX_NOTE_TITLE_CHARS);
  if (entry && typeof entry === 'object' && 'name' in entry) {
    const name = (entry as { name?: unknown }).name;
    // Deliberately not String(name): a non-string would stringify to "[object Object]".
    if (typeof name !== 'string') return '';
    return name.trim().slice(0, MAX_NOTE_TITLE_CHARS);
  }
  return '';
}

function collectLabelNames(root: BackupRoot, noteEntries: unknown[]): Set<string> {
  const names = new Set<string>();
  for (const entry of root.labels ?? []) {
    const name = parseLabelName(entry);
    if (name) names.add(name);
  }
  for (const entry of noteEntries) {
    if (!entry || typeof entry !== 'object') continue;
    const labels = (entry as { labels?: unknown[] }).labels ?? [];
    for (const label of labels) {
      const name = parseLabelName(label);
      if (name) names.add(name);
    }
  }
  return names;
}

function noteFromBackupEntry(
  entry: unknown,
  localId: number,
  position: number,
  labelResolver: (name: string) => ReturnType<typeof labelFromName>,
): Note | null {
  if (!entry || typeof entry !== 'object') return null;

  const data = entry as FirestoreNoteDocument & {
    labels?: unknown[];
  };

  const labelNames = (Array.isArray(data.labels) ? data.labels : [])
    .map(parseLabelName)
    .filter((name) => name.length > 0)
    .slice(0, MAX_NOTE_LABELS);

  // cloudMapToNote coerces types; the caps below keep an imported note inside the limits
  // firestore.rules enforces, so it can still sync after import.
  const mapped = cloudMapToNote(String(localId), {
    ...data,
    localId,
    position,
    labels: labelNames.map((name) => ({ name })),
  }, labelResolver);

  return createEmptyNote({
    ...mapped,
    id: String(localId),
    localId,
    position,
    title: mapped.title.slice(0, MAX_NOTE_TITLE_CHARS),
    content: mapped.content.slice(0, MAX_NOTE_CONTENT_CHARS),
    checklist: mapped.checklist
      .slice(0, MAX_NOTE_CHECKLIST_ITEMS)
      .map((item) => ({ ...item, text: item.text.slice(0, MAX_NOTE_TITLE_CHARS) })),
    attachments: [],
  });
}

export function importNotesFromBackup(json: unknown, existingNotes: Note[]): {
  merged: Note[];
  result: BackupImportResult;
} {
  if (!json || typeof json !== 'object') {
    throw new Error('Invalid backup file');
  }

  const root = json as BackupRoot;
  const version = typeof root.version === 'number' ? root.version : 0;
  if (version > BACKUP_VERSION) {
    throw new Error(`Unsupported backup version: ${version}`);
  }

  const noteEntries = Array.isArray(root.notes) ? root.notes : [];
  if (noteEntries.length === 0) {
    return { merged: existingNotes, result: { notesImported: 0, labelsCreated: 0 } };
  }
  if (noteEntries.length > MAX_BACKUP_NOTES) {
    throw new Error(`Backup has too many notes (max ${MAX_BACKUP_NOTES})`);
  }

  const existingLabelKeys = new Set(
    existingNotes.flatMap((note) => note.labels.map((label) => label.name.toLowerCase())),
  );
  const backupLabelNames = collectLabelNames(root, noteEntries);
  let labelsCreated = 0;
  for (const name of backupLabelNames) {
    if (!existingLabelKeys.has(name.toLowerCase())) {
      labelsCreated++;
      existingLabelKeys.add(name.toLowerCase());
    }
  }

  const labelCache = new Map<string, ReturnType<typeof labelFromName>>();
  const resolveLabel = (name: string) => {
    const key = name.toLowerCase();
    const cached = labelCache.get(key);
    if (cached) return cached;
    const label = labelFromName(name);
    labelCache.set(key, label);
    return label;
  };

  const basePosition = nextNotePosition(existingNotes);
  let runningMaxId = existingNotes.reduce((max, note) => Math.max(max, note.localId), 0);
  const imported: Note[] = [];

  for (const entry of noteEntries) {
    const localId = nextLocalNoteIdAfter(runningMaxId);
    runningMaxId = localId;
    const position = basePosition + imported.length;
    const note = noteFromBackupEntry(entry, localId, position, resolveLabel);
    if (!note) continue;
    imported.push(note);
  }

  return {
    merged: [...existingNotes, ...imported],
    result: { notesImported: imported.length, labelsCreated },
  };
}

export async function readBackupFile(file: File): Promise<unknown> {
  if (file.size > MAX_BACKUP_FILE_BYTES) {
    throw new Error('Backup file is too large');
  }
  const text = await file.text();
  try {
    return JSON.parse(text) as unknown;
  } catch {
    throw new Error('Backup file is not valid JSON');
  }
}
