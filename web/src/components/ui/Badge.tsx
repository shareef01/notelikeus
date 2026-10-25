import type { HTMLAttributes, ReactNode } from 'react';

export type BadgeVariant = 'neutral' | 'accent' | 'caution' | 'danger' | 'success';
export type BadgeSize = 'sm' | 'md';

export interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  variant?: BadgeVariant;
  size?: BadgeSize;
  children?: ReactNode;
}

const VARIANT_CLASSES: Record<BadgeVariant, string> = {
  neutral:
    'bg-brand-outline/25 text-brand-muted',
  accent:
    'bg-brand-primary/20 text-brand-primary',
  caution:
    'bg-amber-500/15 text-amber-800 dark:text-amber-400 border border-amber-500/40',
  danger:
    'bg-red-500/15 text-red-800 dark:text-red-400 border border-red-500/40',
  success:
    'bg-emerald-500/15 text-emerald-800 dark:text-emerald-400 border border-emerald-500/40',
};

const SIZE_CLASSES: Record<BadgeSize, string> = {
  sm: 'text-[11px] px-1.5 py-0.5 min-h-[20px] min-w-[20px]',
  md: 'text-[12px] px-2 py-0.5 min-h-[24px] min-w-[24px]',
};

export function Badge({
  variant = 'neutral',
  size = 'md',
  className = '',
  children,
  ...props
}: BadgeProps) {
  return (
    <span
      className={`inline-flex items-center justify-center rounded-full font-semibold tabular-nums tracking-wide transition-colors ${
        VARIANT_CLASSES[variant]
      } ${SIZE_CLASSES[size]} ${className}`}
      {...props}
    >
      {children}
    </span>
  );
}
