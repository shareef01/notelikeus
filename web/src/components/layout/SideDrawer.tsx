import { GoogleSignInButton } from '@/components/auth/GoogleSignInButton';
import { BrandMark } from '@/components/brand/BrandMark';
import {
  ArchiveIcon,
  CloseIcon,
  LabelIcon,
  NotesIcon,
  SettingsIcon,
  TrashIcon,
} from '@/components/icons/Icons';
import { useIsTabletUp } from '@/hooks/useMediaQuery';
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
  const isTabletUp = useIsTabletUp();

  return (
    <>
      <div
        className={`fixed inset-0 z-40 bg-black/80 backdrop-blur-sm transition-opacity md:hidden ${
          open ? 'opacity-100' : 'pointer-events-none opacity-0'
        }`}
        onClick={onClose}
        aria-hidden={!open}
      />

      <aside
        className={`fixed inset-y-0 left-0 z-50 flex w-[min(320px,88vw)] flex-col bg-true-surface shadow-2xl transition-transform duration-300 ease-out md:static md:z-auto md:w-60 md:shrink-0 md:translate-x-0 md:border-r md:border-brand-outline md:shadow-none lg:w-64 xl:w-72 ${
          open ? 'translate-x-0' : '-translate-x-full md:translate-x-0'
        }`}
        aria-hidden={!open && !isTabletUp}
        aria-label="Navigation"
      >
        <div className="flex items-center justify-between px-6 pb-6 pt-safe md:pt-8">
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
            onClick={onClose}
            className="flex size-10 items-center justify-center rounded-full text-brand-muted transition-colors hover:bg-white/5 md:hidden"
            aria-label="Close menu"
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
              <LabelIcon size={24} className="text-brand-muted/60" />
              Edit labels
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

        <div className="mt-auto border-t border-brand-outline p-6 pb-safe md:pb-8">
          {userEmail ? (
            <div className="flex flex-col gap-3">
              <div>
                <p className="text-[9px] font-bold uppercase tracking-widest text-brand-muted/40">Signed in</p>
                <p className="truncate text-[12px] font-medium text-brand-muted/70">{userEmail}</p>
              </div>
              <button
                type="button"
                onClick={onSignOut}
                className="w-full rounded-note border border-brand-outline bg-true-surface-variant px-4 py-2.5 text-sm font-bold text-brand-primary transition-colors hover:bg-white/5"
              >
                <span>Sign Out</span>
              </button>
            </div>
          ) : (
            <button
              type="button"
              onClick={onSignIn}
              className="w-full rounded-note bg-brand-primary px-4 py-2.5 text-sm font-bold text-true-surface transition-transform active:scale-95"
            >
              Sign in with Google
            </button>
          )}
        </div>
      </aside>
    </>
  );
}
