interface SignOutDialogProps {
  open: boolean;
  onCancel: () => void;
  onSignOut: () => void;
  onSignOutAndDelete: () => void;
}

export function SignOutDialog({
  open,
  onCancel,
  onSignOut,
  onSignOutAndDelete,
}: SignOutDialogProps) {
  if (!open) return null;

  return (
    <div className="fixed inset-0 z-[60] flex items-end justify-center bg-black/70 p-4 sm:items-center sm:p-6">
      <div className="w-full max-w-md rounded-note bg-true-surface p-5 shadow-xl">
        <h4 className="text-lg font-semibold">Sign out of Google?</h4>
        <p className="mt-2 text-sm text-brand-muted">
          Notes on this device stay in your browser cache. Copies in the cloud remain unless you
          delete them.
        </p>
        <button
          type="button"
          onClick={onSignOutAndDelete}
          className="mt-4 w-full rounded-note py-2.5 text-sm font-semibold text-red-400 hover:bg-red-950/30"
        >
          Sign out and delete cloud data
        </button>
        <div className="mt-4 flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            className="rounded-note px-4 py-2 text-sm text-brand-muted"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={onSignOut}
            className="rounded-note bg-brand-primary px-4 py-2 text-sm font-semibold text-true-black"
          >
            Sign out
          </button>
        </div>
      </div>
    </div>
  );
}
