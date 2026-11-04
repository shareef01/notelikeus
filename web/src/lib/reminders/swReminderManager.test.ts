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

  afterEach(async () => {
    for (const mgr of activeManagers) {
      mgr.destroy();
      await mgr.whenIdle();
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

  it('discards stale callback when reminder is canceled while timeout is pending (Matrix I)', async () => {
    let capturedCallback: (() => void) | null = null;
    const customSetTimeout = vi.fn((cb: () => void) => {
      capturedCallback = cb;
      return 1234 as any;
    });

    const manager = createManager({
      setTimeoutFn: customSetTimeout,
    });

    const reminder: SwReminder = {
      noteId: '801',
      title: 'Pending Cancel',
      body: 'Will be canceled',
      fireAt: currentTime + 500,
    };

    await manager.syncReminders([reminder]);
    expect(manager.getActiveTimerNoteIds()).toContain('801');
    expect(capturedCallback).toBeTypeOf('function');

    // Step 2: Reminder is canceled (e.g., deleted or cleared in window)
    await manager.syncReminders([]);
    expect(manager.getActiveTimerNoteIds()).not.toContain('801');

    // Step 3: The old callback in the JS macrotask queue executes anyway
    currentTime += 600;
    capturedCallback!();

    // Await exclusive queue completion deterministically
    await manager.whenIdle();

    // Step 4: No canceled notification is delivered
    expect(firedNotifications).toHaveLength(0);
    expect(storage.data).toEqual([]);
  });

  it('discards stale callback when reminder is rescheduled to a later timestamp (Matrix I)', async () => {
    const callbacks: Array<() => void> = [];
    const customSetTimeout = vi.fn((cb: () => void) => {
      callbacks.push(cb);
      return callbacks.length as any;
    });

    const manager = createManager({
      setTimeoutFn: customSetTimeout,
    });

    const t1 = currentTime + 100;
    const t2 = currentTime + 500;

    // Schedule for T1
    await manager.syncReminders([
      { noteId: '802', title: 'Note 1', body: 'Body 1', fireAt: t1 },
    ]);
    expect(callbacks).toHaveLength(1);
    const cb1 = callbacks[0];

    // Reschedule same note to T2 (T2 > T1)
    await manager.syncReminders([
      { noteId: '802', title: 'Note 1', body: 'Body 1 (Updated)', fireAt: t2 },
    ]);
    expect(callbacks).toHaveLength(2);
    const cb2 = callbacks[1];

    // Simulate old timer cb1 firing after time advances past T1
    currentTime = t1 + 10;
    cb1();
    await manager.whenIdle();

    // Must NOT deliver notification for stale cb1
    expect(firedNotifications).toHaveLength(0);
    expect(manager.getRemindersSnapshot()).toHaveLength(1);

    // Now time advances to T2, cb2 fires
    currentTime = t2 + 10;
    cb2();
    await manager.whenIdle();

    // Exactly 1 notification delivered (from cb2)
    expect(firedNotifications).toHaveLength(1);
    expect(firedNotifications[0].noteId).toBe('802');
    expect(firedNotifications[0].body).toBe('You have a note reminder');
  });

  it('discards stale callback when newer sync replaces reminders (Matrix I)', async () => {
    let oldCallback: (() => void) | null = null;
    const customSetTimeout = vi.fn((cb: () => void) => {
      if (!oldCallback) oldCallback = cb;
      return 100 as any;
    });

    const manager = createManager({
      setTimeoutFn: customSetTimeout,
    });

    await manager.syncReminders([
      { noteId: '803', title: 'Old', body: 'Old Body', fireAt: currentTime + 50 },
    ]);

    // Newer sync arrives before timer fires, with completely different notes
    await manager.syncReminders([
      { noteId: '804', title: 'New', body: 'New Body', fireAt: currentTime + 1000 },
    ]);

    // Stale timer for 803 executes
    currentTime += 60;
    oldCallback!();
    await manager.whenIdle();

    expect(firedNotifications).toHaveLength(0);
    expect(manager.getRemindersSnapshot().map((r) => r.noteId)).toEqual(['804']);
  });

  it('records persistence error in lastPersistenceError when storage save fails (Matrix J)', async () => {
    storage.shouldFailSave = true;
    const manager = createManager();

    expect(manager.getLastPersistenceError()).toBeNull();

    await manager.syncReminders([
      { noteId: '805', title: 'Err', body: 'Body', fireAt: currentTime + 100 },
    ]);

    expect(manager.getLastPersistenceError()).toBeInstanceOf(Error);
    expect(manager.getLastPersistenceError()?.message).toContain('Simulated QuotaExceededError');
  });

  describe('Regression Coverage (Tasks 2.A - 2.I)', () => {
    it('2.A: handles destroy while timer callback is queued without firing', async () => {
      let queuedCb: (() => void) | null = null;
      const customSetTimeout = vi.fn((cb: () => void) => {
        queuedCb = cb;
        return 9901 as any;
      });
      const manager = createManager({ setTimeoutFn: customSetTimeout });
      await manager.syncReminders([
        { noteId: '901', title: 'Reminder', body: 'Body 901', fireAt: currentTime + 50 },
      ]);
      expect(queuedCb).toBeTypeOf('function');

      // Destroy before the queued callback executes in macrotask queue
      manager.destroy();
      expect(manager.getRemindersSnapshot()).toHaveLength(0);

      // Callback executes anyway from event loop
      queuedCb!();
      await manager.whenIdle();

      expect(firedNotifications).toHaveLength(0);
    });

    it('2.B: handles destroy while notification operation is awaiting', async () => {
      let resolveNotify: (() => void) | null = null;
      let notifyStarted: (() => void) | null = null;
      const notifyStartedPromise = new Promise<void>((resolve) => {
        notifyStarted = resolve;
      });
      const notifyPromise = new Promise<void>((resolve) => {
        resolveNotify = resolve;
      });

      const delayedNotification = vi.fn(async (reminder: SwReminder) => {
        notifyStarted?.();
        await notifyPromise;
        firedNotifications.push(reminder);
      });

      const manager = createManager({ fireNotification: delayedNotification });
      await manager.syncReminders([
        { noteId: '902', title: 'Reminder', body: 'Body 902', fireAt: currentTime + 10 },
      ]);

      // Fire timer
      currentTime += 20;
      const timerPromise = manager.handleTimerFired('902');
      await notifyStartedPromise;

      // Destroy manager while notifyPromise is still pending
      manager.destroy();

      // Now resolve the notification promise
      resolveNotify!();
      await timerPromise;
      await manager.whenIdle();

      // Manager destroyed must not re-arm or crash
      expect(manager.getActiveTimerNoteIds()).toHaveLength(0);
    });

    it('2.C: handles reschedule while previous timer is firing', async () => {
      let resolveSave: () => void = () => {};
      let saveStarted: () => void = () => {};
      const saveStartedPromise = new Promise<void>((resolve) => {
        saveStarted = resolve;
      });
      const originalSave = storage.save.bind(storage);
      let interceptSave = false;

      storage.save = async (reminders) => {
        if (interceptSave) {
          interceptSave = false;
          saveStarted();
          await new Promise<void>((res) => {
            resolveSave = res;
          });
        }
        return originalSave(reminders);
      };

      const manager = createManager();
      await manager.syncReminders([
        { noteId: '903', title: 'Reminder', body: 'Body 903', fireAt: currentTime + 50 },
      ]);

      currentTime += 60;
      interceptSave = true;

      // Start firing timer for 903 (pauses inside storage.save)
      const firePromise = manager.handleTimerFired('903');
      await saveStartedPromise;

      // While firing is paused, window reschedules 903 to future
      const reschedulePromise = manager.syncReminders([
        { noteId: '903', title: 'Reminder', body: 'Body 903', fireAt: currentTime + 500 },
      ]);

      // Unblock the initial timer
      resolveSave();
      await Promise.all([firePromise, reschedulePromise]);
      await manager.whenIdle();

      // Storage and active timer reflect the rescheduled state cleanly
      expect(manager.getRemindersSnapshot()).toHaveLength(1);
      expect(manager.getRemindersSnapshot()[0]!.fireAt).toBe(currentTime + 500);
      expect(manager.getActiveTimerNoteIds()).toContain('903');
    });

    it('2.D: handles multiple concurrent timer events without duplicate firing or lost updates', async () => {
      const manager = createManager();
      const reminders: SwReminder[] = [
        { noteId: '9041', title: 'Reminder', body: 'B1', fireAt: currentTime + 10 },
        { noteId: '9042', title: 'Reminder', body: 'B2', fireAt: currentTime + 10 },
        { noteId: '9043', title: 'Reminder', body: 'B3', fireAt: currentTime + 10 },
        { noteId: '9044', title: 'Reminder', body: 'B4', fireAt: currentTime + 10 },
        { noteId: '9045', title: 'Reminder', body: 'B5', fireAt: currentTime + 10 },
      ];
      await manager.syncReminders(reminders);

      currentTime += 20;
      // Fire all 5 simultaneously
      const fires = reminders.map((r) => manager.handleTimerFired(r.noteId));
      await Promise.all(fires);
      await manager.whenIdle();

      expect(firedNotifications).toHaveLength(5);
      const firedIds = firedNotifications.map((r) => r.noteId).sort();
      expect(firedIds).toEqual(['9041', '9042', '9043', '9044', '9045']);
      expect(storage.data).toEqual([]);
      expect(manager.getRemindersSnapshot()).toEqual([]);
    });

    it('2.E: discards notification when reminder is removed while callback is pending', async () => {
      let timerCb: (() => void) | null = null;
      const customSetTimeout = vi.fn((cb: () => void) => {
        timerCb = cb;
        return 9905 as any;
      });
      const manager = createManager({ setTimeoutFn: customSetTimeout });
      await manager.syncReminders([
        { noteId: '905', title: 'Reminder', body: 'Body 905', fireAt: currentTime + 100 },
      ]);

      // Remove reminder via empty sync
      await manager.syncReminders([]);
      expect(manager.getActiveTimerNoteIds()).toHaveLength(0);

      // Pending callback fires
      currentTime += 110;
      timerCb!();
      await manager.whenIdle();

      expect(firedNotifications).toHaveLength(0);
    });

    it('2.F: discards stale callback after reminder replacement', async () => {
      const callbacks: Array<() => void> = [];
      const customSetTimeout = vi.fn((cb: () => void) => {
        callbacks.push(cb);
        return callbacks.length as any;
      });
      const manager = createManager({ setTimeoutFn: customSetTimeout });

      await manager.syncReminders([
        { noteId: '906', title: 'Reminder', body: 'Old', fireAt: currentTime + 100 },
      ]);
      const oldCb = callbacks[0]!;

      // Replace reminder with later timestamp
      const newFireAt = currentTime + 300;
      await manager.syncReminders([
        { noteId: '906', title: 'Reminder', body: 'New', fireAt: newFireAt },
      ]);
      expect(callbacks).toHaveLength(2);

      // Fire old callback
      currentTime += 110;
      oldCb();
      await manager.whenIdle();

      expect(firedNotifications).toHaveLength(0);
      expect(manager.getRemindersSnapshot()[0]!.fireAt).toBe(newFireAt);
    });

    it('2.G: supports reinitialization after destroy without state leak', async () => {
      const manager = createManager();
      await manager.syncReminders([
        { noteId: '9071', title: 'Reminder', body: 'Body', fireAt: currentTime + 100 },
      ]);
      manager.destroy();
      await manager.whenIdle();

      expect(manager.getRemindersSnapshot()).toHaveLength(0);
      expect(manager.getActiveTimerNoteIds()).toHaveLength(0);

      // Reinitialize same manager instance with fresh sync
      await manager.syncReminders([
        { noteId: '9072', title: 'Reminder', body: 'Body', fireAt: currentTime + 200 },
      ]);

      expect(manager.getRemindersSnapshot().map((r) => r.noteId)).toEqual(['9072']);
      expect(manager.getActiveTimerNoteIds()).toEqual(['9072']);
    });

    it('2.H: recovers cleanly when promise rejects during timer processing', async () => {
      const failingNotify = vi.fn(async () => {
        throw new Error('Push service transient failure');
      });
      const manager = createManager({ fireNotification: failingNotify });

      await manager.syncReminders([
        { noteId: '9081', title: 'Reminder', body: 'B', fireAt: currentTime + 10 },
        { noteId: '9082', title: 'Reminder', body: 'B', fireAt: currentTime + 50 },
      ]);

      currentTime += 20;
      await manager.handleTimerFired('9081');
      await manager.whenIdle();

      expect(failingNotify).toHaveBeenCalledTimes(1);
      // Reminders list must still have removed 9081 and preserved 9082
      expect(manager.getRemindersSnapshot().map((r) => r.noteId)).toEqual(['9082']);

      // Subsequent operation proceeds without deadlock
      currentTime += 40;
      await manager.handleTimerFired('9082');
      await manager.whenIdle();
      expect(manager.getRemindersSnapshot()).toHaveLength(0);
    });

    it('2.I: tears down cleanly in afterEach even with outstanding asynchronous work', async () => {
      const slowStorage: SwReminderStorage = {
        load: async () => [],
        save: async () => {
          await new Promise((res) => setTimeout(res, 50));
        },
      };

      const manager = createManager({ storage: slowStorage });
      // Queue async work
      void manager.syncReminders([
        { noteId: '909', title: 'Reminder', body: 'Body', fireAt: currentTime + 100 },
      ]);

      // Destroy immediately while save is pending
      manager.destroy();
      await manager.whenIdle();

      // State is torn down cleanly
      expect(manager.getRemindersSnapshot()).toHaveLength(0);
      expect(manager.getActiveTimerNoteIds()).toHaveLength(0);
    });
  });
});
