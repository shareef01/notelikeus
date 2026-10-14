import { describe, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { useFocusTrap } from '@/hooks/useFocusTrap';
import { useShortcuts, type ShortcutBinding, type ShortcutOptions } from '@/hooks/useShortcuts';

function mountShortcuts(bindings: ShortcutBinding[], options?: ShortcutOptions): () => void {
  const container = document.createElement('div');
  document.body.appendChild(container);

  function Host() {
    useShortcuts(bindings, options);
    return createElement('div', null, createElement('input', { id: 'test-input', type: 'text' }));
  }

  const root = createRoot(container);
  act(() => {
    root.render(createElement(Host));
  });

  return () => {
    act(() => {
      root.unmount();
    });
    container.remove();
  };
}

/** A dialog holding the keyboard, as ModalDialog and ResponsiveSheet do. */
function Dialog() {
  const ref = useFocusTrap<HTMLDivElement>(true, () => {});
  return createElement('div', { ref }, createElement('button', null, 'OK'));
}

describe('useShortcuts', () => {
  it('triggers action on exact key match', () => {
    const action = vi.fn();
    const unmount = mountShortcuts([{ key: '/', action }]);

    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: '/', bubbles: true }));
    });

    expect(action).toHaveBeenCalledTimes(1);
    unmount();
  });

  it('triggers action case-insensitively', () => {
    const action = vi.fn();
    const unmount = mountShortcuts([{ key: 'n', action }]);

    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'N', bubbles: true }));
    });

    expect(action).toHaveBeenCalledTimes(1);
    unmount();
  });

  it('triggers ctrlOrMeta shortcuts on ctrlKey or metaKey', () => {
    const action = vi.fn();
    const unmount = mountShortcuts([{ key: 'k', ctrlOrMeta: true, action }]);

    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true, bubbles: true }));
    });
    expect(action).toHaveBeenCalledTimes(1);

    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'K', metaKey: true, bubbles: true }));
    });
    expect(action).toHaveBeenCalledTimes(2);

    // Should NOT trigger if ctrl/meta is missing
    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', bubbles: true }));
    });
    expect(action).toHaveBeenCalledTimes(2);

    unmount();
  });

  it('ignores shortcuts when target is editable unless allowInInputs is true', () => {
    const actionNormal = vi.fn();
    const actionAllowed = vi.fn();
    const unmount = mountShortcuts([
      { key: 'n', action: actionNormal },
      { key: 'Escape', allowInInputs: true, action: actionAllowed },
    ]);

    const input = document.getElementById('test-input') as HTMLInputElement;

    act(() => {
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'n', bubbles: true }));
    });
    expect(actionNormal).not.toHaveBeenCalled();

    act(() => {
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    });
    expect(actionAllowed).toHaveBeenCalledTimes(1);

    unmount();
  });

  it('cleans up listener on unmount', () => {
    const action = vi.fn();
    const unmount = mountShortcuts([{ key: '/', action }]);

    unmount();

    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: '/', bubbles: true }));
    });

    expect(action).not.toHaveBeenCalled();
  });

  it('leaves a key it does not handle to the browser', () => {
    const unmount = mountShortcuts([{ key: 'f', ctrlOrMeta: true, action: () => false }]);
    const event = new KeyboardEvent('keydown', { key: 'f', ctrlKey: true, bubbles: true, cancelable: true });

    act(() => {
      window.dispatchEvent(event);
    });

    // Ctrl+F with nothing to focus must still open the browser's find bar.
    expect(event.defaultPrevented).toBe(false);
    unmount();
  });

  it('tries the next binding when one declines', () => {
    const second = vi.fn();
    const unmount = mountShortcuts([
      { key: 'n', action: () => false },
      { key: 'n', action: second },
    ]);

    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'n', bubbles: true }));
    });

    expect(second).toHaveBeenCalledTimes(1);
    unmount();
  });

  it('stands aside while a dialog is open when asked to, so keys reach the dialog only', () => {
    const screen = vi.fn();
    const unmount = mountShortcuts([{ key: 'n', action: screen }], { ignoreWhileModalOpen: true });

    const host = document.createElement('div');
    document.body.appendChild(host);
    const dialogRoot = createRoot(host);
    act(() => dialogRoot.render(createElement(Dialog)));

    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'n', bubbles: true }));
    });
    expect(screen).not.toHaveBeenCalled();

    act(() => dialogRoot.unmount());
    host.remove();
    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'n', bubbles: true }));
    });
    expect(screen).toHaveBeenCalledTimes(1);
    unmount();
  });
});
