interface EditorBottomBarProps {
  timestamp: number;
  isSaving: boolean;
  saveFailed?: boolean;
  isSavedLocally?: boolean;
  isSignedIn?: boolean;
  isOnline?: boolean;
  hasPendingAttachments?: boolean;
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
  isSignedIn = false,
  isOnline = true,
  hasPendingAttachments = false,
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

  let statusText = `Saved locally • ${editedLabel}`;
  let isError = false;

  if (saveFailed) {
    statusText = 'Local save failed';
    isError = true;
  } else if (isSaving) {
    statusText = 'Saving locally…';
  } else if (!isSignedIn) {
    statusText = `Saved locally • ${editedLabel}`;
  } else if (!isOnline) {
    statusText = 'Saved locally • Offline';
  } else if (hasPendingAttachments) {
    statusText = 'Saved locally • Sync pending';
  } else {
    statusText = 'Saved locally • Synced';
  }

  return (
    <footer
      className="relative z-10 flex shrink-0 items-center justify-between px-2 pb-safe-action pt-2 sm:px-3 lg:px-4"
      style={{ color: contentColor }}
    >
      <div className="flex-1" />
      <div className="text-center text-xs font-medium">
        {isError ? (
          <div className="flex items-center justify-center gap-1 text-red-500 dark:text-red-400">
            <span>{statusText}</span>
            {onRetrySave ? (
              <button
                type="button"
                onClick={onRetrySave}
                className="underline underline-offset-2 hover:opacity-80 font-semibold ml-1 cursor-pointer"
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
          className="flex size-10 items-center justify-center rounded-full hover:bg-[color-mix(in_srgb,currentColor_10%,transparent)]"
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

