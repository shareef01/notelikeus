import { useCallback, useRef } from 'react';

interface UseRovingRadioGroupProps<T> {
  items: T[];
  value: T;
  onChange: (value: T) => void;
}

export function useRovingRadioGroup<T>({
  items,
  value,
  onChange,
}: UseRovingRadioGroupProps<T>) {
  const containerRef = useRef<HTMLElement | null>(null);

  const focusItem = useCallback((targetItem: T) => {
    if (!containerRef.current) return;
    const targetIdx = items.indexOf(targetItem);
    if (targetIdx === -1) return;
    const buttons = containerRef.current.querySelectorAll<HTMLButtonElement>('button[role="radio"]');
    if (buttons[targetIdx]) {
      buttons[targetIdx].focus();
    }
  }, [items]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      const currentIndex = items.indexOf(value);
      if (currentIndex === -1) return;

      let nextIndex = -1;

      switch (e.key) {
        case 'ArrowRight':
        case 'ArrowDown':
          e.preventDefault();
          nextIndex = (currentIndex + 1) % items.length;
          break;
        case 'ArrowLeft':
        case 'ArrowUp':
          e.preventDefault();
          nextIndex = (currentIndex - 1 + items.length) % items.length;
          break;
        case 'Home':
          e.preventDefault();
          nextIndex = 0;
          break;
        case 'End':
          e.preventDefault();
          nextIndex = items.length - 1;
          break;
        default:
          return;
      }

      if (nextIndex !== -1) {
        const nextItem = items[nextIndex];
        onChange(nextItem);
        requestAnimationFrame(() => {
          focusItem(nextItem);
        });
      }
    },
    [items, value, onChange, focusItem],
  );

  const getContainerProps = useCallback(
    () => ({
      ref: (el: HTMLElement | null) => {
        containerRef.current = el;
      },
      onKeyDown: handleKeyDown,
    }),
    [handleKeyDown],
  );

  const getRadioProps = useCallback(
    (item: T) => ({
      tabIndex: item === value ? 0 : -1,
    }),
    [value],
  );

  return {
    getContainerProps,
    getRadioProps,
  };
}
