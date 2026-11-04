import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { DeleteAccountDialog } from '@/components/settings/DeleteAccountDialog';

/** The only thing standing between a stray tap and an irreversible delete. */
describe('DeleteAccountDialog', () => {
  let container: HTMLDivElement;
  let root: Root;
  const onConfirm = vi.fn(async () => {});
  const onExportBackup = vi.fn<() => Promise<{ complete: boolean; included: number; total: number } | null> | void>();

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    vi.clearAllMocks();
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(() => {
    act(() => root.unmount());
    container.remove();
  });

  function render(open = true) {
    return act(async () => {
      root.render(
        createElement(DeleteAccountDialog, { open, onCancel: () => {}, onExportBackup, onConfirm }),
      );
    });
  }

  function confirmButton() {
    return [...document.body.querySelectorAll('button')].find(
      (button) => button.textContent === 'Delete account',
    )!;
  }

  async function type(value: string) {
    const input = document.body.querySelector<HTMLInputElement>('input[type="text"]')!;
    const setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
    await act(async () => {
      setValue.call(input, value);
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });
  }

  it('says it is permanent, immediate and reaches other devices', async () => {
    await render();

    const text = document.body.textContent ?? '';
    expect(text).toContain('permanently deletes your account');
    expect(text).toContain('cannot be undone');
    expect(text).toContain('other signed-in devices');
  });

  it.each(['', 'delete', 'DELET', ' DELETE'])('stays disarmed for %j', async (value) => {
    await render();

    await type(value);

    expect(confirmButton().disabled).toBe(true);
  });

  it('arms only on the exact word, and then deletes', async () => {
    await render();

    await type('DELETE');
    expect(confirmButton().disabled).toBe(false);

    await act(async () => confirmButton().click());
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it('does not stay armed when it is closed and reopened', async () => {
    await render();
    await type('DELETE');

    await render(false);
    await render();

    expect(document.body.querySelector<HTMLInputElement>('input[type="text"]')!.value).toBe('');
    expect(confirmButton().disabled).toBe(true);
  });

  function exportButton() {
    return [...document.body.querySelectorAll('button')].find((button) =>
      button.textContent?.startsWith('Export a complete backup'),
    )!;
  }

  function lossCheckbox() {
    return document.body.querySelector<HTMLInputElement>('input[type="checkbox"]');
  }

  it('offers a backup without deleting anything', async () => {
    await render();

    await act(async () => exportButton().click());

    expect(onExportBackup).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  // AUD-001: the backup offered here must not be mistaken for a safe one when images are missing.
  it('says a backup is complete only when every image is in it, and needs nothing more', async () => {
    onExportBackup.mockResolvedValueOnce({ complete: true, included: 3, total: 3 });
    await render();

    await act(async () => exportButton().click());
    await type('DELETE');

    expect(document.body.textContent).toContain('Complete backup saved');
    expect(document.body.textContent).toContain('all 3 images');
    expect(lossCheckbox()).toBeNull();
    expect(confirmButton().disabled).toBe(false);
  });

  it('shows an incomplete backup as incomplete, and stays disarmed until the loss is accepted', async () => {
    onExportBackup.mockResolvedValueOnce({ complete: false, included: 1, total: 4 });
    await render();

    await act(async () => exportButton().click());
    await type('DELETE');

    const text = document.body.textContent ?? '';
    expect(text).toContain('This backup is incomplete: 3 images of 4 could not be included');
    expect(text).not.toContain('Complete backup saved');
    expect(confirmButton().disabled).toBe(true);

    await act(async () => lossCheckbox()!.click());
    expect(confirmButton().disabled).toBe(false);
    await act(async () => confirmButton().click());
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it('asks again after a new export, and forgets the answer when closed', async () => {
    onExportBackup.mockResolvedValue({ complete: false, included: 0, total: 2 });
    await render();
    await act(async () => exportButton().click());
    await act(async () => lossCheckbox()!.click());

    await act(async () => exportButton().click());
    expect(lossCheckbox()!.checked).toBe(false);

    await render(false);
    await render();
    expect(lossCheckbox()).toBeNull();
    onExportBackup.mockReset();
  });

  it('reports a failed export as nothing saved', async () => {
    onExportBackup.mockResolvedValueOnce(null);
    await render();

    await act(async () => exportButton().click());

    expect(document.body.textContent).toContain('The backup failed, so nothing was saved');
  });

  it('still lets the user delete without exporting anything', async () => {
    await render();

    await type('DELETE');

    expect(lossCheckbox()).toBeNull();
    expect(confirmButton().disabled).toBe(false);
  });

  it('cannot delete while the backup is still being written', async () => {
    let finish: (value: { complete: boolean; included: number; total: number }) => void = () => {};
    onExportBackup.mockReturnValueOnce(new Promise((resolve) => (finish = resolve)));
    await render();
    await type('DELETE');

    await act(async () => exportButton().click());
    expect(confirmButton().disabled).toBe(true);

    await act(async () => finish({ complete: true, included: 0, total: 0 }));
    expect(confirmButton().disabled).toBe(false);
  });
});
