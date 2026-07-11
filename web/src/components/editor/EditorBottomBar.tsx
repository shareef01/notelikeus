interface EditorBottomBarProps {
  timestamp: number;
  isSaving: boolean;
  contentColor: string;
  reminderTimestamp?: number | null;
  onMoreClick: () => void;
}

function formatReminder(timestamp: number): string {
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  }).format(new Date(timestamp));
}

export function EditorBottomBar({
  timestamp,
  isSaving,
  contentColor,
  reminderTimestamp,
  onMoreClick,
}: EditorBottomBarProps) {
  const editedLabel = new Intl.DateTimeFormat(undefined, {
    hour: 'numeric',
    minute: '2-digit',
  }).format(new Date(timestamp));

  return (
    <footer
      className="flex items-center justify-between px-2 pb-safe pt-2"
      style={{ color: contentColor }}
    >
      <div className="flex-1" />
      <div className="text-center text-xs font-medium opacity-70">
        {reminderTimestamp ? (
          <span className="block">Reminder {formatReminder(reminderTimestamp)}</span>
        ) : null}
        <span className={reminderTimestamp ? 'mt-0.5 block opacity-80' : undefined}>
          {isSaving ? 'Saving…' : `Edited ${editedLabel}`}
        </span>
      </div>
      <div className="flex flex-1 justify-end">
        <button
          type="button"
          onClick={onMoreClick}
          className="flex size-10 items-center justify-center rounded-full hover:bg-black/10"
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
