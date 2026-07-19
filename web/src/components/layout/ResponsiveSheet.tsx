import { useEffect, type ReactNode } from 'react';
import { useFocusTrap } from '@/hooks/useFocusTrap';

interface ResponsiveSheetProps {
  open: boolean;
  onClose: () => void;
  ariaLabel: string;
  children: ReactNode;
  maxWidthClass?: string;
  maxHeightClass?: string;
}

/** Bottom sheet on mobile; centered modal on tablet and desktop. */
export function ResponsiveSheet({
  open,
  onClose,
  ariaLabel,
  children,
  maxWidthClass = 'md:max-w-lg',
  maxHeightClass = 'max-h-[92vh] md:max-h-[85vh]',
}: ResponsiveSheetProps) {
  const panelRef = useFocusTrap<HTMLDivElement>(open, onClose);

  useEffect(() => {
    if (!open) return;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previousOverflow;
    };
  }, [open]);

  if (!open) return null;

  return (
    <>
      <div
        className="fixed inset-0 z-50 overlay-scrim backdrop-blur-sm"
        onClick={onClose}
        aria-hidden
      />
      <div
        ref={panelRef}
        className={`fixed inset-x-0 bottom-0 z-50 w-full overflow-y-auto rounded-t-[20px] bg-true-surface pb-safe shadow-2xl md:inset-x-auto md:bottom-auto md:left-1/2 md:top-1/2 md:-translate-x-1/2 md:-translate-y-1/2 md:rounded-note ${maxWidthClass} ${maxHeightClass}`}
        role="dialog"
        aria-modal="true"
        aria-label={ariaLabel}
      >
        <div className="mx-auto my-3 h-1.5 w-10 rounded-full bg-brand-outline md:hidden" />
        {children}
      </div>
    </>
  );
}
