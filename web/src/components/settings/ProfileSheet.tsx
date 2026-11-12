import type { ReactNode } from 'react';
import { useBodyScrollLock } from '@/hooks/useBodyScrollLock';
import { version } from '../../../package.json';
import { BrandMark } from '@/components/brand/BrandMark';
import { ThemePicker } from '@/components/settings/ThemePicker';
import { useFocusTrap } from '@/hooks/useFocusTrap';
import { CHROME_FOCUS } from '@/lib/ui/focusStyles';
import type { ViewColumns } from '@/store/uiStore';
import type { AccentColor, ThemeBase, ThemePreference } from '@/store/settingsStore';
import {
  GridViewIcon,
  SortIcon,
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
      className={`flex w-full min-h-[3.5rem] items-center gap-3.5 px-4 py-3.5 text-left transition-colors sm:min-h-16 sm:px-5 sm:py-4 ${CHROME_FOCUS} ${
        onClick && !disabled ? 'hover:bg-brand-primary/[0.04] active:bg-brand-primary/[0.07]' : ''
      } ${disabled ? 'opacity-40' : ''}`}
    >
      {icon ? <SettingsLeadingIcon>{icon}</SettingsLeadingIcon> : null}
      <div className="min-w-0 flex-1">
        <p
          className={`truncate text-[15px] font-medium leading-snug tracking-tight ${
            destructive ? 'text-red-700 dark:text-red-300' : 'text-brand-primary'
          }`}
        >
          {title}
        </p>
        {subtitle ? (
          <p className="mt-0.5 text-[13px] leading-snug text-brand-muted">{subtitle}</p>
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

/** The order the view choices are offered in, matching the toolbar's chooser. */
const VIEW_OPTIONS: readonly ViewColumns[] = [2, 1, 3];

const noteCountText = (count: number) => (count === 1 ? '1 note' : `${count} notes`);

/**
 * A visible choice instead of a row that cycles on tap: the options are on screen before the tap,
 * not discovered one at a time after it. Same chip classes as the filter row.
 */
function SettingsChoice<T extends string | number>({
  label,
  icon,
  options,
  value,
  labelFor,
  onSelect,
}: {
  label: string;
  icon: ReactNode;
  options: readonly T[];
  value: T;
  labelFor: (option: T) => string;
  onSelect: (option: T) => void;
}) {
  return (
    <div className="flex flex-col gap-2 px-4 py-3 sm:px-5">
      <span className="flex items-center gap-2 text-sm font-medium text-brand-primary">
        {icon}
        {label}
      </span>
      <div className="flex flex-wrap gap-1.5" role="group" aria-label={label}>
        {options.map((option) => {
          const active = option === value;
          return (
            <button
              key={String(option)}
              type="button"
              aria-pressed={active}
              onClick={() => onSelect(option)}
              className={`filter-chip px-3 text-xs sm:px-3.5 ${CHROME_FOCUS} ${
                active ? 'filter-chip-active' : 'filter-chip-inactive'
              }`}
            >
              <span className="whitespace-nowrap">{labelFor(option)}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}

interface ProfileSheetProps {
  open: boolean;
  onClose: () => void;
  noteCount: number;
  viewColumns: ViewColumns;
  sortOrder: 'manual' | 'newest' | 'oldest';
  onViewColumnsSelect: (columns: ViewColumns) => void;
  onSortOrderSelect: (order: 'manual' | 'newest' | 'oldest') => void;
  theme: ThemePreference;
  onThemeBaseChange: (base: ThemeBase) => void;
  onAccentChange: (accent: AccentColor) => void;
  onAmoledChange: (amoled: boolean) => void;
  isSignedIn: boolean;
  isGuest: boolean;
  userEmail: string | null;
  syncStatus: string;
  onExportBackup: () => void;
  onExportCompleteBackup: () => void;
  onExportDeviceBackup: () => void;
  onShowDiagnostics: () => void;
  onImportBackup: () => void;
  onPrivacyPolicy: () => void;
  onSignIn: () => void;
  onSignUp: () => void;
  onSignOut: () => void;
  onDeleteAccount: () => void;
}

export function ProfileSheet({
  open,
  onClose,
  noteCount,
  viewColumns,
  sortOrder,
  onViewColumnsSelect,
  onSortOrderSelect,
  theme,
  onThemeBaseChange,
  onAccentChange,
  onAmoledChange,
  isSignedIn,
  isGuest,
  userEmail,
  syncStatus,
  onExportBackup,
  onExportCompleteBackup,
  onExportDeviceBackup,
  onShowDiagnostics,
  onImportBackup,
  onPrivacyPolicy,
  onSignIn,
  onSignUp,
  onSignOut,
  onDeleteAccount,
}: ProfileSheetProps) {
  const panelRef = useFocusTrap<HTMLDivElement>(open, onClose);
  useBodyScrollLock(open);

  if (!open) return null;

  const signedIn = isSignedIn && !!userEmail;
  // Who you are, whether the cloud is reachable and how many notes there are, in one row. These
  // used to be a separate Insights section that repeated the account section above it.
  const accountTitle = signedIn ? userEmail! : isGuest ? 'Browsing as a guest' : 'Not signed in';
  const accountStatus = signedIn
    ? syncStatus === 'online'
      ? 'Connected'
      : 'Working offline'
    : isGuest
      ? 'Notes stay in this browser'
      : 'Notes stay on this device';

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
        {/*
          One column on a phone, read top to bottom: account, appearance, layout, data, about. On a
          wide screen Layout heads the second column so the two are about the same height. Sign out
          and Delete account come last on purpose, away from routine settings.
        */}
        <div className="mx-auto grid w-full max-w-content gap-5 px-4 py-5 pb-12 sm:px-6 lg:grid-cols-2 lg:gap-6 lg:px-8 xl:grid-cols-[1fr_1.15fr]">
          <div className="flex flex-col gap-5">
            <SettingsSection title="Account & Sync">
              <SettingsRow
                title={accountTitle}
                subtitle={`${accountStatus} · ${noteCountText(noteCount)}`}
                icon={<AccountIcon size={18} />}
              />
              {signedIn ? null : (
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
            </SettingsSection>

            <SettingsSection title="Appearance">
              <ThemePicker
                value={theme}
                onBaseChange={onThemeBaseChange}
                onAccentChange={onAccentChange}
                onAmoledChange={onAmoledChange}
              />
            </SettingsSection>
          </div>

          <div className="flex flex-col gap-5">
            <SettingsSection title="Layout">
              <SettingsChoice
                label="Default view"
                icon={<GridViewIcon size={18} />}
                options={VIEW_OPTIONS}
                value={viewColumns}
                labelFor={(columns) => VIEW_LABELS[columns]}
                onSelect={onViewColumnsSelect}
              />
              <SettingsChoice
                label="Sort order"
                icon={<SortIcon size={18} />}
                options={['manual', 'newest', 'oldest'] as const}
                value={sortOrder}
                labelFor={(order) => SORT_LABELS[order]}
                onSelect={onSortOrderSelect}
              />
            </SettingsSection>

            <SettingsSection title="Data & Backup">
              <SettingsRow
                title="Export complete backup"
                subtitle="All notes and images in one .nlkbak file"
                onClick={onExportCompleteBackup}
                icon={<BackupIcon size={18} />}
              />
              <SettingsRow
                title="Export backup from this device"
                subtitle="Works offline; leaves out images not on this device"
                onClick={onExportDeviceBackup}
                icon={<BackupIcon size={18} />}
              />
              <SettingsRow
                title="Export notes only"
                subtitle="Notes as JSON, without images"
                onClick={onExportBackup}
                icon={<BackupIcon size={18} />}
              />
              <SettingsRow
                title="Import backup"
                subtitle="Add notes from a .nlkbak or .json file"
                onClick={onImportBackup}
                icon={<AddIcon size={18} />}
              />
            </SettingsSection>

            <SettingsSection title="About">
              <SettingsRow
                title="Privacy policy"
                subtitle="How your data is handled"
                onClick={onPrivacyPolicy}
                icon={<PrivacyIcon size={18} />}
              />
              <SettingsRow
                title="Sync diagnostics"
                subtitle="Technical state for troubleshooting — no note content"
                onClick={onShowDiagnostics}
                icon={<InfoIcon size={18} />}
              />
              <SettingsRow title="Version" subtitle={`${version} (web)`} icon={<InfoIcon size={18} />} />
            </SettingsSection>

            {signedIn ? (
              <SettingsSection title="Account">
                <SettingsRow
                  title="Sign out"
                  subtitle="Stop syncing notes on this browser"
                  onClick={onSignOut}
                  icon={<LogoutIcon size={18} />}
                  destructive
                />
                <SettingsRow
                  title="Delete account"
                  subtitle="Permanently remove your account and notes"
                  onClick={onDeleteAccount}
                  icon={<AccountIcon size={18} />}
                  destructive
                />
              </SettingsSection>
            ) : null}
          </div>
        </div>
      </div>
    </div>
  );
}
