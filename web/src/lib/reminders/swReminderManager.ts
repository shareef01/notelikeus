import { sanitizeReminders, type SwReminder } from './swReminderPayload';

export interface SwReminderStorage {
  load(): Promise<SwReminder[]>;
  save(reminders: SwReminder[]): Promise<void>;
}

export interface SwReminderManagerOptions {
  storage: SwReminderStorage;
  fireNotification: (reminder: SwReminder) => Promise<void>;
  now?: () => number;
  setTimeoutFn?: (callback: () => void, delayMs: number) => ReturnType<typeof setTimeout>;
  clearTimeoutFn?: (timerId: ReturnType<typeof setTimeout>) => void;
  throttleIntervalMs?: number;
}

/** setTimeout delays above ~24.8 days clamp in 32-bit signed ints; re-arm until fireAt is due. */
export const MAX_TIMER_DELAY_MS = 2_147_483_647;

/**
 * Service Worker Reminder Manager
 *
 * NOTIFICATION DELIVERY MODEL:
 *
 * 1. Semantics: At-Most-Once Delivery, Best-Effort Wakeup.
 *    - To prevent spamming users or resurrecting expired alarms, notifications operate with
 *      strict at-most-once delivery semantics: reminders are deleted from memory and committed
 *      to persistent storage BEFORE `showNotification` is called.
 *    - If the worker is terminated or the notification call is rejected (e.g. revoked permissions
 *      or closed browser), the reminder will NOT repeatedly re-fire on future wake-ups.
 *
 * 2. Platform Constraints (Browsers without Web Push):
 *    - Browser service workers are killed by the browser within seconds of becoming idle,
 *      destroying in-memory `setTimeout` handles.
 *    - Because plain Web PWA background execution without Web Push cannot guarantee exact-moment
 *      background wake-ups, reminders are persisted in CacheStorage and caught up on every worker
 *      lifecycle event (activate, navigation fetch, window message).
 *    - Exact scheduled alarm delivery is not claimed where browser platform limits prevent it;
 *      Android AlarmManager paths provide exact delivery, while Web PWA provides best-effort delivery.
 *
 * 3. Concurrency and Race Safety:
 *    - All state mutations (`syncReminders`, `catchUpReminders`, and `handleTimerFired`) are serialized
 *      through an asynchronous FIFO queue (`runExclusive`) to prevent lost-update races.
 *    - Every timer is tagged with a unique generational token (`timerTokens`). Queued timeout callbacks
 *      verify that their token matches the active token for that note ID before mutating state or
 *      firing notifications, completely eliminating stale-callback races from canceled or rescheduled reminders.
 */
export class SwReminderManager {
  private readonly storage: SwReminderStorage;
  private readonly fireNotification: (reminder: SwReminder) => Promise<void>;
  private readonly now: () => number;
  private readonly setTimeoutFn: (callback: () => void, delayMs: number) => ReturnType<typeof setTimeout>;
  private readonly clearTimeoutFn: (timerId: ReturnType<typeof setTimeout>) => void;
  private readonly throttleIntervalMs: number;

  private reminders = new Map<string, SwReminder>();
  private timers = new Map<string, ReturnType<typeof setTimeout>>();
  private timerTokens = new Map<string, number>();
  private nextTimerToken = 1;

  private initialized = false;
  private isDestroyed = false;
  private lifecycleEpoch = 1;
  private lastCatchUp = 0;
  private queue: Promise<void> = Promise.resolve();
  public lastPersistenceError: Error | null = null;

  constructor(options: SwReminderManagerOptions) {
    this.storage = options.storage;
    this.fireNotification = options.fireNotification;
    this.now = options.now ?? (() => Date.now());
    this.setTimeoutFn = options.setTimeoutFn ?? ((cb, ms) => setTimeout(cb, ms));
    this.clearTimeoutFn = options.clearTimeoutFn ?? ((id) => clearTimeout(id));
    this.throttleIntervalMs = options.throttleIntervalMs ?? 30_000;
  }

  /**
   * Serializes all state mutations and reads to eliminate race conditions
   * and lost updates between concurrent timers, sync messages, and catch-up passes.
   */
  private runExclusive<T>(task: () => Promise<T>): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      this.queue = this.queue
        .then(async () => {
          try {
            const result = await task();
            resolve(result);
          } catch (error) {
            reject(error);
          }
        })
        .catch(() => {});
    });
  }

  private async ensureInitialized(): Promise<void> {
    if (this.initialized) return;
    try {
      const stored = await this.storage.load();
      this.reminders.clear();
      for (const reminder of stored) {
        this.reminders.set(reminder.noteId, reminder);
      }
    } catch (error) {
      console.warn('[SwReminderManager] Failed to load initial reminders:', error);
      this.reminders.clear();
    } finally {
      this.initialized = true;
    }
  }

  private cancelTimer(noteId: string): void {
    const timerId = this.timers.get(noteId);
    if (timerId != null) {
      this.clearTimeoutFn(timerId);
      this.timers.delete(noteId);
    }
    this.timerTokens.delete(noteId);
  }

  private armTimer(reminder: SwReminder): void {
    if (this.isDestroyed) return;
    this.cancelTimer(reminder.noteId);
    const delay = reminder.fireAt - this.now();
    if (delay <= 0) return;

    const token = this.nextTimerToken++;
    this.timerTokens.set(reminder.noteId, token);
    const epoch = this.lifecycleEpoch;

    const timerId = this.setTimeoutFn(() => {
      if (this.isDestroyed || this.lifecycleEpoch !== epoch) return;
      void this.handleTimerFired(reminder.noteId, token, reminder.fireAt);
    }, Math.min(delay, MAX_TIMER_DELAY_MS));

    this.timers.set(reminder.noteId, timerId);
  }

  private async persistCurrentState(): Promise<void> {
    const list = Array.from(this.reminders.values());
    try {
      this.lastPersistenceError = null;
      await this.storage.save(list);
    } catch (error) {
      this.lastPersistenceError = error instanceof Error ? error : new Error(String(error));
      console.warn('[SwReminderManager] Failed to persist reminders to storage:', error);
    }
  }

  /**
   * Invoked when a timer fires. Validates that the callback's generational token and scheduled
   * timestamp match active state before taking action, discarding stale or canceled callbacks.
   */
  public async handleTimerFired(noteId: string, token?: number, expectedFireAt?: number): Promise<void> {
    const epoch = this.lifecycleEpoch;
    if (this.isDestroyed) return;
    return this.runExclusive(async () => {
      if (this.isDestroyed || this.lifecycleEpoch !== epoch) return;
      await this.ensureInitialized();
      if (this.isDestroyed || this.lifecycleEpoch !== epoch) return;

      const activeToken = this.timerTokens.get(noteId);
      // Guard 1: Verify this callback matches the current timer token for this note
      if (token !== undefined) {
        if (activeToken !== token) {
          return; // Stale or canceled callback; discard
        }
      } else if (activeToken === undefined) {
        return; // No active timer for this note
      }

      // Guard 2: Verify the reminder in memory matches the expected timestamp
      const reminder = this.reminders.get(noteId);
      if (!reminder || (expectedFireAt !== undefined && reminder.fireAt !== expectedFireAt)) {
        return; // Rescheduled or deleted; discard
      }

      this.cancelTimer(noteId);

      const currentTime = this.now();
      if (reminder.fireAt > currentTime) {
        // Delay was clamped (> 24.8 days); re-arm until it is actually due
        this.armTimer(reminder);
        return;
      }

      // At-most-once delivery: remove and commit to storage BEFORE dispatching notification
      this.reminders.delete(noteId);
      await this.persistCurrentState();
      if (this.isDestroyed || this.lifecycleEpoch !== epoch) return;

      try {
        await this.fireNotification(reminder);
      } catch (error) {
        console.warn(`[SwReminderManager] Notification delivery failed for note ${noteId}:`, error);
      }
    });
  }

  /**
   * Syncs the reminder set from the window client.
   * Cancels timers for removed notes, updates upcoming reminders, and commits new state atomically.
   */
  public async syncReminders(incoming: unknown[]): Promise<void> {
    const clean = sanitizeReminders(incoming);
    if (this.isDestroyed) {
      this.isDestroyed = false;
      this.lifecycleEpoch++;
    }
    const epoch = this.lifecycleEpoch;
    return this.runExclusive(async () => {
      if (this.isDestroyed || this.lifecycleEpoch !== epoch) return;
      this.initialized = true; // explicitly superseded by incoming sync
      const activeIds = new Set<string>();
      const currentTime = this.now();

      for (const reminder of clean) {
        // Drop past reminders so stale sync messages cannot resurrect fired reminders
        if (reminder.fireAt <= currentTime) continue;

        activeIds.add(reminder.noteId);
        const existing = this.reminders.get(reminder.noteId);
        if (!existing || existing.fireAt !== reminder.fireAt) {
          this.reminders.set(reminder.noteId, reminder);
          this.armTimer(reminder);
        }
      }

      for (const [noteId] of Array.from(this.reminders.entries())) {
        if (!activeIds.has(noteId)) {
          this.cancelTimer(noteId);
          this.reminders.delete(noteId);
        }
      }

      await this.persistCurrentState();
    });
  }

  /**
   * Fires anything already due and re-arms the rest.
   * Atomic across all due reminders; commits deletion of due reminders before notification firing.
   */
  public async catchUpReminders(): Promise<void> {
    const epoch = this.lifecycleEpoch;
    if (this.isDestroyed) return;
    return this.runExclusive(async () => {
      if (this.isDestroyed || this.lifecycleEpoch !== epoch) return;
      await this.ensureInitialized();
      if (this.isDestroyed || this.lifecycleEpoch !== epoch) return;
      if (this.reminders.size === 0) return;

      const currentTime = this.now();
      const due: SwReminder[] = [];
      const upcoming: SwReminder[] = [];

      for (const reminder of this.reminders.values()) {
        if (reminder.fireAt <= currentTime) {
          due.push(reminder);
        } else {
          upcoming.push(reminder);
        }
      }

      if (due.length > 0) {
        for (const reminder of due) {
          this.cancelTimer(reminder.noteId);
          this.reminders.delete(reminder.noteId);
        }
        await this.persistCurrentState();
        if (this.isDestroyed || this.lifecycleEpoch !== epoch) return;

        for (const reminder of due) {
          if (this.isDestroyed || this.lifecycleEpoch !== epoch) break;
          try {
            await this.fireNotification(reminder);
          } catch (error) {
            console.warn(`[SwReminderManager] Catch-up notification failed for note ${reminder.noteId}:`, error);
          }
        }
      }

      if (this.isDestroyed || this.lifecycleEpoch !== epoch) return;

      for (const reminder of upcoming) {
        if (!this.timers.has(reminder.noteId)) {
          this.armTimer(reminder);
        }
      }
    });
  }

  /**
   * Throttled catch-up pass suitable for fetch event listeners.
   */
  public async catchUpThrottled(): Promise<void> {
    const currentTime = this.now();
    if (currentTime - this.lastCatchUp < this.throttleIntervalMs) return;
    this.lastCatchUp = currentTime;
    return this.catchUpReminders();
  }

  /** Returns snapshot of currently tracked reminders for inspection / testing. */
  public getRemindersSnapshot(): SwReminder[] {
    return Array.from(this.reminders.values()).map((r) => ({ ...r }));
  }

  /** Returns currently active timer noteIds for inspection / testing. */
  public getActiveTimerNoteIds(): string[] {
    return Array.from(this.timers.keys());
  }

  /** Returns the active token for a note's timer (for testing). */
  public getActiveTimerToken(noteId: string): number | undefined {
    return this.timerTokens.get(noteId);
  }

  public getLastPersistenceError(): Error | null {
    return this.lastPersistenceError;
  }

  /**
   * Resolves when all currently queued exclusive operations have completed.
   * Useful in tests and coordinated teardown to avoid race conditions.
   */
  public async whenIdle(): Promise<void> {
    return this.queue;
  }

  /** Tears down all active timers and clears state. */
  public destroy(): void {
    this.isDestroyed = true;
    this.lifecycleEpoch++;
    for (const noteId of Array.from(this.timers.keys())) {
      this.cancelTimer(noteId);
    }
    this.reminders.clear();
  }
}
