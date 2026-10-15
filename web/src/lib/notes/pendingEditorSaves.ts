/**
 * Which open editors hold an edit that is not in IndexedDB yet: a debounced autosave waiting to
 * fire, or a save still running. The service-worker update waits on this before it reloads the
 * page, because the editor's flush on `pagehide` is async (seal, then write) and a reload does not
 * wait for it, so reloading mid-typing could drop the last second of an edit (N-08).
 */
const pending = new Set<object>();
let waiters: (() => void)[] = [];

export function markEditorSavePending(editor: object): void {
  pending.add(editor);
}

export function markEditorSaveSettled(editor: object): void {
  if (!pending.delete(editor) || pending.size > 0) return;
  const settled = waiters;
  waiters = [];
  for (const resolve of settled) resolve();
}

export function hasPendingEditorSaves(): boolean {
  return pending.size > 0;
}

/** Resolves once no editor holds an unsaved edit; at once when none does. */
export function whenEditorSavesSettled(): Promise<void> {
  if (pending.size === 0) return Promise.resolve();
  return new Promise((resolve) => waiters.push(resolve));
}
