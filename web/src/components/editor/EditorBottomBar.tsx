import { NOTE_SYNC_LABELS, noteSyncStatus, showsEditedTime } from '@/lib/sync/noteSyncStatus';

/** The footer sits on the note's own colour, so its focus ring follows the text colour. */
const FOOTER_FOCUS =
  'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-current';

interface EditorBottomBarProps {
  timestamp: number;
  isSaving: boolean;
  saveFailed?: boolean;
  isSavedLocally?: boolean;
  isSignedIn?: boolean;
  isOnline?: boolean;
  /** The note holds an edit or an image the cloud has not confirmed. */
  isPending?: boolean;
  /** The account's queue is being pushed right now. */
  isFlushing?: boolean;
  /** The account's last push left edits behind. */
  flushFailed?: boolean;
  contentColor: string;
  reminderTimestamp?: number | null;
  onRetrySave?: () => void;
  onMoreClick: () => void;
}

function formatReminderLabel(timestamp: number | null | undefined): string | null {
  if (timestamp == null) return null;
  const date = new Date(timestamp);
  return `Reminder ${new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  }).format(date)}`;
}

export function EditorBottomBar({
  timestamp,
  isSaving,
  saveFailed = false,
  isSavedLocally = false,
  isSignedIn = false,
  isOnline = true,
  isPending = false,
  isFlushing = false,
  flushFailed = false,
  contentColor,
  reminderTimestamp = null,
  onRetrySave,
  onMoreClick,
}: EditorBottomBarProps) {
  const editedLabel = new Intl.DateTimeFormat(undefined, {
    hour: 'numeric',
    minute: '2-digit',
  }).format(new Date(timestamp));
  const reminderLabel = formatReminderLabel(reminderTimestamp);

  const status = noteSyncStatus({
    saveFailed,
    isSaving,
    isSavedLocally,
    isSignedIn,
    isOnline,
    isPending,
    isFlushing,
    flushFailed,
  });
  const label = NOTE_SYNC_LABELS[status];
  const statusText = showsEditedTime(status) ? `${label} • ${editedLabel}` : label;
  const isError = status === 'save-failed';

  return (
    <footer
      className="relative z-10 flex shrink-0 items-center justify-between px-2 pb-safe-action pt-2 sm:px-3 lg:px-4"
      style={{ color: contentColor }}
    >
      <div className="flex-1" />
      <div className="text-center text-xs font-medium" role="status" aria-live="polite">
        {isError ? (
          <div className="flex items-center justify-center gap-1 text-red-700 dark:text-red-400">
            <span>{statusText}</span>
            {onRetrySave ? (
              <button
                type="button"
                onClick={onRetrySave}
                className={`-my-2 ml-1 min-h-10 cursor-pointer rounded px-2 font-semibold underline underline-offset-2 hover:opacity-80 ${FOOTER_FOCUS}`}
              >
                Retry
              </button>
            ) : null}
          </div>
        ) : (
          <div className="opacity-75 tracking-tight flex items-center justify-center gap-1.5">
            <span>{statusText}</span>
          </div>
        )}
        {reminderLabel ? (
          <div className="mt-0.5 font-semibold opacity-90">{reminderLabel}</div>
        ) : null}
      </div>
      <div className="flex flex-1 justify-end">
        <button
          type="button"
          onClick={onMoreClick}
          className={`flex size-10 items-center justify-center rounded-full hover:bg-[color-mix(in_srgb,currentColor_10%,transparent)] ${FOOTER_FOCUS}`}
          aria-label="More options"
        >
          <svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor" aria-hidden>
            <path d="M12 8a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm0 6a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm0 6a2 2 0 1 0 0-4 2 2 0 0 0 0 4z" />
          </svg>
        </button>
      </div>
    </footer>
  );
}

