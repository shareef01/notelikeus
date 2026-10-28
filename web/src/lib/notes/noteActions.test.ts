import { beforeEach, describe, expect, it, vi } from "vitest";

const { remoteMocks } = vi.hoisted(() => ({
  remoteMocks: {
    upsertNote: vi.fn().mockResolvedValue(undefined),
    deleteNote: vi.fn().mockResolvedValue(undefined),
  },
}));

vi.mock("@/lib/remote/remoteNotesDataSourceRegistry", () => ({
  getRemoteNotesDataSource: () => remoteMocks,
}));

vi.mock("@/lib/local/notesLocalRepository", () => ({
  putNote: vi.fn().mockResolvedValue(undefined),
  // Signed-in edits are written together with their pending-push marker (F-06).
  putLocalEdit: vi.fn().mockResolvedValue(undefined),
  deleteNote: vi.fn().mockResolvedValue(undefined),
  getOwnerMeta: vi.fn().mockResolvedValue({ lastRemoteRevision: 0 }),
}));

vi.mock("@/lib/notes/tombstones", async (importOriginal) => ({
  // The real module is spread so `RestoreRefusedError` is the genuine class: the restore path
  // checks for it, and a partially mocked module would make that check throw rather than decide.
  ...(await importOriginal<typeof import("@/lib/notes/tombstones")>()),
  restoreCloudNote: vi.fn().mockResolvedValue(undefined),
}));

const { attachmentMocks } = vi.hoisted(() => ({
  attachmentMocks: {
    isR2AttachmentsEnabled: vi.fn(() => false),
    gcAttachmentsAfterNoteDelete: vi.fn().mockResolvedValue(undefined),
    deleteAttachmentsForNote: vi.fn().mockResolvedValue(undefined),
    syncNoteAttachments: vi.fn(async (note: unknown) => note),
  },
}));

vi.mock("@/lib/attachments/attachmentConfig", () => ({
  isR2AttachmentsEnabled: () => attachmentMocks.isR2AttachmentsEnabled(),
}));

vi.mock("@/lib/attachments/attachmentSyncService", () => ({
  gcAttachmentsAfterNoteDelete: (noteId: string, attachments: unknown) =>
    attachmentMocks.gcAttachmentsAfterNoteDelete(noteId, attachments),
  deleteAttachmentsForNote: (noteId: string, attachments: unknown) =>
    attachmentMocks.deleteAttachmentsForNote(noteId, attachments),
  syncNoteAttachments: (note: unknown) =>
    attachmentMocks.syncNoteAttachments(note),
}));

import { restoreCloudNote } from "@/lib/notes/tombstones";
import { getOwnerMeta } from "@/lib/local/notesLocalRepository";
import { CloudDeleteUnconfirmedError } from "@/lib/remote/remoteNotesDataSource";
import { deleteNote, putLocalEdit, putNote } from "@/lib/local/notesLocalRepository";
import {
  removeNote,
  removeNotes,
  restorePermanentlyDeletedNote,
  saveNote,
} from "@/lib/notes/noteActions";
import { useAuthStore } from "@/store/authStore";
import { useNotesStore } from "@/store/notesStore";
import { useTombstoneStore } from "@/store/tombstoneStore";
import { createEmptyNote } from "@/types/note";

function makeNote() {
  return createEmptyNote({
    id: "1",
    localId: 1,
    timestamp: 1,
    title: "Note",
    content: "Body",
  });
}

describe("saveNote", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(putNote).mockResolvedValue(undefined);
    vi.mocked(deleteNote).mockResolvedValue(undefined);
    remoteMocks.upsertNote.mockResolvedValue(undefined);
    remoteMocks.deleteNote.mockResolvedValue(undefined);
    attachmentMocks.syncNoteAttachments.mockReset();
    attachmentMocks.syncNoteAttachments.mockImplementation(
      async (note: unknown) => note,
    );
    useNotesStore.getState().reset();
    useAuthStore.getState().reset();
    useTombstoneStore.getState().reset();
  });

  it("saves locally and uploads to the cloud when signed in", async () => {
    useAuthStore
      .getState()
      .setUser({ uid: "user-1", email: null, displayName: null });

    await saveNote(makeNote());

    expect(remoteMocks.upsertNote).toHaveBeenCalledWith(
      "user-1",
      expect.objectContaining({ id: "1" }),
    );
    expect(putLocalEdit).toHaveBeenCalled();
    expect(useNotesStore.getState().notes.some((note) => note.id === "1")).toBe(
      true,
    );
  });

  it("saves locally only when signed out", async () => {
    await saveNote(makeNote());

    expect(remoteMocks.upsertNote).not.toHaveBeenCalled();
    expect(useNotesStore.getState().notes.some((note) => note.id === "1")).toBe(
      true,
    );
  });

  it("skips no-op saves for an unchanged note", async () => {
    useAuthStore
      .getState()
      .setUser({ uid: "user-1", email: null, displayName: null });
    const note = makeNote();

    await saveNote(note);
    await saveNote(note);

    expect(remoteMocks.upsertNote).toHaveBeenCalledTimes(1);
  });

  it("saves note text locally even when attachment upload is offline / fails", async () => {
    useAuthStore
      .getState()
      .setUser({ uid: "user-1", email: null, displayName: null });
    attachmentMocks.syncNoteAttachments.mockRejectedValueOnce(
      new Error("R2 Worker temporary 503"),
    );
    const note = {
      ...makeNote(),
      attachments: [
        {
          id: "att-1",
          noteId: 1,
          storagePath: "pending:att-1",
          type: "image" as const,
          mimeType: "image/png",
          fileName: "test.png",
          byteSize: 10,
          createdAt: 1,
        },
      ],
    };

    // The note text MUST save locally without throwing
    await saveNote(note);

    expect(putLocalEdit).toHaveBeenCalled();
    expect(useNotesStore.getState().notes.some((n) => n.id === note.id)).toBe(
      true,
    );
  });

  it("does not mutate in-memory store when the IndexedDB write fails", async () => {
    useAuthStore
      .getState()
      .setUser({ uid: "user-1", email: null, displayName: null });
    vi.mocked(putLocalEdit).mockRejectedValueOnce(new Error("IndexedDB disk full"));
    const note = makeNote();

    await expect(saveNote(note)).rejects.toThrow(/IndexedDB disk full/);

    // In-memory store must not falsely contain the unpersisted note
    expect(useNotesStore.getState().notes.some((n) => n.id === note.id)).toBe(
      false,
    );
  });

  it("keeps note durable in IndexedDB and memory when remote upsert fails", async () => {
    useAuthStore
      .getState()
      .setUser({ uid: "user-1", email: null, displayName: null });
    remoteMocks.upsertNote.mockRejectedValueOnce(
      new Error("Supabase 503 outage"),
    );
    const note = makeNote();

    // Local-first: remote error does not fail the local save
    await saveNote(note);

    // Locally committed note remains durable in store and IndexedDB
    expect(putLocalEdit).toHaveBeenCalled();
    expect(useNotesStore.getState().notes.some((n) => n.id === note.id)).toBe(
      true,
    );
  });
});

describe("restorePermanentlyDeletedNote", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(putNote).mockResolvedValue(undefined);
    vi.mocked(deleteNote).mockResolvedValue(undefined);
    remoteMocks.upsertNote.mockResolvedValue(undefined);
    remoteMocks.deleteNote.mockResolvedValue(undefined);
    vi.mocked(restoreCloudNote).mockResolvedValue(undefined);
    useNotesStore.getState().reset();
    useAuthStore.getState().reset();
    useTombstoneStore.getState().reset();
  });

  it("re-adds the note locally and clears the tombstone when signed out", async () => {
    const note = makeNote();
    await removeNote(note.id);
    expect(useTombstoneStore.getState().isDeleted(note.id)).toBe(true);
    expect(
      useNotesStore.getState().notes.some((entry) => entry.id === note.id),
    ).toBe(false);

    await restorePermanentlyDeletedNote(note);

    expect(useTombstoneStore.getState().isDeleted(note.id)).toBe(false);
    expect(
      useNotesStore.getState().notes.some((entry) => entry.id === note.id),
    ).toBe(true);
    expect(restoreCloudNote).not.toHaveBeenCalled();
    expect(remoteMocks.upsertNote).not.toHaveBeenCalled();
  });

  it("restores through the atomic RPC when signed in", async () => {
    useAuthStore
      .getState()
      .setUser({ uid: "user-1", email: null, displayName: null });
    const note = makeNote();
    await removeNote(note.id);
    expect(remoteMocks.deleteNote).toHaveBeenCalledWith(
      "user-1",
      note.id,
      undefined,
    );

    await restorePermanentlyDeletedNote(note);

    expect(restoreCloudNote).toHaveBeenCalledWith(
      "user-1",
      expect.objectContaining({ id: "1" }),
    );
    expect(remoteMocks.upsertNote).not.toHaveBeenCalled();
    expect(useTombstoneStore.getState().isDeleted(note.id)).toBe(false);
    expect(useTombstoneStore.getState().isRestored(note.id)).toBe(false);
    expect(
      useNotesStore.getState().notes.some((entry) => entry.id === note.id),
    ).toBe(true);
  });

  it("clears the local tombstone and keeps a restore marker when the RPC fails", async () => {
    useAuthStore
      .getState()
      .setUser({ uid: "user-1", email: null, displayName: null });
    const note = makeNote();
    await removeNote(note.id);
    vi.mocked(restoreCloudNote).mockRejectedValueOnce(new Error("rpc failed"));

    await expect(restorePermanentlyDeletedNote(note)).rejects.toThrow(
      /rpc failed/,
    );
    expect(useTombstoneStore.getState().isDeleted(note.id)).toBe(false);
    expect(useTombstoneStore.getState().isRestored(note.id)).toBe(true);
    expect(
      useNotesStore.getState().notes.some((entry) => entry.id === note.id),
    ).toBe(true);
  });

  it("does not prematurely clear tombstone when IndexedDB putNote fails during restore", async () => {
    useAuthStore
      .getState()
      .setUser({ uid: "user-1", email: null, displayName: null });
    const note = makeNote();
    useTombstoneStore.getState().markDeleted(note.id);
    vi.mocked(putNote).mockRejectedValueOnce(
      new Error("IndexedDB restore failure"),
    );

    await expect(restorePermanentlyDeletedNote(note)).rejects.toThrow(
      /IndexedDB restore failure/,
    );

    // Tombstone MUST NOT be cleared if durable persistence failed
    expect(useTombstoneStore.getState().isDeleted(note.id)).toBe(true);
    expect(useNotesStore.getState().notes.some((n) => n.id === note.id)).toBe(
      false,
    );
  });
});

describe("removeNote", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(putNote).mockResolvedValue(undefined);
    vi.mocked(deleteNote).mockResolvedValue(undefined);
    remoteMocks.upsertNote.mockResolvedValue(undefined);
    remoteMocks.deleteNote.mockResolvedValue(undefined);
    attachmentMocks.isR2AttachmentsEnabled.mockReturnValue(false);
    attachmentMocks.gcAttachmentsAfterNoteDelete.mockResolvedValue(undefined);
    useNotesStore.getState().reset();
    useAuthStore.getState().reset();
    useTombstoneStore.getState().reset();
  });

  it("does not remove from memory or tombstone when IndexedDB deleteNote fails", async () => {
    useAuthStore
      .getState()
      .setUser({ uid: "user-1", email: null, displayName: null });
    const note = makeNote();
    useNotesStore.getState().setNotes([note]);
    vi.mocked(deleteNote).mockRejectedValueOnce(
      new Error("IndexedDB delete failed"),
    );

    await expect(removeNote(note.id)).rejects.toThrow(
      /IndexedDB delete failed/,
    );

    // Note must not enter incoherent state: stays in memory, not tombstoned
    expect(useNotesStore.getState().notes.some((n) => n.id === note.id)).toBe(
      true,
    );
    expect(useTombstoneStore.getState().isDeleted(note.id)).toBe(false);
  });

  it("keeps locally committed delete tombstoned and removed when remote delete fails", async () => {
    useAuthStore
      .getState()
      .setUser({ uid: "user-1", email: null, displayName: null });
    const note = makeNote();
    useNotesStore.getState().setNotes([note]);
    remoteMocks.deleteNote.mockRejectedValueOnce(
      new Error("Supabase network timeout"),
    );

    await expect(removeNote(note.id)).rejects.toThrow(
      /Supabase network timeout/,
    );

    // Local commit succeeded: note stays deleted and tombstoned
    expect(deleteNote).toHaveBeenCalledWith("user-1", note.id);
    expect(useNotesStore.getState().notes.some((n) => n.id === note.id)).toBe(
      false,
    );
    expect(useTombstoneStore.getState().isDeleted(note.id)).toBe(true);
  });

  it("skips the tombstone in guest mode so a guest delete can never suppress a real cloud note", async () => {
    useAuthStore.getState().enterGuestMode();
    const note = makeNote();
    await saveNote(note);
    expect(
      useNotesStore.getState().notes.some((entry) => entry.id === note.id),
    ).toBe(true);

    await removeNote(note.id);

    expect(
      useNotesStore.getState().notes.some((entry) => entry.id === note.id),
    ).toBe(false);
    expect(useTombstoneStore.getState().isDeleted(note.id)).toBe(false);
    expect(remoteMocks.deleteNote).not.toHaveBeenCalled();
  });

  it("does not GC attachments when the server note delete fails", async () => {
    attachmentMocks.isR2AttachmentsEnabled.mockReturnValue(true);
    useAuthStore
      .getState()
      .setUser({ uid: "user-1", email: null, displayName: null });
    const note = {
      ...makeNote(),
      attachments: [
        {
          id: "att-1",
          noteId: 1,
          storagePath: "r2:owners/u/notes/1/att-1",
          type: "image",
        },
      ],
    };
    useNotesStore.getState().setNotes([note]);
    remoteMocks.deleteNote.mockRejectedValueOnce(new Error("rpc failed"));

    await expect(removeNote(note.id)).rejects.toThrow(/rpc failed/);
    expect(attachmentMocks.gcAttachmentsAfterNoteDelete).not.toHaveBeenCalled();
    expect(
      useTombstoneStore.getState().pendingAttachmentGcByNoteId[note.id],
    ).toBeUndefined();
    expect(useTombstoneStore.getState().isDeleted(note.id)).toBe(true);
  });

  it("GCs attachments only after the server delete and keeps a retry marker on GC failure", async () => {
    attachmentMocks.isR2AttachmentsEnabled.mockReturnValue(true);
    useAuthStore
      .getState()
      .setUser({ uid: "user-1", email: null, displayName: null });
    const note = {
      ...makeNote(),
      attachments: [
        {
          id: "att-1",
          noteId: 1,
          storagePath: "r2:owners/u/notes/1/att-1",
          type: "image",
        },
      ],
    };
    useNotesStore.getState().setNotes([note]);
    const order: string[] = [];
    remoteMocks.deleteNote.mockImplementation(async () => {
      order.push("deleteNote");
    });
    attachmentMocks.gcAttachmentsAfterNoteDelete.mockImplementation(
      async () => {
        order.push("gc");
        throw new Error("r2 unavailable");
      },
    );

    await removeNote(note.id);

    expect(order).toEqual(["deleteNote", "gc"]);
    expect(useTombstoneStore.getState().isDeleted(note.id)).toBe(true);
    expect(
      useTombstoneStore.getState().pendingAttachmentGcByNoteId[note.id],
    ).toEqual(["att-1"]);
  });

  it("drops in-flight saveNote attachment upload if account switches before upload completes", async () => {
    attachmentMocks.isR2AttachmentsEnabled.mockReturnValue(true);
    useAuthStore
      .getState()
      .setUser({ uid: "user-a", email: null, displayName: null });

    let completeUpload: (synced: any) => void = () => {};
    const uploadPromise = new Promise((resolve) => {
      completeUpload = resolve;
    });
    attachmentMocks.syncNoteAttachments.mockReturnValue(uploadPromise);

    const noteA = {
      ...makeNote(),
      id: "note-a",
      attachments: [
        {
          id: "att-1",
          noteId: 1,
          storagePath: "pending:att-1",
          type: "image",
        },
      ],
    };

    const savePromise = saveNote(noteA);

    // Initial push happened for user-a
    expect(putLocalEdit).toHaveBeenCalledWith("user-a", noteA);

    // Account switches to user-b while upload is in flight
    useAuthStore
      .getState()
      .setUser({ uid: "user-b", email: null, displayName: null });
    useNotesStore.getState().setNotes([]);
    vi.mocked(putLocalEdit).mockClear();
    vi.mocked(putNote).mockClear();
    remoteMocks.upsertNote.mockClear();

    // Now upload completes
    const syncedNoteA = {
      ...noteA,
      attachments: [
        {
          id: "att-1",
          noteId: 1,
          storagePath: "r2:owners/user-a/notes/1/att-1",
          type: "image",
        },
      ],
    };
    completeUpload(syncedNoteA);
    await savePromise;

    // Must NOT write to user-b's IndexedDB partition
    expect(putNote).not.toHaveBeenCalledWith("user-b", expect.anything());
    expect(putLocalEdit).not.toHaveBeenCalledWith("user-b", expect.anything());

    // Must NOT inject into user-b's UI
    expect(useNotesStore.getState().notes.map((n) => n.id)).not.toContain(
      "note-a",
    );

    // Must NOT upload under user-b's cloud account
    expect(remoteMocks.upsertNote).not.toHaveBeenCalledWith(
      "user-b",
      expect.anything(),
    );
  });
});

describe("emptyTrash", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(putNote).mockResolvedValue(undefined);
    vi.mocked(deleteNote).mockResolvedValue(undefined);
    remoteMocks.upsertNote.mockResolvedValue(undefined);
    remoteMocks.deleteNote.mockResolvedValue(undefined);
    useNotesStore.getState().reset();
    useAuthStore.getState().reset();
    useTombstoneStore.getState().reset();
  });

  it("safely processes a mixed batch of deleted notes with distinct baselines", async () => {
    const { emptyTrash } = await import("@/lib/notes/noteActions");
    useAuthStore
      .getState()
      .setUser({ uid: "user-1", email: null, displayName: null });

    // Setup 4 trashed notes
    const noteA = createEmptyNote({
      id: "A",
      localId: 1,
      title: "A",
      isTrashed: true,
    }); // matches remote
    const noteB = createEmptyNote({
      id: "B",
      localId: 2,
      title: "B",
      isTrashed: true,
    }); // newer remote
    const noteC = createEmptyNote({
      id: "C",
      localId: 3,
      title: "C",
      isTrashed: true,
    }); // already deleted
    const noteD = createEmptyNote({
      id: "D",
      localId: 4,
      title: "D",
      isTrashed: true,
    }); // missing baseline

    useNotesStore.getState().setNotes([noteA, noteB, noteC, noteD]);

    // Mock getOwnerMeta so getNoteBaseRevision returns 10 for A, B, C
    vi.mocked(getOwnerMeta).mockResolvedValue({
      lastRemoteRevision: 10,
      knownCloudIds: [],
      noteRevisions: {
        A: 10,
        B: 10,
        C: 10,
      },
    } as any);

    remoteMocks.deleteNote.mockImplementation(async (_userId, id, _rev) => {
      if (id === "A") return; // succeeds
      if (id === "B") throw new Error("Delete conflict");
      if (id === "C") return; // idempotent success
      // F-05: no baseline, so the data source refuses to delete and says so.
      if (id === "D") throw new CloudDeleteUnconfirmedError("D");
    });

    // B's cloud delete failed, but B is gone here and its tombstone retries the delete, so the
    // batch still settles: one note's failure no longer reports every other note as not deleted.
    await expect(emptyTrash()).resolves.toEqual({
      removedIds: ["A", "B", "C", "D"],
      pendingInCloud: 1,
      failedIds: [],
    });

    expect(remoteMocks.deleteNote).toHaveBeenCalledWith("user-1", "A", 10);
    expect(remoteMocks.deleteNote).toHaveBeenCalledWith("user-1", "B", 10);
    expect(remoteMocks.deleteNote).toHaveBeenCalledWith("user-1", "C", 10);
    expect(remoteMocks.deleteNote).toHaveBeenCalledWith(
      "user-1",
      "D",
      undefined,
    );
  });
});

describe("removeNote authority", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(putNote).mockResolvedValue(undefined);
    vi.mocked(deleteNote).mockResolvedValue(undefined);
    remoteMocks.upsertNote.mockResolvedValue(undefined);
    remoteMocks.deleteNote.mockResolvedValue(undefined);
    useNotesStore.getState().reset();
    useAuthStore.getState().reset();
    useTombstoneStore.getState().reset();
  });

  // DEL-4 / WEB-DEL-8: a repeated invocation must reuse the revision the user's original decision
  // was made against. Re-capturing here would send a newer revision and widen what the delete may
  // destroy; against the pre-fix code this assertion sees undefined instead of 10.
  it("reuses the recorded baseline when the note is deleted again", async () => {
    useAuthStore
      .getState()
      .setUser({ uid: "user-1", email: null, displayName: null });
    useTombstoneStore.getState().markDeleted("1", 10);

    await removeNote("1");

    expect(remoteMocks.deleteNote).toHaveBeenCalledWith("user-1", "1", 10);
    expect(useTombstoneStore.getState().baselineRevisionById["1"]).toBe(10);
  });
});

function ownerMeta(knownCloudIds: string[], noteRevisions: Record<string, number> = {}) {
  vi.mocked(getOwnerMeta).mockResolvedValue({
    lastRemoteRevision: 10,
    knownCloudIds,
    noteRevisions,
  } as any);
}

// F-05: a delete with no known cloud revision is done here and finished by a later snapshot. It
// used to surface as "Delete failed" with no Undo, and one such note failed a whole Empty trash.
describe("removeNote without a known cloud revision", () => {
  const attachment = {
    id: "att-1",
    noteId: 1,
    storagePath: "r2:owners/u/notes/1/att-1",
    type: "image",
  };

  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(putNote).mockResolvedValue(undefined);
    vi.mocked(deleteNote).mockResolvedValue(undefined);
    remoteMocks.deleteNote.mockImplementation(async (_userId, id, baseRevision) => {
      if (baseRevision === undefined) throw new CloudDeleteUnconfirmedError(id);
    });
    attachmentMocks.isR2AttachmentsEnabled.mockReturnValue(true);
    attachmentMocks.gcAttachmentsAfterNoteDelete.mockResolvedValue(undefined);
    useNotesStore.getState().reset();
    useAuthStore.getState().reset();
    useTombstoneStore.getState().reset();
    useAuthStore
      .getState()
      .setUser({ uid: "user-1", email: null, displayName: null });
  });

  it("reports a synced note as still on other devices instead of failing", async () => {
    ownerMeta(["1"]);
    const note = { ...makeNote(), attachments: [attachment] };
    useNotesStore.getState().setNotes([note]);

    await expect(removeNote(note.id)).resolves.toBe("cloudDeleteUnconfirmed");

    expect(useNotesStore.getState().notes.some((n) => n.id === note.id)).toBe(false);
    expect(useTombstoneStore.getState().isDeleted(note.id)).toBe(true);
    // The fingerprint is what lets the next snapshot finish the delete.
    expect(useTombstoneStore.getState().deletedDigestById[note.id]).toBeTruthy();
    // The live cloud copy may still reference its images.
    expect(attachmentMocks.gcAttachmentsAfterNoteDelete).not.toHaveBeenCalled();
    expect(
      useTombstoneStore.getState().pendingAttachmentGcByNoteId[note.id],
    ).toBeUndefined();
  });

  it("reports a note no snapshot has shown as deleted", async () => {
    ownerMeta([]);
    useNotesStore.getState().setNotes([makeNote()]);

    await expect(removeNote("1")).resolves.toBe("deleted");
    expect(useTombstoneStore.getState().isDeleted("1")).toBe(true);
  });

  it("still rejects on any other remote failure", async () => {
    ownerMeta(["1"]);
    useNotesStore.getState().setNotes([makeNote()]);
    remoteMocks.deleteNote.mockRejectedValueOnce(new Error("Supabase network timeout"));

    await expect(removeNote("1")).rejects.toThrow(/Supabase network timeout/);
  });

  it("lets Empty trash finish and counts the notes still on other devices", async () => {
    const { emptyTrash } = await import("@/lib/notes/noteActions");
    ownerMeta(["A", "B", "C"], { A: 10 });
    useNotesStore.getState().setNotes([
      createEmptyNote({ id: "A", localId: 1, title: "A", isTrashed: true }),
      createEmptyNote({ id: "B", localId: 2, title: "B", isTrashed: true }),
      createEmptyNote({ id: "C", localId: 3, title: "C", isTrashed: true }),
      createEmptyNote({ id: "D", localId: 4, title: "D", isTrashed: false }),
    ]);

    await expect(emptyTrash()).resolves.toEqual({
      removedIds: ["A", "B", "C"],
      pendingInCloud: 2,
      failedIds: [],
    });

    expect(remoteMocks.deleteNote).toHaveBeenCalledWith("user-1", "A", 10);
    expect(useNotesStore.getState().notes.map((n) => n.id)).toEqual(["D"]);
  });
});

describe("removeNotes", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(deleteNote).mockResolvedValue(undefined);
    remoteMocks.deleteNote.mockResolvedValue(undefined);
    attachmentMocks.isR2AttachmentsEnabled.mockReturnValue(false);
    ownerMeta(["A", "B", "C"], { A: 10, B: 10, C: 10 });
    useNotesStore.getState().reset();
    useAuthStore.getState().reset();
    useTombstoneStore.getState().reset();
    useAuthStore
      .getState()
      .setUser({ uid: "user-1", email: null, displayName: null });
    useNotesStore.getState().setNotes([
      createEmptyNote({ id: "A", localId: 1, title: "A" }),
      createEmptyNote({ id: "B", localId: 2, title: "B" }),
      createEmptyNote({ id: "C", localId: 3, title: "C" }),
    ]);
  });

  it("settles every note when one local delete fails", async () => {
    vi.mocked(deleteNote).mockImplementation(async (_ownerId, id) => {
      if (id === "B") throw new Error("IndexedDB delete failed");
    });

    await expect(removeNotes(["A", "B", "C"])).resolves.toEqual({
      removedIds: ["A", "C"],
      pendingInCloud: 0,
      failedIds: ["B"],
    });
    expect(useNotesStore.getState().notes.map((n) => n.id)).toEqual(["B"]);
    expect(remoteMocks.deleteNote).toHaveBeenCalledWith("user-1", "C", 10);
  });

  it("counts a cloud-only failure as removed and still pending in the cloud", async () => {
    remoteMocks.deleteNote.mockImplementation(async (_userId, id) => {
      if (id === "A") throw new Error("Supabase network timeout");
    });

    await expect(removeNotes(["A", "B"])).resolves.toEqual({
      removedIds: ["A", "B"],
      pendingInCloud: 1,
      failedIds: [],
    });
    expect(useTombstoneStore.getState().isDeleted("A")).toBe(true);
  });
});
