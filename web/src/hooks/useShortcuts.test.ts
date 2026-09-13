import { describe, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { useShortcuts, type ShortcutBinding } from '@/hooks/useShortcuts';

function mountShortcuts(bindings: ShortcutBinding[]): () => void {
  const container = document.createElement('div');
  document.body.appendChild(container);

  function Host() {
    useShortcuts(bindings);
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
});
