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

export class SwReminderManager {
  private readonly storage: SwReminderStorage;
  private readonly fireNotification: (reminder: SwReminder) => Promise<void>;
  private readonly now: () => number;
  private readonly setTimeoutFn: (callback: () => void, delayMs: number) => ReturnType<typeof setTimeout>;
  private readonly clearTimeoutFn: (timerId: ReturnType<typeof setTimeout>) => void;
  private readonly throttleIntervalMs: number;

  private reminders = new Map<string, SwReminder>();
  private timers = new Map<string, ReturnType<typeof setTimeout>>();
  private initialized = false;
  private lastCatchUp = 0;
  private queue: Promise<void> = Promise.resolve();

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
  }

  private armTimer(reminder: SwReminder): void {
    this.cancelTimer(reminder.noteId);
    const delay = reminder.fireAt - this.now();
    if (delay <= 0) return;

    const timerId = this.setTimeoutFn(() => {
      void this.handleTimerFired(reminder.noteId);
    }, Math.min(delay, MAX_TIMER_DELAY_MS));

    this.timers.set(reminder.noteId, timerId);
  }

  private async persistCurrentState(): Promise<void> {
    const list = Array.from(this.reminders.values());
    try {
      await this.storage.save(list);
    } catch (error) {
      console.warn('[SwReminderManager] Failed to persist reminders:', error);
    }
  }

  private async handleTimerFired(noteId: string): Promise<void> {
    return this.runExclusive(async () => {
      await this.ensureInitialized();
      this.cancelTimer(noteId);
      const reminder = this.reminders.get(noteId);
      if (!reminder) return;

      const currentTime = this.now();
      if (reminder.fireAt > currentTime) {
        // Delay was clamped; re-arm until it is actually due
        this.armTimer(reminder);
        return;
      }

      // Remove from state and commit to storage before firing to prevent duplicate delivery
      this.reminders.delete(noteId);
      await this.persistCurrentState();

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
    return this.runExclusive(async () => {
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
    return this.runExclusive(async () => {
      await this.ensureInitialized();
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

        for (const reminder of due) {
          try {
            await this.fireNotification(reminder);
          } catch (error) {
            console.warn(`[SwReminderManager] Catch-up notification failed for note ${reminder.noteId}:`, error);
          }
        }
      }

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

  /** Tears down all active timers and clears state. */
  public destroy(): void {
    for (const noteId of Array.from(this.timers.keys())) {
      this.cancelTimer(noteId);
    }
    this.reminders.clear();
  }
}
