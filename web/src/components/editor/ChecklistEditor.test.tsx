import { describe, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { ChecklistEditor } from '@/components/editor/ChecklistEditor';
import type { ChecklistItem } from '@/types/checklist';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

describe('UX-G — Checklist keyboard ergonomics', () => {
  const sampleItems: ChecklistItem[] = [
    { id: 'item-1', text: 'Buy groceries', isChecked: false, position: 0 },
    { id: 'item-2', text: 'Clean kitchen', isChecked: false, position: 1 },
  ];

  it('pressing Enter on a checklist item triggers creation of a new item', () => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);
    const onAdd = vi.fn();

    act(() => {
      root.render(
        createElement(ChecklistEditor, {
          items: sampleItems,
          contentColor: '#ffffff',
          onUpdate: vi.fn(),
          onAdd,
          onRemove: vi.fn(),
        }),
      );
    });

    const inputs = container.querySelectorAll<HTMLInputElement>('input[type="text"]');
    expect(inputs.length).toBe(2);

    act(() => {
      inputs[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    });

    expect(onAdd).toHaveBeenCalledTimes(1);

    act(() => {
      root.unmount();
    });
    container.remove();
  });

  it('pressing Backspace on an empty checklist item removes it', () => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);
    const onRemove = vi.fn();

    const itemsWithEmpty: ChecklistItem[] = [
      { id: 'item-1', text: 'Buy groceries', isChecked: false, position: 0 },
      { id: 'item-2', text: '', isChecked: false, position: 1 },
    ];

    act(() => {
      root.render(
        createElement(ChecklistEditor, {
          items: itemsWithEmpty,
          contentColor: '#ffffff',
          onUpdate: vi.fn(),
          onAdd: vi.fn(),
          onRemove,
        }),
      );
    });

    const inputs = container.querySelectorAll<HTMLInputElement>('input[type="text"]');
    expect(inputs[1].value).toBe('');

    act(() => {
      inputs[1].dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace', bubbles: true }));
    });

    expect(onRemove).toHaveBeenCalledWith('item-2');

    act(() => {
      root.unmount();
    });
    container.remove();
  });

  it('pressing Escape on an active item stops event propagation to prevent editor closure', () => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);

    act(() => {
      root.render(
        createElement(ChecklistEditor, {
          items: sampleItems,
          contentColor: '#ffffff',
          onUpdate: vi.fn(),
          onAdd: vi.fn(),
          onRemove: vi.fn(),
        }),
      );
    });

    const inputs = container.querySelectorAll<HTMLInputElement>('input[type="text"]');
    const escEvent = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    const stopPropagationSpy = vi.spyOn(escEvent, 'stopPropagation');

    act(() => {
      inputs[0].dispatchEvent(escEvent);
    });

    expect(stopPropagationSpy).toHaveBeenCalled();

    act(() => {
      root.unmount();
    });
    container.remove();
  });

  it('does not create endless items when Enter is pressed on an empty item', () => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);
    const onAdd = vi.fn();

    act(() => {
      root.render(
        createElement(ChecklistEditor, {
          items: [{ id: 'item-empty', text: '', isChecked: false, position: 0 }],
          contentColor: '#ffffff',
          onUpdate: vi.fn(),
          onAdd,
          onRemove: vi.fn(),
        }),
      );
    });

    const input = container.querySelector<HTMLInputElement>('input[type="text"]')!;
    act(() => {
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    });

    expect(onAdd).not.toHaveBeenCalled();

    act(() => {
      root.unmount();
    });
    container.remove();
  });

  it('does not trigger item creation while IME composition is active', () => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);
    const onAdd = vi.fn();

    act(() => {
      root.render(
        createElement(ChecklistEditor, {
          items: sampleItems,
          contentColor: '#ffffff',
          onUpdate: vi.fn(),
          onAdd,
          onRemove: vi.fn(),
        }),
      );
    });

    const input = container.querySelector<HTMLInputElement>('input[type="text"]')!;
    const imeEvent = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true });
    Object.defineProperty(imeEvent, 'isComposing', { value: true });

    act(() => {
      input.dispatchEvent(imeEvent);
    });

    expect(onAdd).not.toHaveBeenCalled();

    act(() => {
      root.unmount();
    });
    container.remove();
  });
});
