import { useEffect, useState } from 'react';
import { countPendingPushes } from '@/lib/local/notesLocalRepository';
import { useAuthStore } from '@/store/authStore';

/**
 * How many of the signed-in account's notes hold an edit the cloud has not confirmed yet, read
 * each time [active] turns true. Zero when signed out or when the count cannot be read: this only
 * ever adds a notice, so failing quiet is the safe direction.
 */
export function useUnsyncedEditCount(active: boolean): number {
  const uid = useAuthStore((state) => state.user?.uid ?? null);
  const [count, setCount] = useState(0);

  useEffect(() => {
    if (!active || !uid) {
      setCount(0);
      return;
    }
    let cancelled = false;
    countPendingPushes(uid)
      .then((value) => {
        if (!cancelled) setCount(value);
      })
      .catch(() => {
        if (!cancelled) setCount(0);
      });
    return () => {
      cancelled = true;
    };
  }, [active, uid]);

  return count;
}
