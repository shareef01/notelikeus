import { BrandMark } from '@/components/brand/BrandMark';
import { ArchiveIcon, TrashIcon } from '@/components/icons/Icons';
import type { ReactNode } from 'react';

interface NotesEmptyStateProps {
  message: string;
  subtitle?: string | null;
  icon?: 'brand' | 'archive' | 'trash';
  action?: ReactNode;
}

/**
 * Empty State Overhaul (Web)
 * Synchronized with Android Elite Standards: 20% opacity large icons, centered medium text.
 */
export function NotesEmptyState({ message, subtitle, icon = 'brand', action }: NotesEmptyStateProps) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center px-6 py-20 text-center sm:px-10 lg:px-16">
      <div className="mb-8 opacity-20">
        {icon === 'brand' ? (
          <BrandMark size={72} />
        ) : icon === 'archive' ? (
          <ArchiveIcon size={72} className="text-brand-primary" />
        ) : (
          <TrashIcon size={72} className="text-brand-primary" />
        )}
      </div>
      <p className="text-[18px] font-bold tracking-tight text-brand-primary opacity-80">{message}</p>
      {subtitle ? (
        <p className="mt-2 text-[14px] font-medium leading-[1.4em] text-brand-muted opacity-65">
          {subtitle}
        </p>
      ) : null}
      {action ? <div className="mt-8">{action}</div> : null}
    </div>
  );
}
