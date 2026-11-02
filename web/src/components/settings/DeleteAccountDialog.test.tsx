import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { DeleteAccountDialog } from '@/components/settings/DeleteAccountDialog';

/** The only thing standing between a stray tap and an irreversible delete. */
describe('DeleteAccountDialog', () => {
  let container: HTMLDivElement;
  let root: Root;
  const onConfirm = vi.fn(async () => {});
  const onExportBackup = vi.fn();

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
    const input = document.body.querySelector('input')!;
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

    expect(document.body.querySelector('input')!.value).toBe('');
    expect(confirmButton().disabled).toBe(true);
  });

  it('offers a backup without deleting anything', async () => {
    await render();

    const exportButton = [...document.body.querySelectorAll('button')].find(
      (button) => button.textContent === 'Export a backup first',
    )!;
    await act(async () => exportButton.click());

    expect(onExportBackup).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();
  });
});
