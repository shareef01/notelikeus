import { useEffect, useRef } from 'react';

interface UseLongPressOptions {
  onLongPress: () => void;
  delayMs?: number;
}

/** Fires onLongPress after holding; suppresses the next click when long-press triggers. */
export function useLongPress({ onLongPress, delayMs = 450 }: UseLongPressOptions) {
  const timerRef = useRef<number | null>(null);
  const longPressTriggered = useRef(false);

  useEffect(() => {
    return () => {
      if (timerRef.current != null) {
        window.clearTimeout(timerRef.current);
      }
    };
  }, []);

  const clearTimer = () => {
    if (timerRef.current != null) {
      window.clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  };

  return {
    longPressProps: {
      onPointerDown: () => {
        longPressTriggered.current = false;
        clearTimer();
        timerRef.current = window.setTimeout(() => {
          longPressTriggered.current = true;
          onLongPress();
        }, delayMs);
      },
      onPointerUp: clearTimer,
      onPointerCancel: clearTimer,
      onPointerLeave: clearTimer,
    },
    shouldSuppressClick: () => {
      if (longPressTriggered.current) {
        longPressTriggered.current = false;
        return true;
      }
      return false;
    },
  };
}
