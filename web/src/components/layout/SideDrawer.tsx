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
  onNavigate: (filter: NoteFilter) => void;
  onToggleCollapse: () => void;
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
  collapsed = false,
  onClick,
  icon,
  label,
  trailing,
  ariaCurrent,
  iconClass,
  barClass,
}: {
  active?: boolean;
  collapsed?: boolean;
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
      title={collapsed ? label : undefined}
      className={`group relative flex items-center text-left text-sm leading-none tracking-tight transition-all duration-200 ${
        collapsed
          ? 'mx-auto size-10 justify-center rounded-[14px] p-0 hover:scale-[1.04]'
          : 'min-h-10 w-full gap-2.5 rounded-xl py-2.5 pl-3 pr-2.5'
      } ${
        active
          ? collapsed
            ? 'bg-brand-primary/[0.14] text-brand-primary ring-1 ring-inset ring-brand-primary/[0.08]'
            : 'bg-brand-primary/[0.08] font-semibold text-brand-primary'
          : 'font-medium text-brand-secondary hover:bg-brand-primary/[0.06] hover:text-brand-primary'
      }`}
    >
      {active && collapsed ? (
        <span
          className={`absolute left-1 top-1/2 -translate-y-1/2 size-1.5 rounded-full ${barClass} ring-2 ring-true-surface`}
          aria-hidden
        />
      ) : active && !collapsed ? (
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
      {!collapsed && (
        <>
          <span className="min-w-0 flex-1 truncate tracking-tight">{label}</span>
          {trailing}
        </>
      )}
      {collapsed && trailing ? (
        <span className="absolute -right-0.5 -top-0.5 flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-brand-primary/25 px-1 text-[10px] font-bold tabular-nums leading-none text-brand-primary ring-2 ring-true-surface">
          {trailing}
        </span>
      ) : null}
    </button>
  );
}

function NavSection({ title, collapsed, children }: { title?: string; collapsed?: boolean; children: ReactNode }) {
  return (
    <div className="flex flex-col">
      {title && !collapsed ? (
        <p className="px-3 pb-2 pt-1 text-chrome-label">{title}</p>
      ) : collapsed ? (
        <div className="mx-auto my-1 h-px w-5 rounded-full bg-brand-outline/15" aria-hidden />
      ) : null}
      <div className={`flex flex-col ${collapsed ? 'gap-0' : 'gap-0.5'}`}>{children}</div>
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
  collapsed: collapsedProp,
  currentFilter,
  onClose,
  onNavigate,
  onToggleCollapse,
  userEmail,
  onSignIn,
  onSignOut,
  onEditLabels,
  navCounts,
  onOpenSettings,
}: SideDrawerProps) {
  const isTabletUp = useIsTabletUp();
  // Collapse is a desktop-only concept — the mobile drawer always shows the full layout.
  const collapsed = collapsedProp && isTabletUp;

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
        className={`fixed inset-y-0 left-0 z-50 flex flex-col bg-true-surface transition-all duration-300 ease-out
          ${collapsed ? 'w-14' : 'w-[min(300px,88vw)]'}
          md:static md:z-auto md:shrink-0 md:translate-x-0 md:border-r md:border-brand-outline/50
          ${collapsed ? 'md:w-14' : 'md:w-56 lg:w-60 xl:w-64'}
          ${open ? 'translate-x-0 shadow-2xl' : '-translate-x-full md:translate-x-0 md:shadow-none'}
        `}
        aria-hidden={!open && !isTabletUp}
        aria-label="Navigation"
      >
        {/* Brand header */}
        <div className={`flex items-center gap-3 pt-safe md:pt-7 ${collapsed ? 'justify-center px-0 pb-3 md:px-0' : 'px-4 pb-5 md:px-5'}`}>
          <div className={`flex min-w-0 items-center gap-2.5 ${collapsed ? 'justify-center' : ''}`}>
            <BrandMark size={collapsed ? 28 : 36} />
            {!collapsed && (
              <div className="min-w-0">
                <p className="truncate text-[15px] font-bold tracking-tight text-brand-primary">
                  Notelikeus
                </p>
                <p className="text-chrome-label">Capture</p>
              </div>
            )}
          </div>
          {!collapsed && (
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

        {/* Navigation */}
        <nav className={`flex flex-1 flex-col overflow-y-auto pb-3 ${collapsed ? 'gap-4 px-1.5' : 'gap-7 px-2.5 md:px-3'}`}>
          <NavSection collapsed={collapsed}>
            {NAV_ITEMS.map(({ filter, label, Icon, iconClass, barClass }) => {
              const active = currentFilter === filter;
              const count = navCounts?.[filter];
              return (
                <NavButton
                  key={filter}
                  active={active}
                  collapsed={collapsed}
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
                      collapsed ? (
                        count
                      ) : (
                        <CountBadge count={count} active={active} />
                      )
                    ) : null
                  }
                />
              );
            })}
          </NavSection>

          {(onEditLabels || onOpenSettings) ? (
            <NavSection title={collapsed ? undefined : 'Manage'} collapsed={collapsed}>
              {onEditLabels ? (
                <NavButton
                  collapsed={collapsed}
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
                  collapsed={collapsed}
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

        {/* Bottom section */}
        <div className={`mt-auto border-t border-brand-outline/40 ${collapsed ? 'px-1.5 py-2.5' : 'px-4 py-4 pb-safe md:px-5 md:pb-6'}`}>
          {userEmail ? (
            <div className={`${collapsed ? 'flex flex-col items-center gap-2' : 'space-y-3'}`}>
              <div className={`flex min-w-0 items-center ${collapsed ? 'justify-center' : 'gap-2.5'}`}>
                <span
                  className={`flex shrink-0 items-center justify-center rounded-full bg-brand-primary/10 font-bold uppercase text-brand-primary transition-all duration-300 ${collapsed ? 'size-[26px] text-[10px]' : 'size-8 text-[12px]'}`}
                  title={collapsed ? userEmail : undefined}
                  aria-hidden
                >
                  {userEmail.charAt(0)}
                </span>
                {!collapsed && (
                  <div className="min-w-0 flex-1">
                    <p className="text-chrome-label">Signed in</p>
                    <p
                      className="truncate text-[13px] font-medium leading-snug tracking-tight text-brand-primary"
                      title={userEmail}
                    >
                      {userEmail}
                    </p>
                  </div>
                )}
              </div>
              {!collapsed && (
                <button
                  type="button"
                  onClick={onSignOut}
                  className="w-full rounded-xl bg-rose-500/15 px-3 py-2.5 text-center text-sm font-semibold text-rose-400 ring-1 ring-inset ring-rose-500/25 transition-colors hover:bg-rose-500/25 hover:text-rose-300"
                >
                  Sign out
                </button>
              )}
            </div>
          ) : (
            !collapsed ? (
              <button
                type="button"
                onClick={onSignIn}
                className="w-full rounded-xl bg-brand-primary px-3 py-2.5 text-sm font-semibold text-true-surface transition-opacity hover:opacity-90"
              >
                Sign in with Google
              </button>
            ) : null
          )}
        </div>

        {/* Collapse toggle */}
        <div className={`border-t border-brand-outline/40 ${collapsed ? 'flex justify-center px-1.5 py-2' : 'flex justify-end px-2 py-1.5'}`}>
          <button
            type="button"
            onClick={onToggleCollapse}
            className={`flex items-center justify-center rounded-lg text-brand-muted/40 transition-all duration-300 hover:bg-brand-primary/10 hover:text-brand-muted ${collapsed ? 'size-8' : 'size-7'}`}
            aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
            title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          >
            <ChevronRightIcon
              size={collapsed ? 15 : 13}
              className={`transition-transform duration-300 ${collapsed ? '' : 'rotate-180'}`}
            />
          </button>
        </div>
      </aside>
    </>
  );
}
