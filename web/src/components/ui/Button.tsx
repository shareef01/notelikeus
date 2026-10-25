import { CHROME_FOCUS } from '@/lib/ui/focusStyles';
import type { ButtonHTMLAttributes, ReactNode } from 'react';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger';
export type ButtonSize = 'sm' | 'md' | 'lg';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  leadingIcon?: ReactNode;
  trailingIcon?: ReactNode;
  fullWidth?: boolean;
}

const VARIANT_CLASSES: Record<ButtonVariant, string> = {
  primary:
    'bg-brand-primary text-true-surface hover:opacity-90 active:scale-[0.98] shadow-sm',
  secondary:
    'border border-brand-outline/40 bg-true-surface-variant/40 text-brand-primary hover:bg-true-surface-variant/80 hover:border-brand-outline/60 active:scale-[0.98]',
  ghost:
    'text-brand-secondary hover:bg-brand-primary/8 hover:text-brand-primary active:bg-brand-primary/12',
  danger:
    'border border-red-500/30 bg-red-500/10 text-red-700 dark:text-red-400 hover:bg-red-500/20 active:scale-[0.98]',
};

const SIZE_CLASSES: Record<ButtonSize, string> = {
  sm: 'min-h-8 px-3 py-1.5 text-xs font-semibold gap-1.5 rounded-lg',
  md: 'min-h-10 px-4 py-2 text-sm font-semibold gap-2 rounded-note',
  lg: 'min-h-12 px-5 py-2.5 text-base font-semibold gap-2.5 rounded-note',
};

export function Button({
  variant = 'primary',
  size = 'md',
  leadingIcon,
  trailingIcon,
  fullWidth = false,
  className = '',
  disabled,
  children,
  ...props
}: ButtonProps) {
  return (
    <button
      type="button"
      disabled={disabled}
      className={`inline-flex items-center justify-center font-medium tracking-tight transition-all duration-150 select-none ${
        VARIANT_CLASSES[variant]
      } ${SIZE_CLASSES[size]} ${CHROME_FOCUS} ${
        fullWidth ? 'w-full' : ''
      } ${disabled ? 'cursor-not-allowed opacity-50 active:scale-100' : 'cursor-pointer'} ${className}`}
      {...props}
    >
      {leadingIcon ? <span className="shrink-0">{leadingIcon}</span> : null}
      {children ? <span>{children}</span> : null}
      {trailingIcon ? <span className="shrink-0">{trailingIcon}</span> : null}
    </button>
  );
}
