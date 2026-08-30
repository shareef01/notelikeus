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
      className="flex w-full items-center justify-center gap-3 rounded-note border border-brand-outline/50 bg-true-surface px-4 py-3.5 text-sm font-semibold text-brand-primary shadow-sm transition-transform hover:scale-[1.01] active:scale-[0.99] disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-primary"
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
