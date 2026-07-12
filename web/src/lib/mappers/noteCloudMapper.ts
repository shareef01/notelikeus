import type { ChecklistItem } from '@/types/checklist';
import type { Label } from '@/types/label';
import type { Note } from '@/types/note';
import { labelFromName } from '@/types/label';
import { isCloudSyncEligible } from '@/types/note';
import {
  ensureCloudId,
  noteCloudDocumentId,
  resolveCloudIdFromDocument,
} from '@/lib/cloudIds';

export interface FirestoreNoteDocument {
  cloudId?: string;
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

function checklistToCloudMap(item: ChecklistItem): Record<string, unknown> {
  return stripUndefined({
    text: item.text,
    isChecked: item.isChecked,
    position: item.position,
  }) as Record<string, unknown>;
}

/** Recursively strips `undefined` values that Firestore rejects. */
function stripUndefined(value: unknown): unknown {
  if (value === undefined) return null;
  if (Array.isArray(value)) {
    return value.map(stripUndefined);
  }
  if (value !== null && typeof value === 'object') {
    const result: Record<string, unknown> = {};
    for (const key of Object.keys(value as Record<string, unknown>)) {
      const v = (value as Record<string, unknown>)[key];
      if (v !== undefined) {
        result[key] = stripUndefined(v);
      }
    }
    return result;
  }
  return value;
}

/** Serializes a note to the Android-compatible Firestore map. */
export function noteToCloudMap(note: Note): FirestoreNoteDocument {
  const payload: FirestoreNoteDocument = stripUndefined({
    cloudId: noteCloudDocumentId(note),
    localId: note.localId,
    title: note.title,
    content: note.content,
    timestamp: note.timestamp,
    color: note.color,
    isPinned: note.isPinned,
    isArchived: note.isArchived,
    isTrashed: note.isTrashed,
    position: note.position,
    isLocked: note.isLocked,
    reminderTimestamp: note.reminderTimestamp,
    labels: note.labels.map((label) => ({ name: label.name })),
    checklist: note.checklist.map(checklistToCloudMap),
  }) as FirestoreNoteDocument;

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
  const cloudId = resolveCloudIdFromDocument(documentId, data);
  const parsedId = Number(documentId);
  const localId =
    typeof data.localId === 'number'
      ? data.localId
      : Number.isFinite(parsedId) && parsedId > 0
        ? parsedId
        : Date.now();
  const id = String(localId);

  const labels: Label[] = [];
  for (const entry of data.labels ?? []) {
    const name = entry.name?.trim();
    if (name) labels.push(resolveLabel(name));
  }

  const checklist: ChecklistItem[] = (data.checklist ?? []).map((item, index) => ({
    id: `chk-${localId}-${index}`,
    text: item.text ?? '',
    isChecked: item.isChecked ?? false,
    position: typeof item.position === 'number' ? item.position : index,
  }));

  const attachments = (data.attachments ?? []).map((item, index) => ({
    id: `att-${localId}-${index}`,
    noteId: localId,
    storagePath: item.storagePath ?? '',
    type: item.type ?? 'image',
    mimeType: item.mimeType,
    sizeBytes: item.sizeBytes,
  }));

  return {
    id,
    localId,
    cloudId: ensureCloudId(cloudId),
    title: data.title ?? '',
    content: data.content ?? '',
    timestamp: data.timestamp ?? Date.now(),
    color: data.color ?? (0xff1a1a1a | 0),
    isPinned: data.isPinned ?? false,
    isArchived: data.isArchived ?? false,
    isTrashed: data.isTrashed ?? false,
    position: data.position ?? 0,
    isLocked: data.isLocked ?? false,
    reminderTimestamp: data.reminderTimestamp ?? null,
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

export { noteCloudDocumentId };
