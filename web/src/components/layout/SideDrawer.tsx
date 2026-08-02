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
import type { ReactNode } from 'react';

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

const NAV_ITEMS: Array<{
  filter: NoteFilter;
  label: string;
  Icon: typeof NotesIcon;
  iconClass: string;
  barClass: string;
}> = [
  { filter: 'active', label: 'Notes', Icon: NotesIcon, iconClass: 'text-sky-400', barClass: 'bg-sky-400' },
  { filter: 'archived', label: 'Archive', Icon: ArchiveIcon, iconClass: 'text-amber-400', barClass: 'bg-amber-400' },
  { filter: 'trashed', label: 'Trash', Icon: TrashIcon, iconClass: 'text-rose-400', barClass: 'bg-rose-400' },
];

const MANAGE_ITEMS = {
  labels: { iconClass: 'text-violet-400', barClass: 'bg-violet-400' },
  settings: { iconClass: 'text-teal-400', barClass: 'bg-teal-400' },
} as const;

function NavButton({
  active = false,
  onClick,
  icon,
  label,
  trailing,
  ariaCurrent,
  iconClass,
  barClass,
}: {
  active?: boolean;
  onClick: () => void;
  icon: ReactNode;
  label: string;
  trailing?: ReactNode;
  ariaCurrent?: 'page';
  iconClass: string;
  barClass: string;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-current={ariaCurrent}
      className={`group relative flex min-h-10 w-full items-center gap-2.5 rounded-xl py-2.5 pl-3 pr-2.5 text-left text-sm leading-none tracking-tight transition-colors ${
        active
          ? 'bg-brand-primary/[0.08] font-semibold text-brand-primary'
          : 'font-medium text-brand-secondary hover:bg-brand-primary/[0.06] hover:text-brand-primary'
      }`}
    >
      {active ? (
        <span
          className={`absolute inset-y-2 left-0 w-0.5 rounded-full ${barClass}`}
          aria-hidden
        />
      ) : null}
      <span
        className={`flex size-5 shrink-0 items-center justify-center transition-opacity ${iconClass} ${
          active ? 'opacity-100' : 'opacity-85 group-hover:opacity-100'
        }`}
      >
        {icon}
      </span>
      <span className="min-w-0 flex-1 truncate tracking-tight">{label}</span>
      {trailing}
    </button>
  );
}

function NavSection({ title, children }: { title?: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      {title ? (
        <p className="px-3 pb-2 pt-1 text-chrome-label">
          {title}
        </p>
      ) : null}
      {children}
    </div>
  );
}

function CountBadge({ count, active }: { count: number; active: boolean }) {
  return (
    <span
      className={`inline-flex h-5 min-w-5 items-center justify-center rounded-md px-1.5 text-[11px] font-semibold tabular-nums ${
        active
          ? 'bg-brand-primary/20 text-brand-primary'
          : 'bg-brand-primary/[0.08] text-brand-secondary'
      }`}
    >
      {count}
    </span>
  );
}

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
        className={`fixed inset-0 z-40 bg-black/70 transition-opacity md:hidden ${
          open ? 'opacity-100' : 'pointer-events-none opacity-0'
        }`}
        onClick={onClose}
        aria-hidden={!open}
      />

      <aside
        className={`fixed inset-y-0 left-0 z-50 flex w-[min(300px,88vw)] flex-col bg-true-surface transition-transform duration-300 ease-out md:static md:z-auto md:w-56 md:shrink-0 md:translate-x-0 md:border-r md:border-brand-outline/50 lg:w-60 xl:w-64 ${
          open ? 'translate-x-0 shadow-2xl' : '-translate-x-full md:translate-x-0 md:shadow-none'
        }`}
        aria-hidden={!open && !isTabletUp}
        aria-label="Navigation"
      >
        <div className="flex items-center justify-between gap-3 px-4 pb-5 pt-safe md:px-5 md:pt-7">
          <div className="flex min-w-0 items-center gap-2.5">
            <BrandMark size={36} />
            <div className="min-w-0">
              <p className="truncate text-[15px] font-bold tracking-tight text-brand-primary">
                Notelikeus
              </p>
              <p className="text-chrome-label">
                Capture
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="flex size-9 shrink-0 items-center justify-center rounded-full text-brand-muted transition-colors hover:bg-brand-primary/5 md:hidden"
            aria-label="Close menu"
          >
            <CloseIcon size={20} />
          </button>
        </div>

        <nav className="flex flex-1 flex-col gap-7 overflow-y-auto px-2.5 pb-5 md:px-3">
          <NavSection>
            {NAV_ITEMS.map(({ filter, label, Icon, iconClass, barClass }) => {
              const active = currentFilter === filter;
              const count = navCounts?.[filter];
              return (
                <NavButton
                  key={filter}
                  active={active}
                  iconClass={iconClass}
                  barClass={barClass}
                  ariaCurrent={active ? 'page' : undefined}
                  onClick={() => {
                    onNavigate(filter);
                    onClose();
                  }}
                  icon={<Icon size={20} />}
                  label={label}
                  trailing={
                    count != null && count > 0 ? (
                      <CountBadge count={count} active={active} />
                    ) : null
                  }
                />
              );
            })}
          </NavSection>

          {(onEditLabels || onOpenSettings) ? (
            <NavSection title="Manage">
              {onEditLabels ? (
                <NavButton
                  iconClass={MANAGE_ITEMS.labels.iconClass}
                  barClass={MANAGE_ITEMS.labels.barClass}
                  onClick={() => {
                    onEditLabels();
                    onClose();
                  }}
                  icon={<LabelIcon size={20} />}
                  label="Edit labels"
                />
              ) : null}
              {onOpenSettings ? (
                <NavButton
                  iconClass={MANAGE_ITEMS.settings.iconClass}
                  barClass={MANAGE_ITEMS.settings.barClass}
                  onClick={() => {
                    onOpenSettings();
                    onClose();
                  }}
                  icon={<SettingsIcon size={20} />}
                  label="Settings"
                />
              ) : null}
            </NavSection>
          ) : null}
        </nav>

        <div className="mt-auto border-t border-brand-outline/40 px-4 py-4 pb-safe md:px-5 md:pb-6">
          {userEmail ? (
            <div className="space-y-3">
              <div className="flex min-w-0 items-center gap-2.5">
                <span
                  className="flex size-8 shrink-0 items-center justify-center rounded-full bg-brand-primary/10 text-[12px] font-bold uppercase text-brand-primary"
                  aria-hidden
                >
                  {userEmail.charAt(0)}
                </span>
                <div className="min-w-0 flex-1">
                  <p className="text-chrome-label">
                    Signed in
                  </p>
                  <p
                    className="truncate text-[13px] font-medium leading-snug tracking-tight text-brand-primary"
                    title={userEmail}
                  >
                    {userEmail}
                  </p>
                </div>
              </div>
              <button
                type="button"
                onClick={onSignOut}
                className="w-full rounded-xl bg-rose-500/15 px-3 py-2.5 text-center text-sm font-semibold text-rose-400 ring-1 ring-inset ring-rose-500/25 transition-colors hover:bg-rose-500/25 hover:text-rose-300"
              >
                Sign out
              </button>
            </div>
          ) : (
            <button
              type="button"
              onClick={onSignIn}
              className="w-full rounded-xl bg-brand-primary px-3 py-2.5 text-sm font-semibold text-true-surface transition-opacity hover:opacity-90"
            >
              Sign in with Google
            </button>
          )}
        </div>
      </aside>
    </>
  );
}
