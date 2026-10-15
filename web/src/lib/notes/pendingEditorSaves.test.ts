import { describe, expect, it } from 'vitest';
import {
  hasPendingEditorSaves,
  markEditorSavePending,
  markEditorSaveSettled,
  whenEditorSavesSettled,
} from '@/lib/notes/pendingEditorSaves';

describe('pendingEditorSaves', () => {
  it('resolves at once when no editor is saving', async () => {
    await expect(whenEditorSavesSettled()).resolves.toBeUndefined();
  });

  it('waits for every editor, not just the first to settle', async () => {
    const a = {};
    const b = {};
    markEditorSavePending(a);
    markEditorSavePending(b);
    let settled = false;
    const waiting = whenEditorSavesSettled().then(() => {
      settled = true;
    });

    markEditorSaveSettled(a);
    await Promise.resolve();
    expect(settled).toBe(false);
    expect(hasPendingEditorSaves()).toBe(true);

    markEditorSaveSettled(b);
    await waiting;
    expect(settled).toBe(true);
    expect(hasPendingEditorSaves()).toBe(false);
  });
});
