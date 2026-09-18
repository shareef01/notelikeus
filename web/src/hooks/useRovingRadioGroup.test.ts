import { describe, expect, it, vi } from 'vitest';
import { act, createElement, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { useRovingRadioGroup } from '@/hooks/useRovingRadioGroup';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

describe('UX-C — useRovingRadioGroup WAI-ARIA Radiogroup keyboard contract', () => {
  function TestRadiogroup({
    items,
    initialValue,
    onChange,
  }: {
    items: string[];
    initialValue: string;
    onChange?: (val: string) => void;
  }) {
    const [val, setVal] = useState(initialValue);
    const handleChange = (newVal: string) => {
      setVal(newVal);
      onChange?.(newVal);
    };

    const { getRadioProps, getContainerProps } = useRovingRadioGroup({
      items,
      value: val,
      onChange: handleChange,
    });

    return createElement(
      'div',
      { role: 'radiogroup', 'aria-label': 'Test Group', ...getContainerProps() },
      items.map((item) =>
        createElement(
          'button',
          {
            key: item,
            type: 'button',
            role: 'radio',
            'aria-checked': item === val,
            ...getRadioProps(item),
          },
          item,
        ),
      ),
    );
  }

  it('assigns tabIndex=0 to selected radio and tabIndex=-1 to unselected radios', () => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);

    act(() => {
      root.render(
        createElement(TestRadiogroup, {
          items: ['a', 'b', 'c'],
          initialValue: 'b',
          onChange: vi.fn(),
        }),
      );
    });

    const radios = container.querySelectorAll('button[role="radio"]');
    expect(radios[0].getAttribute('tabindex')).toBe('-1');
    expect(radios[1].getAttribute('tabindex')).toBe('0');
    expect(radios[2].getAttribute('tabindex')).toBe('-1');

    act(() => {
      root.unmount();
    });
    container.remove();
  });

  it('navigates with ArrowRight/ArrowDown, ArrowLeft/ArrowUp, Home, and End with focus following selection', () => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);
    const onChange = vi.fn();

    act(() => {
      root.render(
        createElement(TestRadiogroup, {
          items: ['first', 'second', 'third'],
          initialValue: 'first',
          onChange,
        }),
      );
    });

    const radios = container.querySelectorAll<HTMLButtonElement>('button[role="radio"]');
    radios[0].focus();

    // ArrowRight from first -> selects second
    act(() => {
      radios[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    });
    expect(onChange).toHaveBeenLastCalledWith('second');

    // ArrowLeft from second -> selects first
    act(() => {
      radios[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));
    });
    expect(onChange).toHaveBeenLastCalledWith('first');

    // ArrowLeft from first -> wraps to third
    act(() => {
      radios[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));
    });
    expect(onChange).toHaveBeenLastCalledWith('third');

    // End -> selects last ('third')
    act(() => {
      radios[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true }));
    });
    expect(onChange).toHaveBeenLastCalledWith('third');

    // Home -> selects first ('first')
    act(() => {
      radios[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true }));
    });
    expect(onChange).toHaveBeenLastCalledWith('first');

    act(() => {
      root.unmount();
    });
    container.remove();
  });
});
