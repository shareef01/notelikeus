import { BrandMark } from '@/components/brand/BrandMark';
import {
  ArchiveIcon,
  ChevronRightIcon,
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
  collapsed: boolean;
  currentFilter: NoteFilter;
  onClose: () => void;
  onToggleCollapse: () => void;
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
  collapsed = false,
}: {
  active?: boolean;
  onClick: () => void;
  icon: ReactNode;
  label: string;
  trailing?: ReactNode;
  ariaCurrent?: 'page';
  iconClass: string;
  barClass: string;
  collapsed?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-current={ariaCurrent}
      aria-label={collapsed ? label : undefined}
      title={collapsed ? label : undefined}
      className={`group relative flex w-full items-center gap-3 rounded-xl text-left text-sm leading-5 tracking-tight transition-colors ${
        collapsed
          ? 'min-h-11 justify-center px-0 py-2.5'
          : 'min-h-11 py-2.5 pl-3 pr-2.5'
      } ${
        active
          ? 'bg-brand-primary/[0.08] font-semibold text-brand-primary'
          : 'font-medium text-brand-secondary hover:bg-brand-primary/[0.06] hover:text-brand-primary'
      }`}
    >
      {!collapsed && active ? (
        <span
          className={`absolute inset-y-2.5 left-0 w-0.5 rounded-full ${barClass}`}
          aria-hidden
        />
      ) : null}
      <span
        className={`flex shrink-0 items-center justify-center transition-opacity ${iconClass} ${
          active ? 'opacity-100' : 'opacity-85 group-hover:opacity-100'
        } ${collapsed ? 'size-6' : 'size-6'}`}
      >
        {icon}
      </span>
      {!collapsed && (
        <>
          <span className="min-w-0 flex-1 truncate tracking-tight">{label}</span>
          {trailing}
        </>
      )}
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
      className={`inline-flex h-[22px] min-w-[22px] items-center justify-center rounded-full px-1.5 text-[11px] font-semibold tabular-nums ${
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
  collapsed,
  currentFilter,
  onClose,
  onToggleCollapse,
  onNavigate,
  userEmail,
  onSignIn,
  onSignOut,
  onEditLabels,
  navCounts,
  onOpenSettings,
}: SideDrawerProps) {
  const isTabletUp = useIsTabletUp();
  const showCollapsed = collapsed && isTabletUp;

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
        className={`fixed inset-y-0 left-0 z-50 flex flex-col bg-true-surface transition-all duration-300 ease-out md:static md:z-auto md:shrink-0 md:translate-x-0 md:border-r md:border-brand-outline/50 ${
          showCollapsed
            ? 'w-[min(300px,88vw)] md:w-16'
            : 'w-[min(300px,88vw)] md:w-56 lg:w-60 xl:w-64'
        } ${
          open ? 'translate-x-0 shadow-2xl' : '-translate-x-full md:translate-x-0 md:shadow-none'
        }`}
        aria-hidden={!open && !isTabletUp}
        aria-label="Navigation"
      >
        {/* Header */}
        <div className={`flex items-center gap-3 pb-5 pt-safe md:pt-7 ${
          showCollapsed ? 'justify-center' : 'justify-between px-4 md:px-5'
        }`}>
          {showCollapsed ? (
            <BrandMark size={32} />
          ) : (
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
          )}
          {!showCollapsed && (
            <button
              type="button"
              onClick={onClose}
              className="flex size-9 shrink-0 items-center justify-center rounded-full text-brand-muted transition-colors hover:bg-brand-primary/5 md:hidden"
              aria-label="Close menu"
            >
              <CloseIcon size={20} />
            </button>
          )}
        </div>

        {/* Nav */}
        <nav className={`flex flex-1 flex-col overflow-y-auto px-2.5 pb-5 ${
          showCollapsed ? 'gap-3 md:px-1.5' : 'gap-5 md:px-3'
        }`}>
          <NavSection title={showCollapsed ? undefined : undefined}>
            {NAV_ITEMS.map(({ filter, label, Icon, iconClass, barClass }) => {
              const active = currentFilter === filter;
              const count = navCounts?.[filter];
              return (
                <NavButton
                  key={filter}
                  active={active}
                  collapsed={showCollapsed}
                  iconClass={iconClass}
                  barClass={barClass}
                  ariaCurrent={active ? 'page' : undefined}
                  onClick={() => {
                    onNavigate(filter);
                    onClose();
                  }}
                  icon={<Icon size={showCollapsed ? 22 : 22} />}
                  label={label}
                  trailing={
                    !showCollapsed && count != null && count > 0 ? (
                      <CountBadge count={count} active={active} />
                    ) : null
                  }
                />
              );
            })}
          </NavSection>

          {(onEditLabels || onOpenSettings) ? (
            <NavSection title={showCollapsed ? undefined : 'Manage'}>
              {onEditLabels ? (
                <NavButton
                  collapsed={showCollapsed}
                  iconClass={MANAGE_ITEMS.labels.iconClass}
                  barClass={MANAGE_ITEMS.labels.barClass}
                  onClick={() => {
                    onEditLabels();
                    onClose();
                  }}
                  icon={<LabelIcon size={22} />}
                  label="Edit labels"
                />
              ) : null}
              {onOpenSettings ? (
                <NavButton
                  collapsed={showCollapsed}
                  iconClass={MANAGE_ITEMS.settings.iconClass}
                  barClass={MANAGE_ITEMS.settings.barClass}
                  onClick={() => {
                    onOpenSettings();
                    onClose();
                  }}
                  icon={<SettingsIcon size={22} />}
                  label="Settings"
                />
              ) : null}
            </NavSection>
          ) : null}
        </nav>

        {/* Collapse toggle — desktop only */}
        {isTabletUp ? (
          <div className={`border-t border-brand-outline/40 ${showCollapsed ? 'px-1 py-3' : 'px-4 py-2 md:px-5'}`}>
            <button
              type="button"
              onClick={onToggleCollapse}
              className={`group flex w-full items-center rounded-xl border border-brand-secondary/25 bg-brand-primary/[0.06] font-medium text-brand-primary/85 transition-colors hover:border-brand-secondary/45 hover:bg-brand-primary/[0.1] hover:text-brand-primary ${
                showCollapsed
                  ? 'min-h-11 justify-center py-2.5'
                  : 'min-h-11 gap-2.5 py-2.5 pl-3 pr-2.5'
              }`}
              aria-label={showCollapsed ? 'Expand sidebar' : 'Collapse sidebar'}
              title={showCollapsed ? 'Expand sidebar' : 'Collapse sidebar'}
            >
              <span className={`flex size-6 shrink-0 items-center justify-center transition-transform duration-300 ${
                showCollapsed ? '' : 'rotate-180'
              }`}>
                <ChevronRightIcon size={20} />
              </span>
              {!showCollapsed && (
                <span className="min-w-0 flex-1 truncate text-left text-sm tracking-tight">
                  Collapse
                </span>
              )}
            </button>
          </div>
        ) : null}

        {/* Bottom — user section */}
        {!showCollapsed && (
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
        )}
        {showCollapsed && userEmail ? (
          <div className="mt-auto border-t border-brand-outline/40 px-1 py-4">
            <span
              className="mx-auto flex size-8 items-center justify-center rounded-full bg-brand-primary/10 text-[12px] font-bold uppercase text-brand-primary"
              aria-hidden
              title={userEmail}
            >
              {userEmail.charAt(0)}
            </span>
          </div>
        ) : null}
        {showCollapsed && !userEmail ? (
          <div className="mt-auto border-t border-brand-outline/40 px-1 py-4">
            <button
              type="button"
              onClick={onSignIn}
              className="mx-auto flex size-8 items-center justify-center rounded-full bg-brand-primary/10 text-brand-primary transition-opacity hover:opacity-80"
              aria-label="Sign in"
              title="Sign in with Google"
            >
              <span className="text-[14px] font-bold">G</span>
            </button>
          </div>
        ) : null}
      </aside>
    </>
  );
}
