import {
  looksSealedNote,
  noteAad,
  openNoteBytes,
  sealNoteBytes,
} from '@/lib/crypto/notesBytesCodec';
import { getNotesCryptoKey } from '@/lib/crypto/notesCryptoKey';
import type { ChecklistItem } from '@/types/checklist';
import type { Note } from '@/types/note';

/** Sensitive fields sealed at rest (same set the removed note-lock feature protected). */
interface NoteSecrets {
  title: string;
  content: string;
  checklist: ChecklistItem[];
}

/**
 * On-disk note shape when sealed: metadata stays readable for sync/UI flags; body lives in
 * `sealedBody` as `NLN1` bytes. Legacy rows are plain `Note` objects without `sealedBody`.
 */
export type StoredNotePayload = Note & {
  sealedBody?: ArrayBuffer;
};

function bytesToArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return copy.buffer;
}

function payloadToBytes(value: unknown): Uint8Array | null {
  if (value instanceof ArrayBuffer) return new Uint8Array(value);
  if (ArrayBuffer.isView(value)) {
    return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
  }
  return null;
}

export function hasSealedBody(stored: StoredNotePayload): boolean {
  return 'sealedBody' in stored && stored.sealedBody !== undefined;
}

export function isSealedStoredNote(note: StoredNotePayload): boolean {
  const bytes = payloadToBytes(note.sealedBody);
  return bytes != null && looksSealedNote(bytes);
}

function blankSecrets(note: Note): Note {
  return { ...note, title: '', content: '', checklist: [] };
}

export class NoteDecryptionError extends Error {
  readonly noteId: string;
  readonly reason: 'missing_key' | 'decrypt_failed' | 'invalid_payload';

  constructor(
    noteId: string,
    reason: 'missing_key' | 'decrypt_failed' | 'invalid_payload',
    message?: string,
  ) {
    super(message ?? `Failed to decrypt note ${noteId}: ${reason}`);
    this.name = 'NoteDecryptionError';
    this.noteId = noteId;
    this.reason = reason;
  }
}

export class NoteSealingError extends Error {
  readonly noteId: string;
  readonly reason: 'missing_key' | 'seal_failed';

  constructor(
    noteId: string,
    reason: 'missing_key' | 'seal_failed',
    message?: string,
  ) {
    super(message ?? `Failed to seal note ${noteId}: ${reason}`);
    this.name = 'NoteSealingError';
    this.noteId = noteId;
    this.reason = reason;
  }
}

export async function sealNoteForStorage(
  ownerId: string,
  note: Note,
): Promise<StoredNotePayload> {
  const key = await getNotesCryptoKey();
  if (!key) {
    throw new NoteSealingError(
      note.id,
      'missing_key',
      'Encryption key unavailable; refusing to persist unencrypted note',
    );
  }

  const secrets: NoteSecrets = {
    title: note.title,
    content: note.content,
    checklist: note.checklist,
  };
  const plain = new TextEncoder().encode(JSON.stringify(secrets));
  const sealed = await sealNoteBytes(key, plain, noteAad(ownerId, note.id));
  const { sealedBody: _drop, ...rest } = note as StoredNotePayload;
  return {
    ...blankSecrets(rest),
    sealedBody: bytesToArrayBuffer(sealed),
  };
}

export async function openStoredNote(
  ownerId: string,
  stored: StoredNotePayload,
): Promise<Note> {
  if (!hasSealedBody(stored)) {
    const { sealedBody: _drop, ...note } = stored;
    return note;
  }

  const key = await getNotesCryptoKey();
  if (!key) {
    throw new NoteDecryptionError(
      stored.id,
      'missing_key',
      `Cannot open sealed note ${stored.id}: encryption key is unavailable`,
    );
  }

  const bytes = payloadToBytes(stored.sealedBody);
  if (!bytes || !looksSealedNote(bytes)) {
    throw new NoteDecryptionError(
      stored.id,
      'invalid_payload',
      `Cannot open sealed note ${stored.id}: sealed body payload is corrupted or malformed`,
    );
  }

  let plain: Uint8Array;
  try {
    plain = await openNoteBytes(key, bytes, noteAad(ownerId, stored.id));
  } catch {
    throw new NoteDecryptionError(
      stored.id,
      'decrypt_failed',
      `Cannot open sealed note ${stored.id}: decryption failed`,
    );
  }

  let parsed: Partial<NoteSecrets>;
  try {
    parsed = JSON.parse(new TextDecoder().decode(plain)) as Partial<NoteSecrets>;
  } catch {
    throw new NoteDecryptionError(
      stored.id,
      'invalid_payload',
      `Cannot open sealed note ${stored.id}: decrypted payload was not valid JSON`,
    );
  }

  const { sealedBody: _drop, ...shell } = stored;
  return {
    ...shell,
    title: typeof parsed.title === 'string' ? parsed.title : '',
    content: typeof parsed.content === 'string' ? parsed.content : '',
    checklist: Array.isArray(parsed.checklist) ? parsed.checklist : [],
  };
}

/** Rewrite a legacy plaintext row to sealed form when a key is available. */
export async function maybeMigrateStoredNote(
  ownerId: string,
  stored: StoredNotePayload,
  write: (next: StoredNotePayload) => Promise<void>,
): Promise<void> {
  if (hasSealedBody(stored)) return;
  const key = await getNotesCryptoKey();
  if (!key) return;
  try {
    const sealed = await sealNoteForStorage(ownerId, stored);
    if (!isSealedStoredNote(sealed)) return;
    await write(sealed);
  } catch {
    // Leave plaintext; dual-read still works.
  }
}
