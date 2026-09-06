import { describe, expect, it, vi } from 'vitest';
import { act, createElement, useEffect, useState } from 'react';
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

  /**
   * EditorScreen rebuilds `onFloatClose` on every render (`useNoteEditor` returns a fresh object),
   * so a trap keyed on the handler identity re-ran on every keystroke: the cleanup restored focus
   * to the opener and the re-run focused the panel's first element, moving the caret out of the
   * note body and onto the back button after a single character.
   */
  it('keeps focus in place when the handler identity changes on re-render', () => {
    const container = document.createElement('div');
    document.body.appendChild(container);

    let setText: ((value: string) => void) | undefined;

    function Panel() {
      const [text, setTextState] = useState('');
      setText = setTextState;
      const panelRef = useFocusTrap<HTMLDivElement>(true, () => {});
      return createElement(
        'div',
        { ref: panelRef },
        createElement('button', { type: 'button', id: 'toolbar' }, 'Bold'),
        createElement('textarea', { id: 'body', value: text, onChange: () => {} }),
      );
    }

    const root = createRoot(container);
    act(() => {
      root.render(createElement(Panel));
    });

    const body = container.querySelector('#body') as HTMLTextAreaElement;
    act(() => {
      body.focus();
    });
    act(() => {
      setText?.('h');
    });

    expect(document.activeElement).toBe(body);

    act(() => {
      root.unmount();
    });
    container.remove();
  });

  /**
   * Each trap listens on `document`, where `stopPropagation()` does not stop sibling listeners.
   * Without an owner rule, dismissing the link dialog also closed the editor behind it.
   */
  it('gives Escape to the innermost trap only', () => {
    const container = document.createElement('div');
    document.body.appendChild(container);

    const closeOuter = vi.fn();
    const closeInner = vi.fn();

    function Nested() {
      const outerRef = useFocusTrap<HTMLDivElement>(true, closeOuter);
      const innerRef = useFocusTrap<HTMLDivElement>(true, closeInner);
      return createElement(
        'div',
        { ref: outerRef },
        createElement('button', { type: 'button' }, 'Back'),
        createElement(
          'div',
          { ref: innerRef },
          createElement('button', { type: 'button' }, 'Cancel'),
        ),
      );
    }

    const root = createRoot(container);
    act(() => {
      root.render(createElement(Nested));
    });

    act(() => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    });

    expect(closeInner).toHaveBeenCalledTimes(1);
    expect(closeOuter).not.toHaveBeenCalled();

    act(() => {
      root.unmount();
    });
    container.remove();
  });

  /** Once the inner dialog is gone, Escape must reach the trap that is now innermost. */
  it('hands Escape back to the outer trap after the inner one closes', () => {
    const container = document.createElement('div');
    document.body.appendChild(container);

    const closeOuter = vi.fn();
    let setInnerOpen: ((open: boolean) => void) | undefined;

    function Nested() {
      const [innerOpen, setOpen] = useState(true);
      setInnerOpen = setOpen;
      const outerRef = useFocusTrap<HTMLDivElement>(true, closeOuter);
      const innerRef = useFocusTrap<HTMLDivElement>(innerOpen, () => {});
      return createElement(
        'div',
        { ref: outerRef },
        createElement('button', { type: 'button' }, 'Back'),
        innerOpen
          ? createElement(
              'div',
              { ref: innerRef },
              createElement('button', { type: 'button' }, 'Cancel'),
            )
          : null,
      );
    }

    const root = createRoot(container);
    act(() => {
      root.render(createElement(Nested));
    });
    act(() => {
      setInnerOpen?.(false);
    });
    act(() => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    });

    expect(closeOuter).toHaveBeenCalledTimes(1);

    act(() => {
      root.unmount();
    });
    container.remove();
  });
});
