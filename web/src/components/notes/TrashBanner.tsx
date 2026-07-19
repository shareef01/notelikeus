interface TrashBannerProps {
  onEmptyTrash: () => void;
}

export function TrashBanner({ onEmptyTrash }: TrashBannerProps) {
  return (
    <div className="border-b border-red-900/30 bg-red-950/25 px-4 py-3 sm:px-6">
      <div className="mx-auto flex max-w-content items-center justify-between gap-4">
        <p className="min-w-0 text-sm text-red-100/80">
          Notes in trash are removed permanently when you empty trash.
        </p>
        <button
          type="button"
          onClick={onEmptyTrash}
          className="min-h-11 shrink-0 rounded-note px-3 py-2 text-sm font-semibold text-red-300 hover:bg-red-950/50"
        >
          Empty trash
        </button>
      </div>
    </div>
  );
}
