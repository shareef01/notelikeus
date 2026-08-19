import { beforeEach, describe, expect, it, vi } from 'vitest';

import { runNoteAction } from '@/lib/notes/runNoteAction';
import { showUndoToast } from '@/lib/notes/showUndoToast';
import { useToastStore } from '@/store/toastStore';

describe('runNoteAction', () => {
  beforeEach(() => {
    useToastStore.getState().dismiss();
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  });

  it('reports a rejected action as an error toast', async () => {
    const succeeded = await runNoteAction('Archive', () => Promise.reject(new Error('offline')));

    expect(succeeded).toBe(false);
    expect(useToastStore.getState().message).toMatchObject({ tone: 'error' });
    expect(useToastStore.getState().message?.text).toContain('Archive failed');
  });

  it('leaves the toast alone when the action succeeds', async () => {
    const succeeded = await runNoteAction('Archive', () => Promise.resolve());

    expect(succeeded).toBe(true);
    expect(useToastStore.getState().message).toBeNull();
  });
});

describe('showUndoToast', () => {
  beforeEach(() => {
    useToastStore.getState().dismiss();
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  });

  it('surfaces a failing revert instead of dropping it', async () => {
    showUndoToast({ message: 'Note archived', revert: () => Promise.reject(new Error('offline')) });

    useToastStore.getState().message?.onAction?.();
    await vi.waitFor(() => {
      expect(useToastStore.getState().message).toMatchObject({ tone: 'error' });
    });
  });
});
