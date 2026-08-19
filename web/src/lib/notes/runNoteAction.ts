import { useToastStore } from '@/store/toastStore';

/**
 * Runs a note mutation and reports a failure instead of losing it.
 *
 * Every note action resolves through Firestore, and the UI invokes them from `void`-ed event
 * handlers, so a rejection had nowhere to go: the note stayed where it was while the handler's
 * success toast (often with an Undo for an action that never happened) was the only feedback.
 * Resolves to whether the action succeeded, for callers whose follow-up work — an undo toast,
 * clearing a selection — is only correct if the write landed.
 */
export async function runNoteAction(label: string, action: () => Promise<void>): Promise<boolean> {
  try {
    await action();
    return true;
  } catch (error) {
    console.warn(`[Notelikeus] ${label} failed:`, error);
    useToastStore
      .getState()
      .show(`${label} failed. Check your connection and try again.`, 'error');
    return false;
  }
}
