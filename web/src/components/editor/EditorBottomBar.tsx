interface EditorBottomBarProps {
  timestamp: number;
  isSaving: boolean;
  contentColor: string;
  contentLength?: number;
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

const MAX_CONTENT = 500_000;
const CHAR_WARN_THRESHOLD = 450_000;

function formatCount(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`;
  return String(n);
}

export function EditorBottomBar({
  timestamp,
  isSaving,
  contentColor,
  contentLength,
  reminderTimestamp,
  onMoreClick,
}: EditorBottomBarProps) {
  const editedLabel = new Intl.DateTimeFormat(undefined, {
    hour: 'numeric',
    minute: '2-digit',
  }).format(new Date(timestamp));

  const nearLimit = contentLength != null && contentLength >= CHAR_WARN_THRESHOLD;
  const atLimit = contentLength != null && contentLength >= MAX_CONTENT;

  return (
    <footer
      className="flex items-center justify-between px-3 pb-safe pt-2"
      style={{ color: contentColor }}
    >
      <div className="flex-1" />
      <div className="text-center">
        {reminderTimestamp ? (
          <span className="block text-[11px] font-medium opacity-60">Reminder {formatReminder(reminderTimestamp)}</span>
        ) : null}
        <span className={`text-[11px] font-medium opacity-50 ${reminderTimestamp ? 'mt-0.5 block' : ''}`}>
          {isSaving ? (
            <span className="flex items-center gap-1.5">
              <span className="inline-block size-2.5 rounded-full bg-current animate-pulse" />
              Saving
            </span>
          ) : (
            `Edited ${editedLabel}`
          )}
        </span>
        {contentLength != null && contentLength > 0 && (
          <span
            className={`text-[10px] mt-0.5 block font-medium ${
              atLimit ? 'text-red-400 opacity-90' : nearLimit ? 'text-amber-400 opacity-80' : 'opacity-40'
            }`}
          >
            {formatCount(contentLength)} / {formatCount(MAX_CONTENT)}
            {atLimit ? ' — limit reached' : ''}
          </span>
        )}
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
