interface EditorBottomBarProps {
  timestamp: number;
  isSaving: boolean;
  persistStatus?: 'idle' | 'saving' | 'saved-local' | 'attachment-pending' | 'synced' | 'error';
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

function persistLabel(
  isSaving: boolean,
  persistStatus: EditorBottomBarProps['persistStatus'],
  editedLabel: string,
): string {
  if (isSaving || persistStatus === 'saving') return 'Saving…';
  switch (persistStatus) {
    case 'synced':
      return `Synced · Edited ${editedLabel}`;
    case 'saved-local':
      return `Saved locally · Edited ${editedLabel}`;
    case 'attachment-pending':
      return `Attachment pending upload · Edited ${editedLabel}`;
    case 'error':
      return `Sync error · Edited ${editedLabel}`;
    default:
      return `Edited ${editedLabel}`;
  }
}

export function EditorBottomBar({
  timestamp,
  isSaving,
  persistStatus = 'idle',
  contentColor,
  reminderTimestamp = null,
  onMoreClick,
}: EditorBottomBarProps) {
  const editedLabel = new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  }).format(new Date(timestamp));
  const reminderLabel = formatReminderLabel(reminderTimestamp);

  return (
    <footer
      className="flex items-center justify-between px-2 pb-safe pt-2"
      style={{ color: contentColor }}
    >
      <div className="flex-1" />
      <div className="max-w-[min(100%,80ch)] text-center text-xs font-medium opacity-80">
        <div>{persistLabel(isSaving, persistStatus, editedLabel)}</div>
        {reminderLabel ? (
          <div className="mt-0.5 font-semibold opacity-90">{reminderLabel}</div>
        ) : null}
      </div>
      <div className="flex flex-1 justify-end">
        <button
          type="button"
          onClick={onMoreClick}
          className="flex size-11 items-center justify-center rounded-full hover:bg-[color-mix(in_srgb,currentColor_10%,transparent)]"
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
