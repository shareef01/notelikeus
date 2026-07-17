import {
  decryptLockedSecrets,
  encryptLockedSecrets,
  isLockedBlob,
  type LockedBlob,
} from '@/lib/crypto/lockedNoteCrypto';
import type { Note } from '@/types/note';

/** On-disk shape: locked notes redact secrets and carry `lockedBlob`. */
export type PersistedNote = Note & { lockedBlob?: LockedBlob };

export async function noteToPersisted(note: Note): Promise<PersistedNote> {
  if (!note.isLocked) {
    const { lockedBlob: _drop, ...rest } = note as PersistedNote;
    return rest;
  }

  const lockedBlob = await encryptLockedSecrets({
    title: note.title,
    content: note.content,
    checklist: note.checklist,
  });

  return {
    ...note,
    title: '',
    content: '',
    checklist: [],
    lockedBlob,
  };
}

export async function noteFromPersisted(raw: PersistedNote): Promise<Note> {
  const { lockedBlob, ...rest } = raw;
  if (!raw.isLocked) {
    return rest;
  }

  if (isLockedBlob(lockedBlob)) {
    try {
      const secrets = await decryptLockedSecrets(lockedBlob);
      return {
        ...rest,
        title: secrets.title,
        content: secrets.content,
        checklist: secrets.checklist,
      };
    } catch (error) {
      console.warn('[Notelikeus] Failed to decrypt locked note; leaving redacted.', error);
      return { ...rest, title: '', content: '', checklist: [] };
    }
  }

  // Legacy plaintext locked notes (pre-encryption) — keep as-is until next save.
  return rest;
}

export async function notesToPersisted(notes: Note[]): Promise<PersistedNote[]> {
  return Promise.all(notes.map((note) => noteToPersisted(note)));
}

export async function notesFromPersisted(notes: PersistedNote[]): Promise<Note[]> {
  return Promise.all(notes.map((note) => noteFromPersisted(note)));
}
