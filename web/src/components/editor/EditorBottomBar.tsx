interface EditorBottomBarProps {
  timestamp: number;
  isSaving: boolean;
  contentColor: string;
  reminderTimestamp?: number | null;
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
  contentColor,
  reminderTimestamp = null,
  onMoreClick,
}: EditorBottomBarProps) {
  const editedLabel = new Intl.DateTimeFormat(undefined, {
    hour: 'numeric',
    minute: '2-digit',
  }).format(new Date(timestamp));
  const reminderLabel = formatReminderLabel(reminderTimestamp);

  const nearLimit = contentLength != null && contentLength >= CHAR_WARN_THRESHOLD;
  const atLimit = contentLength != null && contentLength >= MAX_CONTENT;

  return (
    <footer
      className="flex items-center justify-between px-3 pb-safe pt-2"
      style={{ color: contentColor }}
    >
      <div className="flex-1" />
      <div className="text-center text-xs font-medium opacity-70">
        <div>{isSaving ? 'Saving…' : `Edited ${editedLabel}`}</div>
        {reminderLabel ? (
          <div className="mt-0.5 font-semibold opacity-90">{reminderLabel}</div>
        ) : null}
      </div>
      <div className="flex flex-1 justify-end">
        <button
          type="button"
          onClick={onMoreClick}
          className="flex size-10 items-center justify-center rounded-full note-surface-hover"
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
