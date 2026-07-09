import { BrandMark } from '@/components/brand/BrandMark';
import { ArchiveIcon, CloseIcon, NotesIcon, SettingsIcon, TrashIcon } from '@/components/icons/Icons';
import { useIsDesktop } from '@/hooks/useMediaQuery';
import type { NoteFilter } from '@/types/note';

interface SideDrawerProps {
  open: boolean;
  currentFilter: NoteFilter;
  onClose: () => void;
  onNavigate: (filter: NoteFilter) => void;
  userEmail: string | null;
  onSignIn: () => void;
  onSignOut: () => void;
  navCounts?: { active: number; archived: number; trashed: number };
  onOpenSettings?: () => void;
}

const NAV_ITEMS: Array<{ filter: NoteFilter; label: string; Icon: typeof NotesIcon }> = [
  { filter: 'active', label: 'Notes', Icon: NotesIcon },
  { filter: 'archived', label: 'Archive', Icon: ArchiveIcon },
  { filter: 'trashed', label: 'Trash', Icon: TrashIcon },
];

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
  navCounts,
  onOpenSettings,
}: SideDrawerProps) {
  const isDesktop = useIsDesktop();

  return (
    <>
      <div
        className={`fixed inset-0 z-40 bg-black/80 backdrop-blur-sm transition-opacity lg:hidden ${
          open ? 'opacity-100' : 'pointer-events-none opacity-0'
        }`}
        onClick={onClose}
        aria-hidden={!open}
      />

      <aside
        className={`fixed inset-y-0 left-0 z-50 flex w-[min(320px,88vw)] flex-col bg-true-surface shadow-2xl transition-transform duration-300 ease-out lg:static lg:z-auto lg:w-64 lg:shrink-0 lg:translate-x-0 lg:border-r lg:border-brand-outline lg:shadow-none xl:w-72 ${
          open ? 'translate-x-0' : '-translate-x-full lg:translate-x-0'
        }`}
        aria-hidden={!open && !isDesktop}
        aria-label="Navigation"
      >
        <div className="flex items-center justify-between px-6 pb-6 pt-safe lg:pt-8">
          <div className="flex items-center gap-3">
            <BrandMark size={44} />
            <div>
              <p className="text-xl font-bold tracking-tight text-brand-primary">Notelikeus</p>
              <p className="text-[12px] font-semibold uppercase tracking-[0.8px] text-brand-muted/65">
                Capture
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="flex size-10 items-center justify-center rounded-full text-brand-muted hover:bg-white/5 lg:hidden transition-colors"
            aria-label="Close menu"
          >
            <CloseIcon size={24} />
          </button>
        </div>

        <nav className="flex flex-col gap-1.5 px-3">
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
                className={`flex items-center gap-4 rounded-note px-4 py-3 text-left text-base font-bold transition-all active:scale-[0.98] ${
                  active
                    ? 'bg-brand-primary/15 text-brand-primary'
                    : 'text-brand-muted hover:bg-white/5 hover:text-brand-primary'
                }`}
              >
                <Icon size={24} className={active ? 'text-brand-primary' : 'text-brand-muted/60'} />
                <span className="flex-1">{label}</span>
                {count != null && count > 0 ? (
                  <span className="text-xs font-semibold text-brand-muted">{count}</span>
                ) : null}
              </button>
            );
          })}
          {onOpenSettings ? (
            <button
              type="button"
              onClick={() => {
                onOpenSettings();
                onClose();
              }}
              className="flex items-center gap-4 rounded-note px-4 py-3 text-left text-base font-bold text-brand-muted transition-all hover:bg-white/5 hover:text-brand-primary"
            >
              <SettingsIcon size={24} className="text-brand-muted/60" />
              Settings
            </button>
          ) : null}
        </nav>

        <div className="mt-auto border-t border-brand-outline p-6 pb-safe lg:pb-8">
          {userEmail ? (
            <div className="space-y-4">
              <p className="truncate text-sm font-medium text-brand-muted">{userEmail}</p>
              <button
                type="button"
                onClick={onSignOut}
                className="w-full rounded-note border border-brand-outline bg-true-black px-4 py-2.5 text-sm font-bold text-brand-primary transition-colors hover:bg-white/5"
              >
                Sign out
              </button>
            </div>
          ) : (
            <button
              type="button"
              onClick={onSignIn}
              className="w-full rounded-note bg-brand-primary px-4 py-2.5 text-sm font-bold text-true-black transition-transform active:scale-95"
            >
              Sign in with Google
            </button>
          )}
        </div>
      </aside>
    </>
  );
}
