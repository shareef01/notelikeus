import type { ReactNode } from 'react';
import { useState } from 'react';
import { BrandMark } from '@/components/brand/BrandMark';
import { ResponsiveSheet } from '@/components/layout/ResponsiveSheet';
import type { ViewColumns } from '@/store/uiStore';
import type { AppTheme } from '@/store/settingsStore';
import type { CloudSyncStatus } from '@/hooks/useCloudSync';
import { formatSyncStatus } from '@/lib/cloud/syncStatusLabel';
import { SettingsOptionPicker } from '@/components/settings/SettingsOptionPicker';
import {
  ThemeSwatch,
  ThemePickerGrid,
  THEME_LABELS,
} from '@/components/settings/ThemeSwatch';
import { GoogleIcon } from '@/components/icons/GoogleIcon';
import {
  GridViewIcon,
  SortIcon,
  PaletteIcon,
  NotesIcon,
  CloudIcon,
  AccountIcon,
  SyncIcon,
  BackupIcon,
  AddIcon,
  PrivacyIcon,
  InfoIcon,
} from '@/components/icons/Icons';

/**
 * Settings Sheet Overhaul
 * Reorganized into a disciplined list layout synchronized with Android Elite standards.
 */
function SettingsSectionHeader({
  title,
  isFirst = false,
}: {
  title: string;
  isFirst?: boolean;
}) {
  return (
    <h3
      className={`px-4 pb-2 text-[12px] font-semibold uppercase tracking-[0.8px] text-brand-muted/65 ${
        isFirst ? 'pt-4' : 'pt-6'
      }`}
    >
      {title}
    </h3>
  );
}

function SettingsLeadingIcon({ children }: { children: ReactNode }) {
  return (
    <span className="flex size-6 shrink-0 items-center justify-center text-brand-primary/60">
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
}: {
  title: string;
  subtitle?: string;
  icon?: ReactNode;
  onClick?: () => void;
  trailing?: ReactNode;
  disabled?: boolean;
}) {
  const isInteractive = Boolean(onClick) && !disabled;
  const Tag = isInteractive ? 'button' : 'div';
  return (
    <Tag
      type={isInteractive ? 'button' : undefined}
      onClick={isInteractive ? onClick : undefined}
      aria-disabled={disabled || !onClick ? true : undefined}
      className={`flex w-full min-h-14 items-center gap-4 px-4 py-3 text-left transition-colors ${
        isInteractive ? 'interactive-hover focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-brand-primary/50' : 'cursor-default opacity-50'
      }`}
    >
      {icon ? <SettingsLeadingIcon>{icon}</SettingsLeadingIcon> : null}
      <div className="min-w-0 flex-1">
        <p className="text-base text-brand-primary">{title}</p>
        {subtitle ? <p className="text-sm text-brand-muted">{subtitle}</p> : null}
      </div>
      {trailing ? <div className="shrink-0">{trailing}</div> : null}
      {isInteractive && !trailing ? (
        <span className="text-brand-muted/45" aria-hidden>
          ›
        </span>
      ) : null}
    </Tag>
  );
}

const SORT_LABELS = {
  manual: 'Manual Sort',
  newest: 'Newest first',
  oldest: 'Oldest first',
} as const;

const VIEW_ORDER: ViewColumns[] = [1, 2, 3];

const VIEW_LABELS: Record<ViewColumns, string> = {
  1: 'List',
  2: 'Grid (2 columns)',
  3: 'Grid (3 columns)',
};

const SORT_ORDER = ['manual', 'newest', 'oldest'] as const;
type SortOrder = (typeof SORT_ORDER)[number];

interface ProfileSheetProps {
  open: boolean;
  onClose: () => void;
  noteCount: number;
  viewColumns: ViewColumns;
  sortOrder: SortOrder;
  onViewColumnsChange: (columns: ViewColumns) => void;
  onSortOrderChange: (order: SortOrder) => void;
  appTheme: AppTheme;
  onAppThemeChange: (theme: AppTheme) => void;
  cloudAutoSyncEnabled: boolean;
  onCloudAutoSyncChange: (enabled: boolean) => void;
  isGoogleAccount: boolean;
  userEmail: string | null;
  syncStatus: CloudSyncStatus;
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
  onViewColumnsChange,
  onSortOrderChange,
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
  const [viewPickerOpen, setViewPickerOpen] = useState(false);
  const [sortPickerOpen, setSortPickerOpen] = useState(false);
  const [themePickerOpen, setThemePickerOpen] = useState(false);

  if (!open) return null;

  const canSync = isGoogleAccount && !isSyncing;

  return (
    <ResponsiveSheet open={open} onClose={onClose} ariaLabel="Settings" maxWidthClass="md:max-w-md">
        <div className="flex items-center gap-4 px-6 py-4">
          <BrandMark size={56} />
          <div>
            <p className="text-xl font-bold tracking-tight">Notelikeus</p>
            <p className="text-sm text-brand-muted">Local notes app</p>
          </div>
        </div>

        <div className="mx-4 border-t border-brand-outline/60" />

        <SettingsSectionHeader title="Layout" isFirst />
        <SettingsRow
          title="Default view"
          subtitle={VIEW_LABELS[viewColumns]}
          onClick={() => {
            setSortPickerOpen(false);
            setThemePickerOpen(false);
            setViewPickerOpen((open) => !open);
          }}
          icon={<GridViewIcon size={24} />}
        />
        {viewPickerOpen ? (
          <SettingsOptionPicker
            options={VIEW_ORDER}
            labels={VIEW_LABELS}
            active={viewColumns}
            ariaLabel="Choose default view"
            onSelect={(columns) => {
              onViewColumnsChange(columns);
              setViewPickerOpen(false);
            }}
          />
        ) : null}
        <SettingsRow
          title="Sort order"
          subtitle={SORT_LABELS[sortOrder]}
          onClick={() => {
            setViewPickerOpen(false);
            setThemePickerOpen(false);
            setSortPickerOpen((open) => !open);
          }}
          icon={<SortIcon size={24} />}
        />
        {sortPickerOpen ? (
          <SettingsOptionPicker
            options={SORT_ORDER}
            labels={SORT_LABELS}
            active={sortOrder}
            ariaLabel="Choose sort order"
            onSelect={(order) => {
              onSortOrderChange(order);
              setSortPickerOpen(false);
            }}
          />
        ) : null}

        <div className="mx-4 mt-2 border-t border-brand-outline/60" />
        <SettingsSectionHeader title="Appearance" />
        <SettingsRow
          title="App theme"
          subtitle={THEME_LABELS[appTheme]}
          onClick={() => {
            setViewPickerOpen(false);
            setSortPickerOpen(false);
            setThemePickerOpen((open) => !open);
          }}
          icon={<PaletteIcon size={24} />}
          trailing={<ThemeSwatch theme={appTheme} />}
        />
        {themePickerOpen ? (
          <ThemePickerGrid
            activeTheme={appTheme}
            onSelect={(theme) => {
              onAppThemeChange(theme);
              setThemePickerOpen(false);
            }}
          />
        ) : null}

        <div className="mx-4 mt-2 border-t border-brand-outline/60" />
        <SettingsSectionHeader title="Insights" />
        <div className="grid grid-cols-2 gap-3 px-4">
          <div className="rounded-note border border-brand-outline/30 bg-true-surface-variant p-4">
            <div className="mb-2 flex items-center gap-2 text-brand-muted/70">
              <NotesIcon size={18} />
              <span className="text-xs font-medium uppercase tracking-wide">Total notes</span>
            </div>
            <p className="text-2xl font-bold tracking-tight text-brand-primary">{noteCount}</p>
          </div>
          <div className="rounded-note border border-brand-outline/30 bg-true-surface-variant p-4">
            <div className="mb-2 flex items-center gap-2 text-brand-muted/70">
              <CloudIcon size={18} />
              <span className="text-xs font-medium uppercase tracking-wide">Cloud sync</span>
            </div>
            <p className="text-sm font-semibold leading-snug text-brand-primary">
              {isGoogleAccount ? formatSyncStatus(syncStatus) : 'Not signed in'}
            </p>
            {isGoogleAccount ? (
              <p className="mt-1 text-xs text-brand-muted">{syncedNoteCount} notes synced</p>
            ) : null}
          </div>
        </div>

        <div className="mx-4 mt-2 border-t border-brand-outline/60" />
        <SettingsSectionHeader title="Account" />
        {isGoogleAccount && userEmail ? (
          <>
            <SettingsRow title={userEmail} subtitle="Signed in" icon={<AccountIcon size={24} />} />
            <SettingsRow title="Sign out" subtitle="Stop using this Google account" onClick={onSignOut} />
          </>
        ) : (
          <>
            <SettingsRow
              title="Sign in with Google"
              subtitle="Sync notes across devices"
              onClick={onSignIn}
              icon={<GoogleIcon size={22} />}
            />
            <SettingsRow
              title="Create account"
              subtitle="Set up sync with Google"
              onClick={onSignUp}
              icon={<AccountIcon size={24} />}
            />
          </>
        )}
        <SettingsRow
          title="Auto-sync"
          subtitle="Save edits to the cloud automatically"
          icon={<SyncIcon size={24} />}
          trailing={
            <input
              type="checkbox"
              checked={cloudAutoSyncEnabled}
              disabled={!isGoogleAccount}
              onChange={(event) => onCloudAutoSyncChange(event.target.checked)}
              className="size-5 rounded accent-brand-primary"
              aria-label="Auto-sync"
            />
          }
        />
        <SettingsRow
          title="Sync now"
          subtitle={isSyncing ? 'Syncing…' : 'Upload all notes to the cloud'}
          onClick={canSync ? onSyncNow : undefined}
          disabled={!canSync}
          icon={<SyncIcon size={24} />}
        />
        <SettingsRow
          title="Restore from cloud"
          subtitle="Merge notes from your Google account"
          onClick={canSync ? onRestore : undefined}
          disabled={!canSync}
          icon={<SyncIcon size={24} />}
        />
        <SettingsRow
          title="Export backup"
          subtitle="Download notes as JSON"
          onClick={onExportBackup}
          icon={<BackupIcon size={24} />}
        />
        <SettingsRow
          title="Import backup"
          subtitle="Merge notes from a JSON backup file"
          onClick={onImportBackup}
          icon={<AddIcon size={24} />}
        />

        <div className="mx-4 mt-2 border-t border-brand-outline/60" />
        <SettingsSectionHeader title="About" />
        <SettingsRow title="Privacy policy" subtitle="How your data is handled" onClick={onPrivacyPolicy} icon={<PrivacyIcon size={24} />} />
        <SettingsRow title="Version" subtitle="1.0.0 (web)" icon={<InfoIcon size={24} />} />

        <div className="h-8" />
    </ResponsiveSheet>
  );
}

export { THEME_ORDER, THEME_LABELS } from '@/components/settings/ThemeSwatch';
