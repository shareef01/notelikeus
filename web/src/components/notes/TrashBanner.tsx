import { CHROME_FOCUS } from '@/lib/ui/focusStyles';

interface TrashBannerProps {
  onEmptyTrash: () => void;
}

export function TrashBanner({ onEmptyTrash }: TrashBannerProps) {
  return (
    <div className="border-b border-red-700/20 bg-red-500/10 px-shell py-3 dark:border-red-900/30 dark:bg-red-950/25">
      <div className="mx-auto flex max-w-content items-center justify-between gap-4">
        <p className="min-w-0 text-sm text-red-900 dark:text-red-100/80">
          Notes in trash are removed permanently when you empty trash.
        </p>
        <button
          type="button"
          onClick={onEmptyTrash}
          className={`min-h-11 shrink-0 rounded-note px-3 py-2 text-sm font-semibold text-red-700 hover:bg-red-500/10 dark:text-red-300 dark:hover:bg-red-950/50 ${CHROME_FOCUS}`}
        >
          Empty trash
        </button>
      </div>
    </div>
  );
}
