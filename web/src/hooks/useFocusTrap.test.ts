import { describe, expect, it, vi } from 'vitest';
import { act, createElement, useEffect } from 'react';
import { createRoot } from 'react-dom/client';
import { useFocusTrap } from '@/hooks/useFocusTrap';

function mountTrap(
  open: boolean,
  onClose: () => void,
  closeOnEscape = true,
): () => void {
  const container = document.createElement('div');
  document.body.appendChild(container);

  function TrapHost() {
    const panelRef = useFocusTrap<HTMLDivElement>(open, onClose, { closeOnEscape });
    useEffect(() => {
      panelRef.current?.querySelector('button')?.focus();
    }, [panelRef]);
    return createElement(
      'div',
      { ref: panelRef },
      createElement('button', { type: 'button' }, 'Inside'),
    );
  }

  const root = createRoot(container);
  act(() => {
    root.render(createElement(TrapHost));
  });

  return () => {
    act(() => {
      root.unmount();
    });
    container.remove();
  };
}

describe('useFocusTrap', () => {
  it('calls onClose when Escape is pressed and closeOnEscape is true', () => {
    const onClose = vi.fn();
    const unmount = mountTrap(true, onClose, true);

    act(() => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    });

    expect(onClose).toHaveBeenCalledTimes(1);
    unmount();
  });

  it('does not call onClose when closeOnEscape is false', () => {
    const onClose = vi.fn();
    const unmount = mountTrap(true, onClose, false);

    act(() => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    });

    expect(onClose).not.toHaveBeenCalled();
    unmount();
  });
});
