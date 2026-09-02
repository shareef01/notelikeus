import type { RealtimeChannel } from '@supabase/supabase-js';
import { getSupabaseClient } from '@/lib/supabase/client';
import {
  SUPABASE_PULL_DEBOUNCE_MS,
  SUPABASE_PULL_FALLBACK_MS,
} from '@/lib/supabase/constants';

export type RealtimeSubscriptionStatus = 'SUBSCRIBED' | 'CHANNEL_ERROR' | 'TIMED_OUT' | 'CLOSED';

/**
 * Subscribes to postgres_changes on notes + note_tombstones for [ownerId].
 * Invokes [onWake] (debounced) when the server signals a change.
 * Falls back to [onFallbackTick] when the channel cannot stay subscribed.
 */
export function subscribeSupabaseNoteRealtime(
  ownerId: string,
  onWake: () => void,
  onFallbackTick: () => void,
  onStatus?: (status: RealtimeSubscriptionStatus) => void,
): () => void {
  const client = getSupabaseClient();
  let debounceTimer: ReturnType<typeof setTimeout> | null = null;
  let fallbackTimer: ReturnType<typeof setInterval> | null = null;
  let stopped = false;

  const wake = () => {
    if (stopped) return;
    if (debounceTimer) clearTimeout(debounceTimer);
    debounceTimer = setTimeout(() => {
      debounceTimer = null;
      if (!stopped) onWake();
    }, SUPABASE_PULL_DEBOUNCE_MS);
  };

  const startFallback = () => {
    if (stopped || fallbackTimer) return;
    fallbackTimer = setInterval(() => {
      if (!stopped) onFallbackTick();
    }, SUPABASE_PULL_FALLBACK_MS);
  };

  const stopFallback = () => {
    if (fallbackTimer) {
      clearInterval(fallbackTimer);
      fallbackTimer = null;
    }
  };

  const channel: RealtimeChannel = client
    .channel(`notelikeus-notes-${ownerId}`)
    .on(
      'postgres_changes',
      {
        event: '*',
        schema: 'public',
        table: 'notes',
        filter: `owner_id=eq.${ownerId}`,
      },
      () => wake(),
    )
    .on(
      'postgres_changes',
      {
        event: '*',
        schema: 'public',
        table: 'note_tombstones',
        filter: `owner_id=eq.${ownerId}`,
      },
      () => wake(),
    )
    .subscribe((status) => {
      if (stopped) return;
      onStatus?.(status as RealtimeSubscriptionStatus);
      if (status === 'SUBSCRIBED') {
        stopFallback();
        return;
      }
      if (status === 'CHANNEL_ERROR' || status === 'TIMED_OUT') {
        startFallback();
      }
    });

  return () => {
    stopped = true;
    if (debounceTimer) clearTimeout(debounceTimer);
    stopFallback();
    void client.removeChannel(channel);
  };
}
