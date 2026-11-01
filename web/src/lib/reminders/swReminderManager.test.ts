import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { SwReminderManager, MAX_TIMER_DELAY_MS, type SwReminderStorage } from './swReminderManager';
import type { SwReminder } from './swReminderPayload';

class InMemoryStorage implements SwReminderStorage {
  public data: SwReminder[] = [];
  public saveCallCount = 0;
  public loadCallCount = 0;
  public shouldFailSave = false;

  async load(): Promise<SwReminder[]> {
    this.loadCallCount++;
    return this.data.map((r) => ({ ...r }));
  }

  async save(reminders: SwReminder[]): Promise<void> {
    this.saveCallCount++;
    if (this.shouldFailSave) {
      throw new Error('Simulated QuotaExceededError on cache.put');
    }
    // Simulate non-zero I/O delay to expose race conditions
    await new Promise((resolve) => setTimeout(resolve, 5));
    this.data = reminders.map((r) => ({ ...r }));
  }
}

describe('SwReminderManager Concurrency and Resilience', () => {
  let storage: InMemoryStorage;
  let firedNotifications: SwReminder[];
  let fireNotificationMock: (reminder: SwReminder) => Promise<void>;
  let currentTime: number;
  let activeManagers: SwReminderManager[];

  const createManager = (options?: Partial<ConstructorParameters<typeof SwReminderManager>[0]>) => {
    const mgr = new SwReminderManager({
      storage,
      fireNotification: fireNotificationMock,
      now: () => currentTime,
      ...options,
    });
    activeManagers.push(mgr);
    return mgr;
  };

  beforeEach(() => {
    storage = new InMemoryStorage();
    firedNotifications = [];
    activeManagers = [];
    currentTime = 1_000_000;
    fireNotificationMock = vi.fn(async (reminder: SwReminder) => {
      // Simulate non-zero delay in notification dispatch
      await new Promise((resolve) => setTimeout(resolve, 2));
      firedNotifications.push(reminder);
    });
  });

  afterEach(() => {
    for (const mgr of activeManagers) {
      mgr.destroy();
    }
    activeManagers = [];
  });

  it('handles simultaneous reminders without lost updates or resurrection', async () => {
    const manager = createManager();

    const reminder1: SwReminder = {
      noteId: '101',
      title: 'Reminder',
      body: 'You have a note reminder',
      fireAt: currentTime + 50,
    };
    const reminder2: SwReminder = {
      noteId: '102',
      title: 'Reminder',
      body: 'You have a note reminder',
      fireAt: currentTime + 50,
    };

    await manager.syncReminders([reminder1, reminder2]);
    expect(storage.data).toHaveLength(2);

    // Fast-forward time past both reminders
    currentTime += 60;

    // Both timers fire concurrently
    const handleTimer1 = (manager as any).handleTimerFired('101');
    const handleTimer2 = (manager as any).handleTimerFired('102');

    await Promise.all([handleTimer1, handleTimer2]);

    expect(firedNotifications).toHaveLength(2);
    expect(firedNotifications.map((r) => r.noteId).sort()).toEqual(['101', '102']);

    // Critical assertion: Neither reminder resurrected in storage
    expect(storage.data).toEqual([]);
    expect(manager.getRemindersSnapshot()).toEqual([]);
  });

  it('preserves concurrent window sync without being overwritten by timer completion', async () => {
    const manager = createManager();

    const oldReminder: SwReminder = {
      noteId: '201',
      title: 'Reminder',
      body: 'You have a note reminder',
      fireAt: currentTime + 10,
    };

    await manager.syncReminders([oldReminder]);

    // Timer expires for oldReminder
    currentTime += 20;

    // Concurrent execution: timer fires while window client syncs a new reminder
    const timerFiring = (manager as any).handleTimerFired('201');

    const newReminder: SwReminder = {
      noteId: '202',
      title: 'Reminder',
      body: 'You have a note reminder',
      fireAt: currentTime + 500,
    };
    const incomingSync = manager.syncReminders([newReminder]);

    await Promise.all([timerFiring, incomingSync]);

    expect(firedNotifications).toHaveLength(1);
    expect(firedNotifications[0].noteId).toBe('201');

    // Storage must contain 202 and must NOT contain 201
    expect(storage.data.map((r) => r.noteId)).toEqual(['202']);
    expect(manager.getRemindersSnapshot().map((r) => r.noteId)).toEqual(['202']);
  });

  it('prevents duplicate notifications when catchUp races an expiring timer', async () => {
    const manager = createManager();

    const reminder: SwReminder = {
      noteId: '301',
      title: 'Reminder',
      body: 'You have a note reminder',
      fireAt: currentTime + 10,
    };

    await manager.syncReminders([reminder]);
    currentTime += 20;

    // Concurrently trigger catchUp and timer callback
    const catchUp = manager.catchUpReminders();
    const timerFired = (manager as any).handleTimerFired('301');

    await Promise.all([catchUp, timerFired]);

    // Notification must only fire ONCE
    expect(firedNotifications).toHaveLength(1);
    expect(firedNotifications[0].noteId).toBe('301');
    expect(storage.data).toEqual([]);
  });

  it('drops reminder cleanly from storage even if showNotification rejects', async () => {
    const failingNotificationMock = vi.fn(async () => {
      throw new Error('Notification permission revoked by user');
    });

    const manager = createManager({
      fireNotification: failingNotificationMock,
    });

    const reminder: SwReminder = {
      noteId: '401',
      title: 'Reminder',
      body: 'You have a note reminder',
      fireAt: currentTime + 10,
    };

    await manager.syncReminders([reminder]);
    currentTime += 20;

    // Trigger timer
    await (manager as any).handleTimerFired('401');

    expect(failingNotificationMock).toHaveBeenCalledTimes(1);
    // Reminder must be removed from storage and not retried forever
    expect(storage.data).toEqual([]);
    expect(manager.getRemindersSnapshot()).toEqual([]);
  });

  it('does not crash or jam queue when storage.save fails', async () => {
    storage.shouldFailSave = true;

    const manager = createManager();

    const reminder: SwReminder = {
      noteId: '501',
      title: 'Reminder',
      body: 'You have a note reminder',
      fireAt: currentTime + 10,
    };

    // Sync should complete without throwing unhandled exception
    await expect(manager.syncReminders([reminder])).resolves.toBeUndefined();
    expect(manager.getRemindersSnapshot()).toHaveLength(1);

    // Subsequent operation still works cleanly through queue
    currentTime += 20;
    await expect((manager as any).handleTimerFired('501')).resolves.toBeUndefined();
    expect(firedNotifications).toHaveLength(1);
  });

  it('simulates service worker restart: loads stored reminders and catches up correctly', async () => {
    // 1st SW instance schedules reminders
    const sw1 = createManager();

    await sw1.syncReminders([
      { noteId: '601', title: 'Reminder', body: 'body', fireAt: currentTime + 100 }, // will be due
      { noteId: '602', title: 'Reminder', body: 'body', fireAt: currentTime + 5000 }, // will be upcoming
    ]);

    expect(storage.data).toHaveLength(2);
    sw1.destroy(); // SW is terminated by browser

    // Time passes while SW is sleeping
    currentTime += 200;

    // 2nd SW instance boots (wake-up on fetch or activate)
    const sw2 = createManager();

    // Worker activates and runs catchUpReminders()
    await sw2.catchUpReminders();

    // 601 was due and fired
    expect(firedNotifications).toHaveLength(1);
    expect(firedNotifications[0].noteId).toBe('601');

    // 602 is still upcoming and armed in sw2
    expect(sw2.getRemindersSnapshot().map((r) => r.noteId)).toEqual(['602']);
    expect(sw2.getActiveTimerNoteIds()).toContain('602');
    expect(storage.data.map((r) => r.noteId)).toEqual(['602']);
  });

  it('re-arms reminders exceeding 32-bit delay clamp (> 24.8 days)', async () => {
    let capturedDelay: number | null = null;
    const customSetTimeout = vi.fn((_cb: () => void, delayMs: number) => {
      capturedDelay = delayMs;
      return 999 as any;
    });

    const manager = createManager({
      setTimeoutFn: customSetTimeout,
    });

    const farFutureReminder: SwReminder = {
      noteId: '701',
      title: 'Reminder',
      body: 'body',
      fireAt: currentTime + MAX_TIMER_DELAY_MS + 10_000,
    };

    await manager.syncReminders([farFutureReminder]);

    // Timer delay should be clamped to MAX_TIMER_DELAY_MS
    expect(capturedDelay).toBe(MAX_TIMER_DELAY_MS);

    // Simulate clamped timer firing early
    currentTime += MAX_TIMER_DELAY_MS;
    await (manager as any).handleTimerFired('701');

    // Notification must NOT fire yet
    expect(firedNotifications).toHaveLength(0);

    // Reminder must be re-armed with the remaining delay
    expect(manager.getRemindersSnapshot()).toHaveLength(1);
    expect(capturedDelay).toBe(10_000);
  });

  it('throttles repeated catch-up calls from rapid navigation fetch events', async () => {
    const manager = createManager({
      throttleIntervalMs: 30_000,
    });

    const catchUpSpy = vi.spyOn(manager, 'catchUpReminders');

    await manager.catchUpThrottled();
    expect(catchUpSpy).toHaveBeenCalledTimes(1);

    // Rapid successive requests within 30 seconds
    currentTime += 5_000;
    await manager.catchUpThrottled();
    currentTime += 10_000;
    await manager.catchUpThrottled();
    expect(catchUpSpy).toHaveBeenCalledTimes(1);

    // After throttle interval passes
    currentTime += 20_000; // total 35s
    await manager.catchUpThrottled();
    expect(catchUpSpy).toHaveBeenCalledTimes(2);
  });
});
