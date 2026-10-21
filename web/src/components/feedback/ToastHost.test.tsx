import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { ToastHost, toastDurationMs } from '@/components/feedback/ToastHost';
import { useToastStore } from '@/store/toastStore';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

describe('ToastHost', () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    vi.useFakeTimers();
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    act(() => root.render(createElement(ToastHost)));
  });

  afterEach(() => {
    act(() => {
      useToastStore.getState().dismiss();
      root.unmount();
    });
    container.remove();
    vi.useRealTimers();
  });

  const show = (...args: Parameters<ReturnType<typeof useToastStore.getState>['show']>) =>
    act(() => useToastStore.getState().show(...args));
  const advance = (ms: number) => act(() => vi.advanceTimersByTime(ms));
  const toastText = () => container.textContent ?? '';

  it('gives an Undo longer than a notice, and an error longer than a notice', () => {
    expect(toastDurationMs({ onAction: () => {} })).toBeGreaterThan(toastDurationMs({}));
    expect(toastDurationMs({ tone: 'error' })).toBeGreaterThan(toastDurationMs({}));
  });

  it('keeps both live regions in the document before any toast is shown', () => {
    // A live region that mounts together with its text is not reliably announced.
    expect(container.querySelector('[role="status"][aria-live="polite"]')).not.toBeNull();
    expect(container.querySelector('[role="alert"]')).not.toBeNull();
    expect(toastText()).toBe('');
  });

  it('announces errors through the alert region', () => {
    show('Could not save', 'error');
    expect(container.querySelector('[role="alert"]')?.textContent).toContain('Could not save');
    expect(container.querySelector('[role="status"]')?.textContent).toBe('');
  });

  it('pauses while the pointer is over it and resumes with the time it had left', () => {
    show('Note moved to trash', 'default', 'Undo', vi.fn());
    const toast = container.querySelector<HTMLElement>('[role="status"] > div')!;

    advance(2000);
    // React listens for pointerenter through pointerover on the root.
    act(() => {
      toast.dispatchEvent(new PointerEvent('pointerover', { bubbles: true }));
    });
    advance(60_000);
    expect(toastText()).toContain('Note moved to trash');

    act(() => {
      toast.dispatchEvent(new PointerEvent('pointerout', { bubbles: true, relatedTarget: document.body }));
    });
    advance(toastDurationMs({ onAction: () => {} }) - 2000 - 50);
    expect(toastText()).toContain('Note moved to trash');
    advance(100);
    expect(toastText()).toBe('');
  });

  it('pauses while focus is inside it', () => {
    show('Note archived', 'default', 'Undo', vi.fn());
    const undo = [...container.querySelectorAll('button')].find((b) => b.textContent === 'Undo')!;
    act(() => undo.focus());
    advance(60_000);
    expect(toastText()).toContain('Note archived');
  });

  it('runs Undo on Ctrl+Z and on Cmd+Z', () => {
    const onUndo = vi.fn();
    show('Note moved to trash', 'default', 'Undo', onUndo);
    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'z', ctrlKey: true }));
    });
    expect(onUndo).toHaveBeenCalledTimes(1);
    expect(toastText()).toBe('');

    show('Note archived', 'default', 'Undo', onUndo);
    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'z', metaKey: true }));
    });
    expect(onUndo).toHaveBeenCalledTimes(2);
  });

  it('leaves Ctrl+Z alone when the toast offers no Undo', () => {
    show('Note restored');
    const event = new KeyboardEvent('keydown', { key: 'z', ctrlKey: true, cancelable: true });
    act(() => {
      window.dispatchEvent(event);
    });
    expect(event.defaultPrevented).toBe(false);
    expect(toastText()).toContain('Note restored');
  });

  it('can be dismissed', () => {
    show('Note pinned');
    const dismiss = container.querySelector<HTMLButtonElement>('button[aria-label="Dismiss"]')!;
    act(() => dismiss.click());
    expect(toastText()).toBe('');
  });
});
