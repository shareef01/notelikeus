import type { ReactNode } from 'react';
import { useEffect } from 'react';
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
  SyncIcon,
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
      <h3 className="px-1 pb-2 text-[11px] font-bold uppercase tracking-[1px] text-brand-muted/70">
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
        onClick && !disabled ? 'hover:bg-white/[0.04] active:bg-white/[0.07]' : ''
      } ${disabled ? 'opacity-40' : ''}`}
    >
      {icon ? <SettingsLeadingIcon>{icon}</SettingsLeadingIcon> : null}
      <div className="min-w-0 flex-1">
        <p
          className={`truncate text-[15px] font-medium leading-snug ${
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

function ThemeToggle({
  checked,
  disabled,
  onChange,
  label,
}: {
  checked: boolean;
  disabled?: boolean;
  onChange: (next: boolean) => void;
  label: string;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={`flex h-7 w-11 shrink-0 items-center rounded-full p-0.5 transition-colors disabled:opacity-40 ${
        checked ? 'justify-end bg-brand-primary' : 'justify-start bg-brand-outline/70'
      }`}
    >
      <span className="size-6 rounded-full bg-true-surface shadow-sm" />
    </button>
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
  3: 'Dense grid',
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
  cloudAutoSyncEnabled: boolean;
  onCloudAutoSyncChange: (enabled: boolean) => void;
  isGoogleAccount: boolean;
  userEmail: string | null;
  syncStatus: string;
  syncedNoteCount: number;
  onSyncNow: () => void;
  onRestore: () => void;
  onExportBackup: () => void;
  onImportBackup: () => void;
  onPrivacyPolicy: () => void;
  onSignIn: () => void;
  onSignUp: () => void;
  onSignOut: () => void;
  isSyncing: boolean;
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
  cloudAutoSyncEnabled,
  onCloudAutoSyncChange,
  isGoogleAccount,
  userEmail,
  syncStatus,
  syncedNoteCount,
  onSyncNow,
  onRestore,
  onExportBackup,
  onImportBackup,
  onPrivacyPolicy,
  onSignIn,
  onSignUp,
  onSignOut,
  isSyncing,
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

  const canSync = isGoogleAccount && !isSyncing;

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
          <p className="truncate text-xs text-brand-muted">Notelikeus · notes backed up to your account</p>
        </div>
        <button
          type="button"
          onClick={onClose}
          className="flex size-10 shrink-0 items-center justify-center rounded-full text-brand-muted transition-colors hover:bg-white/5 hover:text-brand-primary"
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
                    <span className="text-[11px] font-medium uppercase tracking-wide text-brand-muted">
                      Notes
                    </span>
                  </div>
                  <p className="text-2xl font-semibold tabular-nums text-brand-primary sm:text-3xl">
                    {noteCount}
                  </p>
                </div>
                <div className="flex flex-col gap-1 bg-true-surface-variant/35 px-4 py-4 sm:py-5">
                  <div className="flex items-center gap-2 text-brand-primary/70">
                    <CloudIcon size={16} />
                    <span className="text-[11px] font-medium uppercase tracking-wide text-brand-muted">
                      Cloud
                    </span>
                  </div>
                  <p className="truncate text-base font-medium text-brand-primary sm:text-lg">
                    {isGoogleAccount ? syncStatus : 'Signed out'}
                  </p>
                  <p className="text-sm text-brand-muted">
                    {isGoogleAccount ? `${syncedNoteCount} synced` : 'Sign in to sync'}
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
              <SettingsRow title="Version" subtitle="1.0.0 (web)" icon={<InfoIcon size={18} />} />
            </SettingsSection>
          </div>

          <div className="flex flex-col gap-5">
            <SettingsSection title="Account">
              {isGoogleAccount && userEmail ? (
                <>
                  <SettingsRow
                    title={userEmail}
                    subtitle="Signed in"
                    icon={<AccountIcon size={18} />}
                  />
                  <SettingsRow
                    title="Sign out"
                    subtitle="Clear local data for this account"
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
                title="Auto-sync"
                subtitle="Save edits to the cloud automatically"
                icon={<SyncIcon size={18} />}
                trailing={
                  <ThemeToggle
                    checked={cloudAutoSyncEnabled}
                    disabled={!isGoogleAccount}
                    onChange={onCloudAutoSyncChange}
                    label="Auto-sync"
                  />
                }
              />
              <SettingsRow
                title="Sync now"
                subtitle={isSyncing ? 'Syncing…' : 'Upload all notes to the cloud'}
                onClick={canSync ? onSyncNow : undefined}
                icon={<SyncIcon size={18} />}
                disabled={!canSync}
              />
              <SettingsRow
                title="Restore from cloud"
                subtitle="Merge notes from your account"
                onClick={canSync ? onRestore : undefined}
                icon={<CloudIcon size={18} />}
                disabled={!canSync}
              />
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
