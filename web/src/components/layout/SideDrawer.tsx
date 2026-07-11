import { GoogleSignInButton } from '@/components/auth/GoogleSignInButton';
import { BrandMark } from '@/components/brand/BrandMark';
import { ArchiveIcon, CloseIcon, LabelIcon, NotesIcon, SettingsIcon, TrashIcon } from '@/components/icons/Icons';
import { useIsDesktop } from '@/hooks/useMediaQuery';
import type { NoteFilter } from '@/types/note';
import { useEffect, useRef } from 'react';

interface SideDrawerProps {
  open: boolean;
  currentFilter: NoteFilter;
  onClose: () => void;
  onNavigate: (filter: NoteFilter) => void;
  userEmail: string | null;
  onSignIn: () => void;
  onSignOut: () => void;
  onEditLabels?: () => void;
  navCounts?: { active: number; archived: number; trashed: number };
  onOpenSettings?: () => void;
}

const NAV_ITEMS: Array<{ filter: NoteFilter; label: string; Icon: typeof NotesIcon }> = [
  { filter: 'active', label: 'Notes', Icon: NotesIcon },
  { filter: 'archived', label: 'Archive', Icon: ArchiveIcon },
  { filter: 'trashed', label: 'Trash', Icon: TrashIcon },
];

const FOCUSABLE =
  'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Side Drawer Overhaul
 * Geometric Discipline: Strict 16px radius and brand-aligned spacing.
 */
export function SideDrawer({
  open,
  currentFilter,
  onClose,
  onNavigate,
  userEmail,
  onSignIn,
  onSignOut,
  onEditLabels,
  navCounts,
  onOpenSettings,
}: SideDrawerProps) {
  const isDesktop = useIsDesktop();
  const drawerRef = useRef<HTMLElement>(null);
  const previousFocusRef = useRef<HTMLElement | null>(null);
  const trapFocus = open && !isDesktop;

  useEffect(() => {
    if (!trapFocus) return;

    previousFocusRef.current = document.activeElement as HTMLElement | null;
    const drawer = drawerRef.current;
    const focusables = drawer?.querySelectorAll<HTMLElement>(FOCUSABLE);
    focusables?.[0]?.focus();

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        onClose();
        return;
      }
      if (event.key !== 'Tab' || !drawer) return;

      const items = drawer.querySelectorAll<HTMLElement>(FOCUSABLE);
      if (items.length === 0) return;

      const first = items[0];
      const last = items[items.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };

    document.addEventListener('keydown', onKeyDown);
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.body.style.overflow = previousOverflow;
      previousFocusRef.current?.focus?.();
    };
  }, [trapFocus, onClose]);

  return (
    <>
      <div
        className={`fixed inset-0 z-40 overlay-scrim backdrop-blur-sm transition-opacity lg:hidden ${
          open ? 'opacity-100' : 'pointer-events-none opacity-0'
        }`}
        onClick={onClose}
        aria-hidden={!open}
      />

      <aside
        ref={drawerRef}
        role={trapFocus ? 'dialog' : undefined}
        aria-modal={trapFocus ? true : undefined}
        className={`fixed inset-y-0 left-0 z-50 flex w-[min(300px,88vw)] flex-col bg-true-surface shadow-2xl transition-transform duration-300 ease-out lg:static lg:z-auto lg:w-56 lg:shrink-0 lg:translate-x-0 lg:border-r lg:border-brand-outline lg:shadow-none xl:w-60 ${
          open ? 'translate-x-0' : '-translate-x-full lg:translate-x-0'
        }`}
        aria-hidden={!open && !isDesktop}
        aria-label="Navigation"
      >
        <div className="flex items-center justify-between px-4 pb-5 pt-safe lg:px-5 lg:pt-7">
          <div className="flex items-center gap-2.5">
            <BrandMark size={40} />
            <div>
              <p className="text-lg font-bold tracking-tight text-brand-primary">Notelikeus</p>
              <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-brand-muted/60">
                Capture
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="flex size-11 items-center justify-center rounded-full text-brand-muted interactive-hover lg:hidden transition-colors"
            aria-label="Close menu"
          >
            <CloseIcon size={24} />
          </button>
        </div>

        <nav className="flex flex-col gap-1 px-3 lg:px-2">
          {NAV_ITEMS.map(({ filter, label, Icon }) => {
            const active = currentFilter === filter;
            const count = navCounts?.[filter];
            return (
              <button
                key={filter}
                type="button"
                onClick={() => {
                  onNavigate(filter);
                  onClose();
                }}
                aria-current={active ? 'page' : undefined}
                className={`flex min-h-10 items-center gap-3 rounded-note px-3 py-2.5 text-left text-[15px] font-bold transition-all active:scale-[0.98] ${
                  active
                    ? 'bg-brand-primary/15 text-brand-primary'
                    : 'text-brand-muted interactive-hover hover:text-brand-primary'
                }`}
              >
                <Icon size={22} className={active ? 'text-brand-primary' : 'text-brand-muted/60'} />
                <span className="flex-1">{label}</span>
                {count != null && count > 0 ? (
                  <span className="text-xs font-semibold text-brand-muted">{count}</span>
                ) : null}
              </button>
            );
          })}
          {onEditLabels ? (
            <button
              type="button"
              onClick={() => {
                onEditLabels();
                onClose();
              }}
              className="flex min-h-10 items-center gap-3 rounded-note px-3 py-2.5 text-left text-[15px] font-bold text-brand-muted transition-all interactive-hover hover:text-brand-primary"
            >
              <LabelIcon size={22} className="text-brand-muted/60" />
              Edit labels
            </button>
          ) : null}
          {onOpenSettings ? (
            <button
              type="button"
              onClick={() => {
                onOpenSettings();
                onClose();
              }}
              className="flex min-h-10 items-center gap-3 rounded-note px-3 py-2.5 text-left text-[15px] font-bold text-brand-muted transition-all interactive-hover hover:text-brand-primary"
            >
              <SettingsIcon size={22} className="text-brand-muted/60" />
              Settings
            </button>
          ) : null}
        </nav>

        <div className="mt-auto border-t border-brand-outline px-4 py-5 pb-safe lg:px-5 lg:pb-7">
          {userEmail ? (
            <div className="space-y-4">
              <p className="truncate text-sm font-medium text-brand-muted">{userEmail}</p>
              <button
                type="button"
                onClick={onSignOut}
                className="min-h-11 w-full rounded-note border border-brand-outline bg-true-surface px-4 py-2.5 text-sm font-bold text-brand-primary transition-colors interactive-hover"
              >
                Sign out
              </button>
            </div>
          ) : (
            <GoogleSignInButton label="Sign in with Google" onClick={onSignIn} />
          )}
        </div>
      </aside>
    </>
  );
}
