import { GoogleIcon } from '@/components/icons/GoogleIcon';

interface GoogleSignInButtonProps {
  label: string;
  onClick: () => void;
  disabled?: boolean;
  loading?: boolean;
}

export function GoogleSignInButton({
  label,
  onClick,
  disabled = false,
  loading = false,
}: GoogleSignInButtonProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled || loading}
      className="flex w-full min-h-11 items-center justify-center gap-3 rounded-note border border-neutral-700 bg-neutral-900/50 px-4 py-3 text-sm font-medium text-brand-primary transition-colors hover:bg-neutral-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-primary/60 active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-60"
    >
      {loading ? (
        <span className="size-5 animate-spin rounded-full border-2 border-brand-primary/20 border-t-brand-primary" />
      ) : (
        <GoogleIcon size={20} />
      )}
      <span>{loading ? 'Connecting…' : label}</span>
    </button>
  );
}
