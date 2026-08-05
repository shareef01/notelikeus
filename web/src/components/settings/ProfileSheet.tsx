import type { ReactNode } from 'react';
import { useEffect } from 'react';
import { version } from '../../../package.json';
import { BrandMark } from '@/components/brand/BrandMark';
import { ThemePicker } from '@/components/settings/ThemePicker';
import { useFocusTrap } from '@/hooks/useFocusTrap';
import type { ViewColumns } from '@/store/uiStore';
import type { AppTheme } from '@/store/settingsStore';
import {
  GridViewIcon,
  SortIcon,
  NotesIcon,
  CloudIcon,
  AccountIcon,
  BackupIcon,
  AddIcon,
  PrivacyIcon,
  InfoIcon,
  LogoutIcon,
  ChevronRightIcon,
  CloseIcon,
} from '@/components/icons/Icons';

function SettingsSection({
  title,
  children,
}: {
  title: string;
  children: ReactNode;
}) {
  return (
    <section>
      <h3 className="px-1 pb-2 text-chrome-label">
        {title}
      </h3>
      <div className="overflow-hidden rounded-note border border-brand-outline/40 bg-true-surface-variant/35 divide-y divide-brand-outline/35">
        {children}
      </div>
    </section>
  );
}

function SettingsLeadingIcon({ children }: { children: ReactNode }) {
  return (
    <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-brand-primary/10 text-brand-primary/75">
      {children}
    </span>
  );
}

function SettingsRow({
  title,
  subtitle,
  icon,
  onClick,
  trailing,
  disabled = false,
  destructive = false,
}: {
  title: string;
  subtitle?: string;
  icon?: ReactNode;
  onClick?: () => void;
  trailing?: ReactNode;
  disabled?: boolean;
  destructive?: boolean;
}) {
  const Tag = onClick ? 'button' : 'div';
  return (
    <Tag
      type={onClick ? 'button' : undefined}
      onClick={disabled ? undefined : onClick}
      disabled={onClick ? disabled : undefined}
      className={`flex w-full min-h-[3.5rem] items-center gap-3.5 px-4 py-3.5 text-left transition-colors sm:min-h-16 sm:px-5 sm:py-4 ${
        onClick && !disabled ? 'hover:bg-brand-primary/[0.04] active:bg-brand-primary/[0.07]' : ''
      } ${disabled ? 'opacity-40' : ''}`}
    >
      {icon ? <SettingsLeadingIcon>{icon}</SettingsLeadingIcon> : null}
      <div className="min-w-0 flex-1">
        <p
          className={`truncate text-[15px] font-medium leading-snug tracking-tight ${
            destructive ? 'text-red-300' : 'text-brand-primary'
          }`}
        >
          {title}
        </p>
        {subtitle ? (
          <p className="mt-0.5 truncate text-[13px] leading-snug text-brand-muted">{subtitle}</p>
        ) : null}
      </div>
      {trailing ? <div className="shrink-0">{trailing}</div> : null}
      {onClick && !trailing ? (
        <ChevronRightIcon size={18} className="shrink-0 text-brand-muted/45" />
      ) : null}
    </Tag>
  );
}

const SORT_LABELS = {
  manual: 'Manual order',
  newest: 'Newest first',
  oldest: 'Oldest first',
} as const;

const VIEW_LABELS: Record<ViewColumns, string> = {
  1: 'List',
  2: 'Grid',
  3: 'Compact',
};

interface ProfileSheetProps {
  open: boolean;
  onClose: () => void;
  noteCount: number;
  viewColumns: ViewColumns;
  sortOrder: 'manual' | 'newest' | 'oldest';
  onViewColumnsCycle: () => void;
  onSortOrderCycle: () => void;
  appTheme: AppTheme;
  onAppThemeChange: (theme: AppTheme) => void;
  isGoogleAccount: boolean;
  isGuest: boolean;
  userEmail: string | null;
  syncStatus: string;
  syncedNoteCount: number;
  onExportBackup: () => void;
  onImportBackup: () => void;
  onPrivacyPolicy: () => void;
  onSignIn: () => void;
  onSignUp: () => void;
  onSignOut: () => void;
}

export function ProfileSheet({
  open,
  onClose,
  noteCount,
  viewColumns,
  sortOrder,
  onViewColumnsCycle,
  onSortOrderCycle,
  appTheme,
  onAppThemeChange,
  isGoogleAccount,
  isGuest,
  userEmail,
  syncStatus,
  syncedNoteCount,
  onExportBackup,
  onImportBackup,
  onPrivacyPolicy,
  onSignIn,
  onSignUp,
  onSignOut,
}: ProfileSheetProps) {
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
    <div
      ref={panelRef}
      className="fixed inset-0 z-50 flex flex-col bg-true-surface animate-in fade-in duration-200"
      role="dialog"
      aria-modal="true"
      aria-label="Settings"
    >
      <header className="flex shrink-0 items-center gap-3 border-b border-brand-outline/40 px-4 py-3.5 pt-safe sm:px-6 lg:px-8">
        <BrandMark size={40} />
        <div className="min-w-0 flex-1">
          <p className="text-lg font-semibold tracking-tight text-brand-primary">Settings</p>
          <p className="mt-0.5 truncate text-[13px] leading-snug tracking-tight text-brand-secondary">
            Sync &amp; preferences for your notes
          </p>
        </div>
        <button
          type="button"
          onClick={onClose}
          className="flex size-10 shrink-0 items-center justify-center rounded-full text-brand-muted transition-colors hover:bg-brand-primary/5 hover:text-brand-primary"
          aria-label="Close settings"
        >
          <CloseIcon size={22} />
        </button>
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain pb-safe">
        <div className="mx-auto grid w-full max-w-content gap-5 px-4 py-5 pb-12 sm:px-6 lg:grid-cols-2 lg:gap-6 lg:px-8 xl:grid-cols-[1fr_1.15fr]">
          <div className="flex flex-col gap-5">
            <SettingsSection title="Layout">
              <SettingsRow
                title="Default view"
                subtitle={VIEW_LABELS[viewColumns]}
                onClick={onViewColumnsCycle}
                icon={<GridViewIcon size={18} />}
              />
              <SettingsRow
                title="Sort order"
                subtitle={SORT_LABELS[sortOrder]}
                onClick={onSortOrderCycle}
                icon={<SortIcon size={18} />}
              />
            </SettingsSection>

            <SettingsSection title="Appearance">
              <ThemePicker value={appTheme} onChange={onAppThemeChange} />
            </SettingsSection>

            <SettingsSection title="Insights">
              <div className="grid grid-cols-2 gap-px bg-brand-outline/35">
                <div className="flex flex-col gap-1 bg-true-surface-variant/35 px-4 py-4 sm:py-5">
                  <div className="flex items-center gap-2 text-brand-primary/70">
                    <NotesIcon size={16} />
                    <span className="text-chrome-label">
                      Notes
                    </span>
                  </div>
                  <p className="text-2xl font-semibold tabular-nums tracking-tight text-brand-primary sm:text-3xl">
                    {noteCount}
                  </p>
                </div>
                <div className="flex flex-col gap-1 bg-true-surface-variant/35 px-4 py-4 sm:py-5">
                  <div className="flex items-center gap-2 text-brand-primary/70">
                    <CloudIcon size={16} />
                    <span className="text-chrome-label">Cloud</span>
                  </div>
                  <p className="truncate text-base font-medium text-brand-primary sm:text-lg">
                    {isGoogleAccount
                      ? syncStatus
                      : isGuest
                        ? 'Guest session'
                        : 'Signed out'}
                  </p>
                  <p className="text-sm text-brand-muted">
                    {isGoogleAccount
                      ? `${syncedNoteCount} synced`
                      : isGuest
                        ? 'Notes stay on this device'
                        : 'Sign in to sync'}
                  </p>
                </div>
              </div>
            </SettingsSection>

            <SettingsSection title="About">
              <SettingsRow
                title="Privacy policy"
                subtitle="How your data is handled"
                onClick={onPrivacyPolicy}
                icon={<PrivacyIcon size={18} />}
              />
              <SettingsRow title="Version" subtitle={`${version} (web)`} icon={<InfoIcon size={18} />} />
            </SettingsSection>
          </div>

          <div className="flex flex-col gap-5">
            <SettingsSection title="Account">
              {isGuest ? (
                <SettingsRow
                  title="Browsing as a guest"
                  subtitle="Notes aren't synced or backed up. Sign in to keep them across devices."
                  icon={<AccountIcon size={18} />}
                />
              ) : null}
              {isGoogleAccount && userEmail ? (
                <>
                  <SettingsRow
                    title={userEmail}
                    subtitle="Signed in"
                    icon={<AccountIcon size={18} />}
                  />
                  <SettingsRow
                    title="Sign out"
                    subtitle="Stop syncing notes on this browser"
                    onClick={onSignOut}
                    icon={<LogoutIcon size={18} />}
                    destructive
                  />
                </>
              ) : (
                <>
                  <SettingsRow
                    title="Sign in"
                    subtitle="Sync notes across devices"
                    onClick={onSignIn}
                    icon={<AccountIcon size={18} />}
                  />
                  <SettingsRow
                    title="Create account"
                    subtitle="Set up cloud backup"
                    onClick={onSignUp}
                    icon={<AccountIcon size={18} />}
                  />
                </>
              )}
              <SettingsRow
                title="Export backup"
                subtitle="Download notes as JSON"
                onClick={onExportBackup}
                icon={<BackupIcon size={18} />}
              />
              <SettingsRow
                title="Import backup"
                subtitle="Merge notes from a JSON file"
                onClick={onImportBackup}
                icon={<AddIcon size={18} />}
              />
            </SettingsSection>
          </div>
        </div>
      </div>
    </div>
  );
}

export { THEME_ORDER } from '@/components/settings/ThemePicker';
