import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { localMocks } = vi.hoisted(() => ({
  localMocks: { loadLocalNotesIntoStore: vi.fn() },
}));

vi.mock('@/lib/local/hydrateFromRemote', () => localMocks);

import { useGuestLocalNotesBootstrap } from '@/hooks/useGuestLocalNotesBootstrap';
import { resolveNotesViewState } from '@/screens/main/notesViewState';
import { useAuthStore } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';
import { createEmptyNote, type Note } from '@/types/note';

function note(id: string): Note {
  return createEmptyNote({ id, localId: Number(id), title: `Note ${id}`, timestamp: 1 });
}

let root: Root | null = null;

async function renderHook(): Promise<void> {
  function TestComponent() {
    useGuestLocalNotesBootstrap(true);
    return null;
  }
  const container = document.createElement('div');
  await act(async () => {
    root = createRoot(container);
    root.render(React.createElement(TestComponent));
  });
}

/**
 * Guest mode has no cloud copy at all: IndexedDB *is* the library. A bootstrap read that throws
 * used to leave the store in 'loading' behind a spinner with nothing on the way to end it, so the
 * failure was invisible and there was nothing to retry.
 */
describe('useGuestLocalNotesBootstrap', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useNotesStore.getState().reset();
    useAuthStore.getState().reset();
    useAuthStore.getState().enterGuestMode();
    useAuthStore.getState().setReady(true);
  });

  afterEach(async () => {
    const current = root;
    root = null;
    if (current) await act(async () => current.unmount());
  });

  it('loads guest notes from IndexedDB', async () => {
    localMocks.loadLocalNotesIntoStore.mockImplementation(async () => {
      useNotesStore.getState().setNotes([note('1')]);
      return [];
    });

    await renderHook();

    expect(localMocks.loadLocalNotesIntoStore).toHaveBeenCalledTimes(1);
    expect(useNotesStore.getState().status).toBe('ready');
    expect(useNotesStore.getState().notes).toHaveLength(1);
  });

  it('surfaces a storage failure as a blocking, retryable error instead of a stuck spinner', async () => {
    localMocks.loadLocalNotesIntoStore.mockRejectedValue(
      new DOMException('A mutation operation was attempted on a database', 'InvalidStateError'),
    );

    await renderHook();

    const state = useNotesStore.getState();
    expect(state.status).toBe('error');
    expect(state.status).not.toBe('loading');
    expect(state.error).toBe('A mutation operation was attempted on a database');

    expect(
      resolveNotesViewState({
        isLoading: state.status === 'loading',
        error: state.error,
        syncError: state.syncError,
        notesCount: 0,
        filteredCount: 0,
      }),
    ).toEqual({
      kind: 'blocking-error',
      message: 'A mutation operation was attempted on a database',
    });
  });

  it('never renders an object-shaped storage failure as [object Object]', async () => {
    localMocks.loadLocalNotesIntoStore.mockRejectedValue({ name: 'QuotaExceededError', code: 22 });

    await renderHook();

    expect(useNotesStore.getState().error).toBe('QuotaExceededError (22)');
    expect(useNotesStore.getState().error).not.toContain('[object Object]');
  });
});
