import type { ReactNode } from 'react';

export interface EmptyStateProps {
  icon?: ReactNode;
  title: string;
  description?: string | null;
  action?: ReactNode;
  className?: string;
}

export function EmptyState({
  icon,
  title,
  description,
  action,
  className = '',
}: EmptyStateProps) {
  return (
    <div
      className={`flex flex-1 flex-col items-center justify-center px-6 py-12 text-center sm:px-8 sm:py-16 ${className}`}
    >
      {icon ? (
        <div className="mb-5 flex size-14 items-center justify-center rounded-2xl bg-brand-primary/[0.06] text-brand-muted opacity-80" aria-hidden>
          {icon}
        </div>
      ) : null}
      <h3 className="text-base font-semibold tracking-tight text-brand-primary sm:text-lg">
        {title}
      </h3>
      {description ? (
        <p className="mt-1.5 max-w-sm text-sm text-brand-muted">
          {description}
        </p>
      ) : null}
      {action ? <div className="mt-6">{action}</div> : null}
    </div>
  );
}
