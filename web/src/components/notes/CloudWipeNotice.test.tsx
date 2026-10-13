import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';

const reuploadLocalNotesAfterWipe = vi.fn();
const reconcileNowAfterUserAction = vi.fn();

vi.mock('@/lib/supabase/wipeGeneration', () => ({
  reuploadLocalNotesAfterWipe: (...args: unknown[]) => reuploadLocalNotesAfterWipe(...args),
}));
vi.mock('@/lib/notes/notesSyncService', () => ({
  reconcileNowAfterUserAction: (...args: unknown[]) => reconcileNowAfterUserAction(...args),
}));

import { CloudWipeNotice } from '@/components/notes/CloudWipeNotice';
import { useNotesStore } from '@/store/notesStore';

/**
 * The only place a wipe on another device reaches the user here (F-13).
 *
 * The data layer has its own tests; what these cover is the part a user acts on — that the notice
 * says what is being held, that "Not now" uploads nothing, that "Upload my notes again" is what
 * actually lifts the block and starts the sync, and that a failure does not look like a success.
 */
describe('CloudWipeNotice', () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    reuploadLocalNotesAfterWipe.mockReset();
    reconcileNowAfterUserAction.mockReset();
    useNotesStore.getState().reset();
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(() => {
    act(() => root.unmount());
    container.remove();
  });

  function buttonByText(text: string): HTMLButtonElement | undefined {
    return Array.from(container.querySelectorAll('button')).find((button) =>
      button.textContent?.includes(text),
    );
  }

  async function render() {
    await act(async () => {
      root.render(createElement(CloudWipeNotice, { userId: 'user-a' }));
    });
  }

  it('renders nothing while there is no wipe to answer', async () => {
    await render();

    expect(container.textContent).toBe('');
  });

  it('says how many notes it is holding back, and offers both choices', async () => {
    useNotesStore.getState().setCloudWipeNotice({ generation: 3, localNoteCount: 2 });
    await render();

    expect(container.textContent).toContain('deleted from another device');
    expect(container.textContent).toContain('Nothing was uploaded');
    expect(container.textContent).toContain('2 notes');
    expect(buttonByText('Upload my notes again')).toBeDefined();
    expect(buttonByText('Not now')).toBeDefined();
  });

  it('says "note", not "notes", when it is holding one', async () => {
    useNotesStore.getState().setCloudWipeNotice({ generation: 1, localNoteCount: 1 });
    await render();

    expect(container.textContent).toContain('1 note is still on this browser');
  });

  it('"Not now" hides the notice and uploads nothing', async () => {
    useNotesStore.getState().setCloudWipeNotice({ generation: 3, localNoteCount: 2 });
    await render();

    await act(async () => {
      buttonByText('Not now')?.click();
    });

    expect(useNotesStore.getState().cloudWipeNotice).toBeNull();
    expect(reuploadLocalNotesAfterWipe).not.toHaveBeenCalled();
    expect(reconcileNowAfterUserAction).not.toHaveBeenCalled();
  });

  it('"Upload my notes again" lifts the block for this generation and starts the sync', async () => {
    reuploadLocalNotesAfterWipe.mockResolvedValue(undefined);
    reconcileNowAfterUserAction.mockResolvedValue(undefined);
    useNotesStore.getState().setCloudWipeNotice({ generation: 3, localNoteCount: 2 });
    await render();

    await act(async () => {
      buttonByText('Upload my notes again')?.click();
    });

    // The generation the notice named, not whatever the store holds later: uploading under a
    // stale number is the failure this whole protocol exists to prevent.
    expect(reuploadLocalNotesAfterWipe).toHaveBeenCalledWith('user-a', 3);
    expect(reconcileNowAfterUserAction).toHaveBeenCalledWith('user-a');
    expect(useNotesStore.getState().cloudWipeNotice).toBeNull();
  });

  it('a failed upload keeps the notice and says the failure', async () => {
    reuploadLocalNotesAfterWipe.mockRejectedValue(new Error('offline'));
    useNotesStore.getState().setCloudWipeNotice({ generation: 3, localNoteCount: 2 });
    await render();

    await act(async () => {
      buttonByText('Upload my notes again')?.click();
    });

    expect(container.textContent).toContain('Could not start the upload');
    expect(container.textContent).toContain('still here');
    expect(useNotesStore.getState().cloudWipeNotice).not.toBeNull();
    expect(reconcileNowAfterUserAction).not.toHaveBeenCalled();
  });
});
