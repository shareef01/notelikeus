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

export function isSealedStoredNote(note: StoredNotePayload): boolean {
  const bytes = payloadToBytes(note.sealedBody);
  return bytes != null && looksSealedNote(bytes);
}

function blankSecrets(note: Note): Note {
  return { ...note, title: '', content: '', checklist: [] };
}

export async function sealNoteForStorage(
  ownerId: string,
  note: Note,
): Promise<StoredNotePayload> {
  const key = await getNotesCryptoKey();
  if (!key) return note;

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
  if (!isSealedStoredNote(stored)) {
    const { sealedBody: _drop, ...note } = stored;
    return note;
  }

  const key = await getNotesCryptoKey();
  const bytes = payloadToBytes(stored.sealedBody)!;
  if (!key) {
    const { sealedBody: _drop, ...shell } = stored;
    return blankSecrets(shell);
  }
  try {
    const plain = await openNoteBytes(key, bytes, noteAad(ownerId, stored.id));
    const parsed = JSON.parse(new TextDecoder().decode(plain)) as Partial<NoteSecrets>;
    const { sealedBody: _drop, ...shell } = stored;
    return {
      ...shell,
      title: typeof parsed.title === 'string' ? parsed.title : '',
      content: typeof parsed.content === 'string' ? parsed.content : '',
      checklist: Array.isArray(parsed.checklist) ? parsed.checklist : [],
    };
  } catch {
    const { sealedBody: _drop, ...shell } = stored;
    return blankSecrets(shell);
  }
}

/** Rewrite a legacy plaintext row to sealed form when a key is available. */
export async function maybeMigrateStoredNote(
  ownerId: string,
  stored: StoredNotePayload,
  write: (next: StoredNotePayload) => Promise<void>,
): Promise<void> {
  if (isSealedStoredNote(stored)) return;
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
