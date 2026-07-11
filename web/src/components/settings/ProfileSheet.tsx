import type { ReactNode } from 'react';
import { BrandMark } from '@/components/brand/BrandMark';
import { ResponsiveSheet } from '@/components/layout/ResponsiveSheet';
import type { ViewColumns } from '@/store/uiStore';
import type { AppTheme } from '@/store/settingsStore';
import type { CloudSyncStatus } from '@/hooks/useCloudSync';
import { formatSyncStatus } from '@/lib/cloud/syncStatusLabel';
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
}: {
  title: string;
  subtitle?: string;
  icon?: ReactNode;
  onClick?: () => void;
  trailing?: ReactNode;
}) {
  const Tag = onClick ? 'button' : 'div';
  return (
    <Tag
      type={onClick ? 'button' : undefined}
      onClick={onClick}
      className={`flex w-full min-h-14 items-center gap-4 px-4 py-3 text-left transition-colors ${
        onClick ? 'hover:bg-white/5 active:bg-white/10' : ''
      }`}
    >
      {icon ? <SettingsLeadingIcon>{icon}</SettingsLeadingIcon> : null}
      <div className="min-w-0 flex-1">
        <p className="text-base text-brand-primary">{title}</p>
        {subtitle ? <p className="text-sm text-brand-muted">{subtitle}</p> : null}
      </div>
      {trailing ? <div className="shrink-0">{trailing}</div> : null}
      {onClick && !trailing ? (
        <span className="text-brand-muted/45" aria-hidden>
          ›
        </span>
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
  2: 'Grid (2 columns)',
  3: 'Grid (3 columns)',
};

const THEME_ORDER: AppTheme[] = ['auto', 'light', 'dark', 'true_dark', 'midnight', 'forest'];

interface ProfileSheetProps {
  open: boolean;
  onClose: () => void;
  noteCount: number;
  viewColumns: ViewColumns;
  sortOrder: 'manual' | 'newest' | 'oldest';
  onViewColumnsCycle: () => void;
  onSortOrderCycle: () => void;
  appTheme: AppTheme;
  onAppThemeCycle: () => void;
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
  onViewColumnsCycle,
  onSortOrderCycle,
  appTheme,
  onAppThemeCycle,
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
          onClick={onViewColumnsCycle}
          icon={<GridViewIcon size={24} />}
        />
        <SettingsRow
          title="Sort order"
          subtitle={SORT_LABELS[sortOrder]}
          onClick={onSortOrderCycle}
          icon={<SortIcon size={24} />}
        />

        <div className="mx-4 mt-2 border-t border-brand-outline/60" />
        <SettingsSectionHeader title="Appearance" />
        <SettingsRow
          title="App theme"
          subtitle={appTheme.replace('_', ' ').replace(/\b\w/g, (l) => l.toUpperCase())}
          onClick={onAppThemeCycle}
          icon={<PaletteIcon size={24} />}
        />

        <div className="mx-4 mt-2 border-t border-brand-outline/60" />
        <SettingsSectionHeader title="Insights" />
        <SettingsRow title="Total notes" subtitle={String(noteCount)} icon={<NotesIcon size={24} />} />
        <SettingsRow
          title="Cloud sync"
          subtitle={isGoogleAccount ? `${formatSyncStatus(syncStatus)} · ${syncedNoteCount} notes` : 'Not signed in'}
          icon={<CloudIcon size={24} />}
        />

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
              icon={<AccountIcon size={24} />}
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
          icon={<SyncIcon size={24} />}
        />
        <SettingsRow
          title="Restore from cloud"
          subtitle="Merge notes from your Google account"
          onClick={canSync ? onRestore : undefined}
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

export { THEME_ORDER };
