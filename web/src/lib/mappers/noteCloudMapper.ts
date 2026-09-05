import type { ChecklistItem } from '@/types/checklist';
import type { Label } from '@/types/label';
import type { Note } from '@/types/note';
import { labelFromName } from '@/types/label';
import { isCloudSyncEligible } from '@/types/note';

/**
 * Portable note document used by JSON backups and historical cloud maps.
 * Not a Firebase SDK type.
 */
export interface FirestoreNoteDocument {
  localId?: number;
  title?: string;
  content?: string;
  timestamp?: number;
  color?: number;
  isPinned?: boolean;
  isArchived?: boolean;
  isTrashed?: boolean;
  position?: number;
  isLocked?: boolean;
  reminderTimestamp?: number | null;
  /** Epoch millis, or a `{ seconds, nanoseconds }` blob from older backups. */
  serverUpdatedAt?: number | { seconds?: number; nanoseconds?: number } | null;
  labels?: Array<{ name?: string }>;
  checklist?: Array<{
    text?: string;
    isChecked?: boolean;
    position?: number;
  }>;
  attachments?: Array<{
    storagePath?: string;
    type?: string;
    mimeType?: string;
    sizeBytes?: number;
  }>;
}

/**
 * Field readers that never trust the input's type. This mapper parses backup files, which are
 * arbitrary user JSON — a non-string title would otherwise reach the store and throw on the next
 * search or render. Mirrors Android's `Map<String, Any?>.toCloudNote`.
 */
function asString(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback;
}

function asInteger(value: unknown, fallback: number): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) return fallback;
  return Math.trunc(value);
}

function asBoolean(value: unknown, fallback = false): boolean {
  return typeof value === 'boolean' ? value : fallback;
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function serverUpdatedAtToMillis(value: FirestoreNoteDocument['serverUpdatedAt']): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return Math.trunc(value);
  if (value && typeof value === 'object' && typeof value.seconds === 'number') {
    const nanos = typeof value.nanoseconds === 'number' ? value.nanoseconds : 0;
    return value.seconds * 1000 + Math.floor(nanos / 1_000_000);
  }
  return null;
}

function checklistToCloudMap(item: ChecklistItem): Record<string, unknown> {
  return {
    text: item.text,
    isChecked: item.isChecked,
    position: item.position,
  };
}

/** Serializes a note to the Android-compatible cloud/backup map. */
export function noteToCloudMap(note: Note): FirestoreNoteDocument {
  const payload: FirestoreNoteDocument = {
    localId: note.localId,
    title: note.title,
    content: note.content,
    timestamp: note.timestamp,
    color: note.color,
    isPinned: note.isPinned,
    isArchived: note.isArchived,
    isTrashed: note.isTrashed,
    position: note.position,
    isLocked: false,
    reminderTimestamp: note.reminderTimestamp,
    serverUpdatedAt: note.serverUpdatedAt ?? null,
    labels: note.labels.map((label) => ({ name: label.name })),
    checklist: note.checklist.map(checklistToCloudMap),
  };

  if (note.attachments.length > 0) {
    payload.attachments = note.attachments.map((attachment) => ({
      storagePath: attachment.storagePath,
      type: attachment.type,
      mimeType: attachment.mimeType,
      sizeBytes: attachment.sizeBytes,
    }));
  }

  return payload;
}

export function cloudMapToNote(
  documentId: string,
  data: FirestoreNoteDocument,
  resolveLabel: (name: string) => Label = (name) => labelFromName(name),
): Note {
  const parsedId = Number(documentId);
  const hasNumericId = Number.isInteger(parsedId) && parsedId > 0;
  const localId = hasNumericId ? parsedId : asInteger(data.localId, Date.now());

  const id = hasNumericId ? documentId : String(localId);

  const labels: Label[] = [];
  for (const entry of asArray(data.labels)) {
    if (!entry || typeof entry !== 'object') continue;
    const name = asString((entry as { name?: unknown }).name).trim();
    if (name) labels.push(resolveLabel(name));
  }

  const checklist: ChecklistItem[] = asArray(data.checklist).map((raw, index) => {
    const item = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>;
    return {
      id: `chk-${localId}-${index}`,
      text: asString(item.text),
      isChecked: asBoolean(item.isChecked),
      position: asInteger(item.position, index),
    };
  });

  const attachments = asArray(data.attachments).map((raw, index) => {
    const item = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>;
    return {
      id: `att-${localId}-${index}`,
      noteId: localId,
      storagePath: asString(item.storagePath),
      type: asString(item.type, 'image'),
      mimeType: typeof item.mimeType === 'string' ? item.mimeType : undefined,
      sizeBytes: typeof item.sizeBytes === 'number' ? item.sizeBytes : undefined,
    };
  });

  return {
    id,
    localId,
    title: asString(data.title),
    content: asString(data.content),
    timestamp: asInteger(data.timestamp, Date.now()),
    color: asInteger(data.color, 0xff1a1a1a | 0),
    isPinned: asBoolean(data.isPinned),
    isArchived: asBoolean(data.isArchived),
    isTrashed: asBoolean(data.isTrashed),
    position: asInteger(data.position, 0),
    reminderTimestamp:
      typeof data.reminderTimestamp === 'number' && Number.isFinite(data.reminderTimestamp)
        ? Math.trunc(data.reminderTimestamp)
        : null,
    serverUpdatedAt: serverUpdatedAtToMillis(data.serverUpdatedAt),
    labels,
    attachments,
    checklist,
  };
}

export function noteToFirestorePayload(note: Note): FirestoreNoteDocument | null {
  if (!isCloudSyncEligible(note)) return null;
  return noteToCloudMap(note);
}

export function syncMetaMap(noteCount: number, platform: 'web' | 'android' = 'web') {
  return {
    lastSyncAt: Date.now(),
    noteCount,
    platform,
  };
}
