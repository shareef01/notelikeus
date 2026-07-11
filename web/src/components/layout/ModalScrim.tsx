import type { MouseEvent, ReactNode } from 'react';

interface ModalScrimProps {
  children: ReactNode;
  className?: string;
  zIndexClass?: string;
  align?: 'center' | 'bottom';
  onScrimClick?: () => void;
}

/** Theme-aware modal backdrop with flex alignment for panel children. */
export function ModalScrim({
  children,
  className = '',
  zIndexClass = 'z-[60]',
  align = 'bottom',
  onScrimClick,
}: ModalScrimProps) {
  const alignClass =
    align === 'bottom'
      ? 'items-end justify-center p-4 sm:items-center sm:p-6'
      : 'items-center justify-center p-4 sm:p-6';

  return (
    <div
      className={`fixed inset-0 flex overlay-scrim backdrop-blur-sm ${zIndexClass} ${alignClass} ${className}`}
      onClick={onScrimClick}
      role="presentation"
    >
      {children}
    </div>
  );
}

/** Stop scrim click from closing when interacting with panel content. */
export function modalPanelProps(className: string) {
  return {
    className,
    onClick: (event: MouseEvent) => event.stopPropagation(),
  } as const;
}
