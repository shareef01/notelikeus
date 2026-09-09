import { GoogleIcon } from '@/components/icons/GoogleIcon';

interface GoogleSignInButtonProps {
  label: string;
  onClick: () => void;
  disabled?: boolean;
  loading?: boolean;
}

/** Official-ish Google sign-in chrome: white surface, dark label, multicolor G. */
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
      className="flex w-full items-center justify-center gap-3 rounded-xl border border-[#747775]/40 bg-white px-4 py-2.5 text-sm font-semibold text-[#1f1f1f] shadow-sm transition-colors hover:bg-[#f8f9fa] active:bg-[#f1f3f4] disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#4285F4]"
    >
      {loading ? (
        <span className="size-5 animate-spin rounded-full border-2 border-[#1f1f1f]/20 border-t-[#4285F4]" />
      ) : (
        <GoogleIcon size={18} />
      )}
      <span>{loading ? 'Connecting…' : label}</span>
    </button>
  );
}
