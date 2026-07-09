import { BrandMark } from '@/components/brand/BrandMark';
import { ArchiveIcon, TrashIcon } from '@/components/icons/Icons';
import type { ReactNode } from 'react';

interface NotesEmptyStateProps {
  message: string;
  subtitle?: string | null;
  icon?: 'brand' | 'archive' | 'trash';
  action?: ReactNode;
}

export function NotesEmptyState({ message, subtitle, icon = 'brand', action }: NotesEmptyStateProps) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center px-6 py-16 text-center sm:px-10 lg:px-16">
      <div className="mb-6 opacity-20">
        {icon === 'brand' ? (
          <BrandMark size={72} />
        ) : icon === 'archive' ? (
          <ArchiveIcon size={72} className="text-brand-primary" />
        ) : (
          <TrashIcon size={72} className="text-brand-primary" />
        )}
      </div>
      <p className="text-base font-medium text-brand-muted">{message}</p>
      {subtitle ? (
        <p className="mt-2 text-sm font-medium text-brand-muted/85">{subtitle}</p>
      ) : null}
      {action ? <div className="mt-7">{action}</div> : null}
    </div>
  );
}
