import { useToastStore } from '@/store/toastStore';

export type ShareOutcome =
  | 'shared'
  | 'cancelled'
  | 'copied'
  | 'copy-failed'
  | 'unsupported'
  | 'empty';

interface ShareRequest {
  title: string;
  text: string;
}

/**
 * Shares note text through the Web Share API, falling back to the clipboard.
 *
 * Every branch resolves to a stated outcome. The previous inline version awaited
 * `clipboard.writeText` without a catch, so a denied clipboard permission surfaced as an
 * unhandled rejection and the user was told nothing at all — while a browser with no clipboard
 * API silently did nothing. Success is only reported once the write has actually resolved.
 */
export async function shareText({ title, text }: ShareRequest): Promise<ShareOutcome> {
  if (!text.trim()) return 'empty';
  if (typeof navigator === 'undefined') return 'unsupported';

  if (navigator.share) {
    try {
      await navigator.share({ title, text });
      return 'shared';
    } catch (error) {
      // The user closing the share sheet is a completed interaction, not a failure to report.
      if ((error as Error)?.name === 'AbortError') return 'cancelled';
      // Anything else (no handler, transient platform error) falls through to the clipboard.
    }
  }

  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      useToastStore.getState().show('Note copied to clipboard');
      return 'copied';
    } catch {
      useToastStore.getState().show('Could not copy this note', 'error');
      return 'copy-failed';
    }
  }

  useToastStore.getState().show('Sharing is not available in this browser', 'error');
  return 'unsupported';
}
