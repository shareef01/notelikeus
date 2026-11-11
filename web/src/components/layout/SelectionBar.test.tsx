import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, createElement, type ComponentProps } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { SelectionBar } from '@/components/layout/SelectionBar';

describe('SelectionBar Move up / Move down', () => {
  let container: HTMLDivElement;
  let root: Root;

  const render = (props: Partial<ComponentProps<typeof SelectionBar>>) => {
    act(() => {
      root.render(
        createElement(SelectionBar, {
          selectedCount: 1,
          allFilteredSelected: false,
          currentFilter: 'active',
          onClearSelection: () => {},
          onToggleSelectAll: () => {},
          ...props,
        }),
      );
    });
  };
  const button = (label: string) =>
    container.querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`);

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(() => {
    act(() => root.unmount());
    container.remove();
  });

  it('offers both steps as buttons for one selected note', () => {
    const onMoveUp = vi.fn();
    const onMoveDown = vi.fn();
    render({ onMoveUp, onMoveDown });

    // Real buttons: reachable by Tab and operated with Enter or Space.
    expect(button('Move up')?.type).toBe('button');
    act(() => button('Move up')?.click());
    act(() => button('Move down')?.click());
    expect(onMoveUp).toHaveBeenCalledTimes(1);
    expect(onMoveDown).toHaveBeenCalledTimes(1);
  });

  it('leaves them out for several notes, or when the caller offers nothing', () => {
    render({ selectedCount: 2, onMoveUp: () => {}, onMoveDown: () => {} });
    expect(button('Move up')).toBeNull();
    render({ selectedCount: 1 });
    expect(button('Move down')).toBeNull();
  });
});
