# Code audit of changes since 2026-11-05 — 2026-11-17

Audited range: `2864aabc..59ef4fed` (50 commits, 335 files outside `docs/`, about 17,000 added
lines). The baseline is the checkout the [2026-11-05 audit](../2026-11-05/REPORT.md) ended on.

**Result: 4 findings — 1 P1, 2 P2, 1 P3. The P1 and one P2 were reproduced with failing tests; the
other two were confirmed by reading the code and were not exercised.** No code was changed. The
reproduction tests were run and then removed; they are in the appendix.

This is a review of a diff, not proof that the range holds no other defect. What was read closely,
what was skimmed and what was not read is listed under Coverage.

| ID | Severity | Platform | Finding | Evidence |
| --- | --- | --- | --- | --- |
| AUD-G01 | P1 | Web | A refused edit that removed an image deletes the image the surviving cloud version still uses | Reproduced |
| AUD-G02 | P2 | Web | Moving a note to trash from the editor is dropped when an autosave is in flight, and reported as done | Reproduced |
| AUD-G03 | P2 | Web | Every signed-in save decrypts the whole library | Read, not measured |
| AUD-G04 | P3 | CI | Three required checks pass as "skipped" when their `changes` job fails | Read, not exercised |

## Findings

### AUD-G01 — P1: a sync conflict deletes an image from the version that won

Locations: `web/src/lib/attachments/removedAttachmentCleanup.ts:13-25`,
`web/src/lib/supabase/pendingPushes.ts:95` and `:226-259`,
`web/src/lib/local/notesLocalRepository.ts:462-485`.

Introduced by the AUD-E02 fix (`e9e85932`), which made image removal durable: `putLocalEdit` records
the removed image in `pendingAttachmentRemovals`, and `retryRemovedAttachmentCleanup` deletes it
from R2 once the note has no pending push and the local note no longer lists the image.

Both conditions are also true after the push is **refused**:

1. This browser removes image X from note N while another device edits N and keeps X.
2. The push of N conflicts. `keepBothVersions` stores this browser's version as a "(conflicted
   copy)" and replaces N with the cloud's version. The conflict payload carries no attachment list,
   so local N now has `attachments: []`, and the pending marker for N is cleared.
3. `runFlush` then calls `retryRemovedAttachmentCleanup`. N has no pending push and does not list
   X, so it sends `DELETE` for X to the Worker.
4. `begin_note_attachment_delete` (`20261019000000_internal_guard_schema.sql:345-399`) marks the
   row deleted whenever it exists. It does not check that a live note still references it, and an
   attachment id is retired for good.

The cloud version of N is the one every device shows, and it loses its image. The conflicted copy
never had it. The conflict path is documented as keeping both versions "so neither is lost"
(`pendingPushes.ts:44-45`).

The removal intent is also never cancelled when its edit is refused, and while it is listed
`hydrateNotesWithAttachments` (`attachmentSyncService.ts:63-72`) hides X from N on this browser.

Evidence: appendix test 1. After the flush, local N holds the other device's text, the removal list
for N is empty, and the blob store received `delete('1', 'image', 'A')`.

Fix: tie a removal to the edit that made it. Run the cleanup only from `settleAccepted`, for the
note whose push the server accepted, and drop the note's removal intent in `resolveConflictedPush`
in the same transaction that clears its pending marker. Deciding from "no pending push and not in
the local note" cannot tell an accepted edit from a refused one.

### AUD-G02 — P2: trash from the editor is lost during an in-flight autosave

Locations: `web/src/hooks/useNoteEditor.ts:657-662` and `:244-256`,
`web/src/screens/EditorScreen.tsx:297-308`.

Introduced by the AUD-E01 fix. A finished save keeps newer typing by comparing `draftRevision`
with the revision it started from. `trashNote` sets `isTrashed` directly on `stateRef` and never
bumps `draftRevision`, so a save that was already running sees no newer revision and writes its
own snapshot back over the state, without the flag. The queued trash save then finds the draft
equal to what was just saved and returns `unchanged`.

`handleDelete` treats anything but `failed` as success: it closes the editor and shows "moved to
trash" with Undo. The note stays in the list, and nothing was written.

The window is the length of one save, which for a signed-in user includes the cloud push, image
uploads and the cleanup in AUD-G03: type, pause for a second, press the trash button before the
save indicator clears.

Evidence: appendix test 2. `trashNote()` resolves to `{ status: 'unchanged' }`, `saveNote` was
called once, never with `isTrashed`, and the editor state is no longer trashed.

Fix: route the flag through `patch`, or bump `draftRevision` in `trashNote`. `handleDelete` should
also confirm the stored note is trashed before it reports it.

### AUD-G03 — P2: each save decrypts every note

Locations: `web/src/lib/notes/noteActions.ts:86-89`,
`web/src/lib/attachments/removedAttachmentCleanup.ts:9-10`,
`web/src/lib/local/notesLocalRepository.ts:353-357`, `web/src/lib/notes/noteOrder.ts`
(`commitNotePositions`).

`saveNote` awaits `retryRemovedAttachmentCleanup` on every signed-in save. That function calls
`listNotes(ownerId)` before it looks at whether anything is waiting, and `listNotes` reads and
decrypts the account's whole library. The editor autosaves a second after each pause in typing, so
the cost of one save grows with the number of notes, and the editor's save chain waits for it.

Three things follow from the same code:

- `commitNotePositions` saves every renumbered note in parallel. Moving one note in a list of N
  whose positions are not already dense starts up to N saves, each decrypting N notes.
- `listNotes` throws if any stored note cannot be decrypted, and `saveNote` swallows that. One
  unreadable note stops removed-image cleanup for the whole account.
- `putLocalEdit` writes `pendingAttachmentRemovals[noteId] = []` for every edit, and nothing
  removes empty entries, so the owner meta row grows by one key per note ever edited.

Not measured. A timing with a few thousand notes would say how much this costs in practice.

Fix: return early when no note has a non-empty removal list, read only the notes that have one,
and delete a note's key when its list empties.

### AUD-G04 — P3: a failed `changes` job turns three required checks green

Locations: `.github/workflows/supabase.yml`, `attachments-worker.yml`, `account-deletion-e2e.yml`
(the `changes` job added in #451).

`database-tests`, `Worker verify` and `Account deletion e2e` now depend on a `changes` job that
decides whether they run. A job whose dependency fails is skipped, and a skipped job satisfies a
required check. If `changes` fails — a checkout error, or `git diff` failing on a missing base
commit — the real tests are skipped and the pull request can merge without them.

`web.yml` already handles the same shape correctly: its `Web e2e` gate runs with `if: always()`
and fails unless the matrix result is `success`.

Not exercised: forcing the failure needs a pull request against the real branch protection.

Fix: give each workflow a final gate job with `if: always()` that fails unless `changes` succeeded
and the test job either succeeded or was skipped because `changes` said `run=false`, and make that
gate the required check.

## Deployment state

- **Migration `20261115000000`**: the manual `supabase.yml` run from `50eb484f` succeeded on
  2026-11-15 at 05:46 CET. The run before it, from `a22249ae` at 05:28 CET, failed, which is what
  #484 fixed. I did not query the production database to confirm the migration is applied.
- **Attachments Worker**: last deployed from `809cfe51` on 2026-11-04. #433 (`d9dd614b`,
  2026-11-05) is still not deployed. This is already item 1.2 in
  [`REMAINING_WORK.md`](../../audit/REMAINING_WORK.md). Nothing later in this range changes Worker
  behaviour: the other Worker edits only strip byte-order marks and bump dev dependencies.

## Checked and found sound

- **Sweep rotation** (`20261115000000_rotate_account_deletion_sweep.sql`): the function is now
  volatile, keeps `require_service_role`, claims with `FOR UPDATE SKIP LOCKED`, and an abandoned
  batch comes round again. The Worker needs no change to use it.
- **Web account isolation** (`ownerScope.ts` and its callers): the incarnation counter refuses
  A → B → A, requests carry the owner's own token, and global stores are only written while the
  scope is active. Covers save, delete, restore, cloud wipe, import, hydration and deferred image
  cleanup.
- **Web note ids** (`reserveLocalNoteId`): reserved in the owner meta transaction and re-checked
  against existing keys.
- **Content digest v2**: the Kotlin and TypeScript keys are built the same way (UTF-16 lengths,
  same sort), and an old-format digest fails to match, which keeps the cloud copy.
- **Native sign-out sealing** (`SignedOutAccountVault`, `LocalAccountIsolator`, both sync
  managers): sealed before the session ends, armed only after the wipe, restored only for the same
  account, and removed in the transaction that writes the notes back. Note ids are `AUTOINCREMENT`,
  so guest notes made while signed out cannot collide with sealed ones. Staged image bytes are
  kept on disk per owner; only the in-memory cache is cleared.
- **Sync state stores**: the desktop store is synchronized throughout, and both keep the sign-out
  intent across `clear()`.
- **`SupabaseSessionStore`**: one refresh at a time, and a refreshed session is dropped if the
  session changed meanwhile or belongs to another user.
- **`SingleInstance`**: an OS file lock, so a crash cannot leave the app unable to start. The
  activation file can only ask the running copy to show its window.
- **CI**: actions stay pinned to commit SHAs, `db push --linked --yes`, the web e2e gate, and the
  release job reading the Gradle cache without writing it.
- **deepmerge patch**: covers all three shipped entry points and has its own tests.

## Coverage

Read closely: the migration and its pgTAP test, `workers/attachments/src/accountDeletion.ts` and
the delete path in `index.ts`, every workflow diff, and the web data layer (`lib/notes`,
`lib/local`, `lib/attachments`, `lib/supabase`, `lib/auth`, `store`, `useNoteEditor.ts`, the
reorder path in `useNoteActions.ts`). On native: the vault, the isolator, both sync managers, both
sync state stores, `SupabaseSessionStore`, `SingleInstance`, the tray controller and the Room
migration.

Skimmed, changed lines only: `AccountDeletionTransport`, `AttachmentAtRestMigrator`,
`DesktopReminderManager`, `searchNotes`.

Not read: web UI components and styles (`FiltersSheet`, `ProfileSheet`, `EditorScreen` layout,
cards), the query parser, matcher and saved filters beyond how `searchNotes` calls them, native UI
files, `NoteAppFunctions`, the image ingestion changes, the Kotlin 2.4 and Compose 1.12 upgrades
with their dependency metadata, and the new e2e specs.

Not run: the full web test suite, and any Kotlin or database test (Gradle and Docker do not run on
this machine; see `REMAINING_WORK.md` §3). Only the two tests in the appendix were run.

## Appendix: reproduction tests

Both failed on `59ef4fed` under `npx vitest run` in `web/`.

### 1. AUD-G01 — `web/src/lib/supabase/pendingPushes.conflictRemoval.test.ts`

```ts
import { beforeEach, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ apply: vi.fn() }));
vi.mock('@/lib/attachments/attachmentConfig', () => ({ isR2AttachmentsEnabled: () => true }));
vi.mock('@/lib/supabase/supabaseSyncEngine', async (importOriginal) => ({
  ...await importOriginal<typeof import('@/lib/supabase/supabaseSyncEngine')>(),
  applyNoteChange: mocks.apply,
}));

import { setAttachmentBlobStoreForTests } from '@/lib/attachments/attachmentBlobStoreRegistry';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { listNotes, putLocalEdit, putNote } from '@/lib/local/notesLocalRepository';
import { flushPendingPushes } from '@/lib/supabase/pendingPushes';
import { NoteConflictError } from '@/lib/supabase/supabaseSyncEngine';
import { useNotesStore } from '@/store/notesStore';
import { useAuthStore } from '@/store/authStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { createEmptyNote } from '@/types/note';

const owner = 'A';
const image = { id: 'image', noteId: 1, type: 'image' as const, storagePath: 'r2:owners/A/1/image' };
const del = vi.fn();

beforeEach(async () => {
  vi.resetAllMocks();
  await resetNotesDatabaseForTests();
  useNotesStore.getState().reset();
  useAuthStore.getState().setUser({ uid: owner, email: null, displayName: null });
  useTombstoneStore.getState().reset();
  del.mockResolvedValue(undefined);
  setAttachmentBlobStoreForTests({ upload: vi.fn(), download: vi.fn(), delete: del });
});

it('keeps the image of the cloud version when the edit that removed it is refused', async () => {
  const withImage = createEmptyNote({ id: '1', localId: 1, content: 'shared text', attachments: [image] });
  await putNote(owner, withImage);
  const withoutImage = { ...withImage, content: 'edited here, image removed', attachments: [], timestamp: 2000 };
  await putLocalEdit(owner, withoutImage, [image]);
  useNotesStore.getState().setNotes([withoutImage]);

  const cloud = createEmptyNote({ id: '1', localId: 1, content: 'edited on the other device', timestamp: 1500 });
  mocks.apply.mockImplementation(async (_owner: string, note: { id: string }) => {
    if (note.id === '1') throw new NoteConflictError('1', cloud, 'Revision conflict');
    return note;
  });

  await flushPendingPushes(owner);

  expect((await listNotes(owner)).find((n) => n.id === '1')?.content).toBe('edited on the other device');
  expect(del).not.toHaveBeenCalled(); // fails: called with ('1', 'image', 'A')
});
```

### 2. AUD-G02 — `web/src/hooks/useNoteEditor.trashDuringSave.test.tsx`

```tsx
import { afterEach, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';

vi.mock('@/lib/notes/noteActions', () => ({ saveNote: vi.fn(), removeNote: vi.fn() }));
import { saveNote } from '@/lib/notes/noteActions';
import { useNoteEditor } from '@/hooks/useNoteEditor';
import { useNotesStore } from '@/store/notesStore';
import { createEmptyNote } from '@/types/note';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
afterEach(() => { vi.useRealTimers(); });

it('saves the trash flag when an autosave is already in flight', async () => {
  vi.useFakeTimers();
  useNotesStore.getState().setNotes([createEmptyNote({ id: '1', localId: 1, content: 'initial' })]);
  let finish!: () => void;
  vi.mocked(saveNote).mockReset()
    .mockImplementationOnce(() => new Promise<void>((resolve) => { finish = resolve; }))
    .mockResolvedValue(undefined);
  let editor!: ReturnType<typeof useNoteEditor>;
  function Probe() { editor = useNoteEditor('1'); return null; }
  const root = createRoot(document.createElement('div'));
  act(() => root.render(createElement(Probe)));
  try {
    act(() => editor.setContent('edited'));
    await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
    await act(async () => {
      const trashing = editor.trashNote();
      finish();
      await trashing; // resolves to { status: 'unchanged' }
    });
    const trashedSaves = vi.mocked(saveNote).mock.calls.filter(([note]) => note.isTrashed);
    expect(trashedSaves).toHaveLength(1); // fails: 0
  } finally { act(() => root.unmount()); }
});
```
