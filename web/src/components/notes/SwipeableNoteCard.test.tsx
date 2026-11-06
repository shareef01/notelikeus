import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { SwipeableNoteCard } from './SwipeableNoteCard';
import type { Note } from '@/types/note';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

function render() {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  const onClick = vi.fn(), onArchive = vi.fn(), onTrash = vi.fn(), onLongPress = vi.fn();
  act(() => root.render(createElement(SwipeableNoteCard, {
    note: { id: 'touch-note' } as Note, onClick, onArchive, onTrash, onLongPress,
    children: createElement('span', null, 'Note'),
  })));
  const surface = container.querySelector<HTMLElement>('.touch-pan-y')!;
  surface.setPointerCapture = vi.fn();
  const pointer = (type: string, x = 100, y = 200) => act(() => {
    surface.dispatchEvent(new PointerEvent(type, { bubbles: true, pointerId: 1, pointerType: 'touch', clientX: x, clientY: y }));
  });
  const cleanup = () => { act(() => root.unmount()); container.remove(); };
  return { surface, pointer, cleanup, onClick, onArchive, onTrash, onLongPress };
}

afterEach(() => { vi.useRealTimers(); document.body.innerHTML = ''; });

describe('mobile card scroll gestures', () => {
  it('cancelling a pointer is never a tap', () => {
    const card = render();
    try {
      card.pointer('pointerdown'); card.pointer('pointercancel');
      expect(card.onClick).not.toHaveBeenCalled();
      expect(card.onArchive).not.toHaveBeenCalled();
      expect(card.onTrash).not.toHaveBeenCalled();
    } finally { card.cleanup(); }
  });
  it.each([195, 5])('cancelling a swipe at x=%s never commits an action', x => {
    const card = render();
    try {
      card.pointer('pointerdown'); card.pointer('pointermove', x); card.pointer('pointercancel', x);
      expect(card.onArchive).not.toHaveBeenCalled();
      expect(card.onTrash).not.toHaveBeenCalled();
      expect(card.onClick).not.toHaveBeenCalled();
      expect(card.surface.style.transform).toBe('translateX(0px)');
    } finally { card.cleanup(); }
  });
  it('a mainly vertical drag is not a horizontal swipe', () => {
    const card = render();
    try {
      card.pointer('pointerdown'); card.pointer('pointermove', 190, 40); card.pointer('pointerup', 190, 40);
      expect(card.onArchive).not.toHaveBeenCalled();
      expect(card.onClick).not.toHaveBeenCalled();
    } finally { card.cleanup(); }
  });
  it('an intentional horizontal swipe still archives', () => {
    const card = render();
    try {
      card.pointer('pointerdown'); card.pointer('pointermove', 195); card.pointer('pointerup', 195);
      expect(card.onArchive).toHaveBeenCalledTimes(1);
      expect(card.onClick).not.toHaveBeenCalled();
    } finally { card.cleanup(); }
  });
  it('a tap still opens the note', () => {
    const card = render();
    try {
      card.pointer('pointerdown'); card.pointer('pointerup');
      expect(card.onClick).toHaveBeenCalledTimes(1);
    } finally { card.cleanup(); }
  });
  it('scroll cancellation and unmount clear long press timers', () => {
    vi.useFakeTimers();
    const card = render();
    card.pointer('pointerdown'); card.pointer('pointercancel');
    act(() => vi.advanceTimersByTime(500));
    expect(card.onLongPress).not.toHaveBeenCalled();
    card.pointer('pointerdown'); card.cleanup();
    act(() => vi.advanceTimersByTime(500));
    expect(card.onLongPress).not.toHaveBeenCalled();
  });
});
