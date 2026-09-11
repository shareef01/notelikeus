import 'fake-indexeddb/auto';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  assertNoSensitiveValues,
  categorizeSyncError,
  DiagnosticsLeakError,
  formatDiagnosticsReport,
  ownerTag,
  type DiagnosticsReport,
} from '@/lib/diagnostics/diagnosticsReport';
import {
  collectDiagnostics,
  recordRealtimeState,
  recordSyncFailure,
  recordSyncSuccess,
  resetDiagnosticsSignalsForTests,
} from '@/lib/diagnostics/collectDiagnostics';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { pendingStoragePath } from '@/lib/attachments/attachmentPaths';
import { useAuthStore } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { labelFromName } from '@/types/label';
import { createEmptyNote, type Note } from '@/types/note';

function note(id: string, overrides: Partial<Note> = {}): Note {
  return createEmptyNote({ id, localId: Number(id), ...overrides });
}

beforeEach(async () => {
  resetDiagnosticsSignalsForTests();
  await resetNotesDatabaseForTests();
  useAuthStore.getState().reset();
  useNotesStore.getState().setNotes([]);
  useTombstoneStore.getState().reset?.();
});

afterEach(() => {
  useAuthStore.getState().reset();
});

describe('ownerTag', () => {
  it('is stable for one owner and different for another', () => {
    const a = ownerTag('9c1f7a3e-2b44-4d1a-9f6e-0c8b2d4e6a10');
    expect(ownerTag('9c1f7a3e-2b44-4d1a-9f6e-0c8b2d4e6a10')).toBe(a);
    expect(ownerTag('11111111-2222-3333-4444-555555555555')).not.toBe(a);
  });

  it('never contains the owner id it was derived from', () => {
    const uid = '9c1f7a3e-2b44-4d1a-9f6e-0c8b2d4e6a10';
    const tag = ownerTag(uid);
    expect(tag).not.toContain(uid);
    expect(tag).not.toContain('9c1f7a3e');
    expect(tag).toMatch(/^acct-[0-9a-f]{8}$/);
  });

  it('names guest and signed-out plainly', () => {
    expect(ownerTag('__guest__')).toBe('guest');
    expect(ownerTag(null)).toBe('none');
  });
});

describe('categorizeSyncError', () => {
  it('maps each failure onto a stable code rather than its message', () => {
    expect(categorizeSyncError(null)).toBe('none');
    expect(categorizeSyncError(new Error('Failed to fetch'))).toBe('offline');
    expect(categorizeSyncError(new Error('Supabase session missing'))).toBe('auth');
    expect(categorizeSyncError(new Error('new row violates row-level security policy'))).toBe(
      'permission',
    );
    expect(
      categorizeSyncError(new Error('Revision conflict for note 7: remote title "Groceries"')),
    ).toBe('conflict');
    expect(
      categorizeSyncError(
        new Error('Cloud returned no notes but 3 were expected — refusing to overwrite local copies.'),
      ),
    ).toBe('empty-cloud-refused');
    expect(categorizeSyncError(new Error('QuotaExceededError'))).toBe('storage');
    expect(categorizeSyncError(new Error('something new'))).toBe('unknown');
  });

  it('does not carry the note title a conflict error embeds', () => {
    const category = categorizeSyncError(
      new Error('Revision conflict for note 7: remote title "Divorce paperwork"'),
    );
    expect(category).toBe('conflict');
    expect(category).not.toContain('Divorce');
  });
});

describe('assertNoSensitiveValues', () => {
  function base(): DiagnosticsReport {
    return {
      schemaVersion: 1,
      generatedAt: 1767225600000,
      app: { version: '1.0.3', platform: 'web' },
      storage: { kind: 'IndexedDB', schemaVersion: 2, available: true, encryptedAtRest: false },
      account: { state: 'signed-in', ownerTag: 'acct-deadbeef' },
      notes: {
        total: 3,
        active: 2,
        archived: 1,
        trashed: 0,
        pinned: 1,
        withReminder: 1,
        withAttachments: 1,
      },
      sync: {
        lastRemoteRevision: 42,
        knownCloudIdCount: 3,
        trackedRevisionCount: 3,
        pendingMutationCount: 0,
        tombstoneCount: 1,
        pendingRestoreCount: 0,
        lastSuccessfulSyncAt: 1767225500000,
        lastErrorCategory: 'none',
        realtime: 'subscribed',
      },
      attachments: {
        stagedCount: 1,
        stagedBytes: 2048,
        pendingUploadCount: 1,
        unresolvedCleanupCount: 0,
        encryptedAtRest: true,
      },
      serviceWorker: { state: 'active', updateAvailable: false },
    };
  }

  it('accepts a well-formed report', () => {
    expect(() => assertNoSensitiveValues(base())).not.toThrow();
    expect(JSON.parse(formatDiagnosticsReport(base()))).toEqual(base());
  });

  it.each([
    ['a JWT', { accessTokenSample: 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig' }],
    ['an email address', { contact: 'someone@example.com' }],
    ['a raw owner id', { owner: '9c1f7a3e-2b44-4d1a-9f6e-0c8b2d4e6a10' }],
    ['an object key', { key: 'owners/9c1f7a3e/notes/7/att' }],
    ['a bearer header', { header: 'Bearer abc123' }],
    ['a password field', { password: 'redacted-but-still-named' }],
  ])('refuses a report carrying %s', (_label, extra) => {
    const leaky = { ...base(), ...extra } as unknown as DiagnosticsReport;
    expect(() => assertNoSensitiveValues(leaky)).toThrow(DiagnosticsLeakError);
    expect(() => formatDiagnosticsReport(leaky)).toThrow(DiagnosticsLeakError);
  });
});

describe('collectDiagnostics', () => {
  it('counts note state without carrying any note content', async () => {
    useAuthStore.getState().enterGuestMode();
    useNotesStore.getState().setNotes([
      note('1', { title: 'Divorce paperwork', content: 'sensitive body' }),
      note('2', { isArchived: true, labels: [labelFromName('Medical')] }),
      note('3', {
        isTrashed: true,
        reminderTimestamp: 4102444800000,
        attachments: [
          {
            id: 'att-one',
            noteId: 3,
            storagePath: pendingStoragePath('att-one'),
            type: 'image',
          },
        ],
      }),
      note('4', { isPinned: true }),
    ]);

    const report = await collectDiagnostics('1.0.3', 1767225600000);
    const text = formatDiagnosticsReport(report);

    expect(report.notes).toEqual({
      total: 4,
      active: 2,
      archived: 1,
      trashed: 1,
      pinned: 1,
      withReminder: 1,
      withAttachments: 1,
    });
    expect(report.account.state).toBe('guest');
    expect(report.account.ownerTag).toBe('guest');
    expect(report.attachments.pendingUploadCount).toBe(1);
    expect(report.attachments.encryptedAtRest).toBe(true);
    expect(report.storage.encryptedAtRest).toBe(false);

    for (const secret of ['Divorce', 'paperwork', 'sensitive body', 'Medical', 'att-one']) {
      expect(text, `report must not contain ${secret}`).not.toContain(secret);
    }
  });

  it('reports the recorded sync outcome, not the error itself', async () => {
    useAuthStore.getState().enterGuestMode();
    recordSyncSuccess(1767225000000);
    recordRealtimeState('subscribed');
    expect((await collectDiagnostics('1.0.3')).sync).toMatchObject({
      lastSuccessfulSyncAt: 1767225000000,
      lastErrorCategory: 'none',
      realtime: 'subscribed',
    });

    recordSyncFailure(categorizeSyncError(new Error('Failed to fetch')));
    const afterFailure = await collectDiagnostics('1.0.3');
    expect(afterFailure.sync.lastErrorCategory).toBe('offline');
    // The last success is kept: "when did this last work" is the question being answered.
    expect(afterFailure.sync.lastSuccessfulSyncAt).toBe(1767225000000);
  });

  it('counts tombstones and pending restores', async () => {
    useAuthStore.getState().enterGuestMode();
    useTombstoneStore.getState().markDeleted('7', 1767225000000);
    useTombstoneStore.getState().markRestored('9');

    const report = await collectDiagnostics('1.0.3');
    expect(report.sync.tombstoneCount).toBe(1);
    expect(report.sync.pendingRestoreCount).toBe(1);
    expect(formatDiagnosticsReport(report)).not.toContain('"7"');
  });

  it('produces a safe report when signed out and nothing is stored', async () => {
    const report = await collectDiagnostics('1.0.3');
    expect(report.account).toEqual({ state: 'signed-out', ownerTag: 'none' });
    expect(report.notes.total).toBe(0);
    expect(() => assertNoSensitiveValues(report)).not.toThrow();
  });
});
