import { GoogleSignInButton } from '@/components/auth/GoogleSignInButton';
import { BrandMark } from '@/components/brand/BrandMark';
import {
  ArchiveIcon,
  CloseIcon,
  LabelIcon,
  MenuIcon,
  NotesIcon,
  SettingsIcon,
  TrashIcon,
} from '@/components/icons/Icons';
import { useIsDesktop } from '@/hooks/useMediaQuery';
import { useUiStore } from '@/store/uiStore';
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
 * Adaptive Sidebar: Supports expanded and collapsed (rail) modes on desktop.
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
  const collapsed = useUiStore((s) => s.sidebarCollapsed);
  const toggleSidebar = useUiStore((s) => s.toggleSidebar);

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
        className={`fixed inset-0 z-40 bg-black/70 backdrop-blur-sm transition-opacity duration-300 lg:hidden ${
          open ? 'opacity-100' : 'pointer-events-none opacity-0'
        }`}
        onClick={onClose}
        aria-hidden={!open}
      />

      <aside
        ref={drawerRef}
        role={trapFocus ? 'dialog' : undefined}
        aria-modal={trapFocus ? true : undefined}
        className={`fixed inset-y-0 left-0 z-50 flex w-72 max-w-[80vw] flex-col bg-[#0C0C0E] shadow-2xl transition-transform duration-300 ease-out lg:static lg:z-auto lg:w-64 xl:w-72 lg:translate-x-0 lg:border-r lg:border-white/[0.03] lg:shadow-none lg:transition-none ${
          collapsed && isDesktop ? 'lg:w-[72px]' : ''
        } ${open ? 'translate-x-0' : '-translate-x-full lg:translate-x-0'}`}
        aria-hidden={!open && !isDesktop}
        aria-label="Navigation"
      >
        <div className="flex items-center justify-between px-4 pb-4 pt-safe lg:px-5 lg:pt-8">
          <div className="flex items-center gap-3">
            <BrandMark size={32} />
            <div>
              <p className="text-base font-bold tracking-tighter text-brand-primary">Notelikeus</p>
              <p className="text-[9px] font-bold uppercase tracking-[0.2em] text-brand-muted/60">
                Capture
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={isDesktop ? toggleSidebar : onClose}
            className="flex size-9 shrink-0 items-center justify-center rounded-xl text-brand-muted interactive-hover transition-colors"
            aria-label={isDesktop ? (collapsed ? 'Expand sidebar' : 'Collapse sidebar') : 'Close menu'}
          >
            {isDesktop ? <MenuIcon size={18} /> : <CloseIcon size={22} />}
          </button>
        </div>

        <nav className="flex flex-1 flex-col gap-1 overflow-y-auto px-3 pb-4 lg:px-4">
          {NAV_ITEMS.map(({ filter, label, Icon }) => {
            const active = currentFilter === filter;
            const count = navCounts?.[filter];
            return (
              <button
                key={filter}
                type="button"
                onClick={() => {
                  onNavigate(filter);
                  if (!isDesktop) onClose();
                }}
                aria-current={active ? 'page' : undefined}
                className={`flex h-12 w-full items-center gap-3 rounded-xl px-3 transition-all active:scale-[0.98] ${
                  active
                    ? 'bg-white/10 text-white'
                    : 'text-brand-muted hover:text-brand-primary hover:bg-white/5'
                }`}
              >
                <Icon size={20} className={active ? 'text-white' : 'text-brand-muted/50'} />
                <span className="flex-1 text-[14px] font-semibold tracking-tight">{label}</span>
                {count != null && count > 0 && (
                  <span className="text-[11px] font-bold text-brand-muted/60">{count}</span>
                )}
              </button>
            );
          })}

          <div className="my-2 h-px bg-white/[0.03]" />

          {onEditLabels ? (
            <button
              type="button"
              onClick={() => {
                onEditLabels();
                if (!isDesktop) onClose();
              }}
              className="flex h-12 w-full items-center gap-3 rounded-xl px-3 transition-all text-brand-muted hover:text-brand-primary hover:bg-white/5"
            >
              <LabelIcon size={20} className="text-brand-muted/50" />
              <span className="text-[14px] font-semibold tracking-tight">Edit labels</span>
            </button>
          ) : null}

          {onOpenSettings ? (
            <button
              type="button"
              onClick={() => {
                onOpenSettings();
                if (!isDesktop) onClose();
              }}
              className="flex h-12 w-full items-center gap-3 rounded-xl px-3 transition-all text-brand-muted hover:text-brand-primary hover:bg-white/5"
            >
              <SettingsIcon size={20} className="text-brand-muted/50" />
              <span className="text-[14px] font-semibold tracking-tight">Settings</span>
            </button>
          ) : null}
        </nav>

        <div className="border-t border-white/[0.03] px-4 py-4 pb-safe lg:px-5 lg:py-5">
          {userEmail ? (
            <div className="flex flex-col gap-3">
              <div>
                <p className="text-[9px] font-bold uppercase tracking-widest text-brand-muted/40">Signed in</p>
                <p className="truncate text-[12px] font-medium text-brand-muted/70">{userEmail}</p>
              </div>
              <button
                type="button"
                onClick={onSignOut}
                className="flex h-10 w-full items-center justify-center gap-2 rounded-xl border border-white/10 text-[12px] font-bold tracking-tight text-brand-muted transition-all hover:bg-white/5 hover:text-white active:scale-[0.98]"
              >
                <span>Sign Out</span>
              </button>
            </div>
          ) : (
            <div className="flex flex-col gap-3">
              <p className="text-[11px] leading-relaxed text-brand-muted/50">
                Sign in to sync your notes across devices.
              </p>
              <GoogleSignInButton label="Sign in with Google" onClick={onSignIn} />
            </div>
          )}
        </div>
      </aside>
    </>
  );
}
