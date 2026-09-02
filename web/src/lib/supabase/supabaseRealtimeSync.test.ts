import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  SUPABASE_PULL_DEBOUNCE_MS,
  SUPABASE_PULL_FALLBACK_MS,
} from '@/lib/supabase/constants';

const postgresHandlers: Array<() => void> = [];
let subscribeStatus: 'SUBSCRIBED' | 'CHANNEL_ERROR' = 'SUBSCRIBED';
const removeChannel = vi.fn();

vi.mock('@/lib/supabase/client', () => ({
  getSupabaseClient: () => ({
    channel: () => {
      const channel = {
        on: vi.fn(
          (
            _event: string,
            _config: unknown,
            handler: () => void,
          ) => {
            postgresHandlers.push(handler);
            return channel;
          },
        ),
        subscribe: vi.fn((callback: (status: string) => void) => {
          callback(subscribeStatus);
          return channel;
        }),
      };
      return channel;
    },
    removeChannel,
  }),
}));

import { subscribeSupabaseNoteRealtime } from '@/lib/supabase/supabaseRealtimeSync';

describe('subscribeSupabaseNoteRealtime', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    postgresHandlers.length = 0;
    subscribeStatus = 'SUBSCRIBED';
    removeChannel.mockClear();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('debounces postgres_changes into onWake', () => {
    const onWake = vi.fn();
    const onFallback = vi.fn();
    const unsubscribe = subscribeSupabaseNoteRealtime('owner-1', onWake, onFallback);

    postgresHandlers.forEach((handler) => handler());
    postgresHandlers.forEach((handler) => handler());
    expect(onWake).not.toHaveBeenCalled();

    vi.advanceTimersByTime(SUPABASE_PULL_DEBOUNCE_MS);
    expect(onWake).toHaveBeenCalledTimes(1);
    expect(onFallback).not.toHaveBeenCalled();

    unsubscribe();
    expect(removeChannel).toHaveBeenCalled();
  });

  it('starts fallback polling when the channel errors', () => {
    subscribeStatus = 'CHANNEL_ERROR';
    const onWake = vi.fn();
    const onFallback = vi.fn();
    const unsubscribe = subscribeSupabaseNoteRealtime('owner-1', onWake, onFallback);

    vi.advanceTimersByTime(SUPABASE_PULL_FALLBACK_MS);
    expect(onFallback).toHaveBeenCalledTimes(1);

    unsubscribe();
  });
});
