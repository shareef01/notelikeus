import { serverTimestamp, Timestamp, type FieldValue } from 'firebase/firestore';
import type { ChecklistItem } from '@/types/checklist';
import type { Label } from '@/types/label';
import type { Note } from '@/types/note';
import { labelFromName } from '@/types/label';
import { isCloudSyncEligible } from '@/types/note';

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
  /**
   * Server-assigned, not client-set — Firestore resolves the `serverTimestamp()` sentinel to
   * its own commit time, which is what makes cross-device conflict resolution immune to a
   * device's clock being wrong. Rules additionally enforce this is exactly request.time, so a
   * client cannot forge it. `FieldValue` on write (the sentinel), `Timestamp` on read (resolved).
   */
  serverUpdatedAt?: FieldValue | Timestamp;
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
 * Field readers that never trust the input's type. Cloud documents are type-checked by
 * firestore.rules, but this same mapper parses backup files, which are arbitrary user JSON —
 * a non-string title would otherwise reach the store and throw on the next search or render.
 * Mirrors Android's `Map<String, Any?>.toCloudNote` (`as? String ?: ""`).
 */
function asString(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback;
}

/**
 * Coerces to a whole number, for the fields Firestore stores as `int`.
 *
 * `localId`, `color`, `position` and both timestamps are integers everywhere else in the system:
 * Kotlin types them `Long`/`Int`, the desktop transport writes explicit `integerValue`, and
 * `firestore.rules` type-checks them with `is int`. JavaScript has one number type, so nothing on
 * this side enforced it — the previous helper accepted any finite value, and a note carrying `1.5`
 * in one of those fields imports happily and is then **rejected by the rules on every write**.
 * The note survives locally and silently never syncs, which is the worst shape a failure takes.
 *
 * Backups are user-editable JSON and import routes through `cloudMapToNote`, so this is reachable
 * without a malicious actor — a hand-edited or third-party-generated file is enough.
 *
 * Truncates rather than rejects: the magnitude is the meaningful part and dropping a fractional
 * remainder keeps the note usable, where discarding the field would lose real data. It matches
 * what `serverUpdatedAt` already does a few lines below, flooring sub-millisecond precision so
 * both platforms agree on one number.
 */
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

function checklistToCloudMap(item: ChecklistItem): Record<string, unknown> {
  return {
    text: item.text,
    isChecked: item.isChecked,
    position: item.position,
  };
}

/** Serializes a note to the Android-compatible Firestore map. */
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
    // Note locking is gone, but older clients and the deployed rules still expect the field.
    // Writing a constant keeps this build compatible with both without a deploy ordering rule.
    isLocked: false,
    reminderTimestamp: note.reminderTimestamp,
    serverUpdatedAt: serverTimestamp(),
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
  // Number.isInteger, not isFinite: a document literally named "1.5" would otherwise become a
  // fractional localId, which is the same rules rejection by another route.
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
    // Not `.toMillis()`: it keeps the sub-millisecond fraction (float division), while Android's
    // equivalent conversion floors it (integer division) — same commit, two different numbers
    // otherwise. Flooring on both sides is the coarsest representation both platforms agree on.
    serverUpdatedAt: data.serverUpdatedAt instanceof Timestamp
      ? data.serverUpdatedAt.seconds * 1000 + Math.floor(data.serverUpdatedAt.nanoseconds / 1_000_000)
      : null,
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
