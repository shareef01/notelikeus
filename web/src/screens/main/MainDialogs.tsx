import type { ViewColumns } from '@/store/uiStore';
import type { BackupExportOutcome } from '@/lib/backup/bundle/bundleTransfer';
import { BulkDeleteDialog } from '@/components/notes/BulkDeleteDialog';
import { EmptyTrashDialog } from '@/components/notes/EmptyTrashDialog';
import { PrivacyPolicyDialog } from '@/components/settings/PrivacyPolicyDialog';
import { ProfileSheet } from '@/components/settings/ProfileSheet';
import { DeleteAccountDialog } from '@/components/settings/DeleteAccountDialog';
import { ConfirmIdentityDialog } from '@/components/auth/ConfirmIdentityDialog';
import type { IdentityCheck } from '@/screens/main/useAccountActions';
import { SignOutDialog } from '@/components/settings/SignOutDialog';
import { SortSheet } from '@/components/layout/SortSheet';

/** Which of the mutually-exclusive overlays is open. */
export interface MainDialogState {
  profile: boolean;
  signOutConfirm: boolean;
  deleteAccountConfirm: boolean;
  privacyPolicy: boolean;
  emptyTrashConfirm: boolean;
  bulkDeleteConfirm: boolean;
  sortSheet: boolean;
}

export const NO_DIALOGS_OPEN: MainDialogState = {
  profile: false,
  signOutConfirm: false,
  deleteAccountConfirm: false,
  privacyPolicy: false,
  emptyTrashConfirm: false,
  bulkDeleteConfirm: false,
  sortSheet: false,
};

interface MainDialogsProps {
  open: MainDialogState;
  onOpenChange: (next: Partial<MainDialogState>) => void;

  noteCount: number;
  trashedCount: number;
  selectedCount: number;

  viewColumns: ViewColumns;
  onViewColumnsCycle: () => void;
  sortOrder: React.ComponentProps<typeof ProfileSheet>['sortOrder'];
  /** Every surface chooses rather than cycles now: the toolbar's sheet and the settings group. */
  onSortSelect: (order: 'manual' | 'newest' | 'oldest') => void;

  theme: React.ComponentProps<typeof ProfileSheet>['theme'];
  onThemeBaseChange: React.ComponentProps<typeof ProfileSheet>['onThemeBaseChange'];
  onAccentChange: React.ComponentProps<typeof ProfileSheet>['onAccentChange'];
  onAmoledChange: React.ComponentProps<typeof ProfileSheet>['onAmoledChange'];

  isSignedIn: boolean;
  isGuest: boolean;
  userEmail: string | null;
  syncStatus: React.ComponentProps<typeof ProfileSheet>['syncStatus'];

  onExportBackup: () => void;
  onExportCompleteBackup: () => Promise<BackupExportOutcome | null>;
  onExportDeviceBackup: () => void;
  onShowDiagnostics: () => void;
  onImportBackup: () => void;
  onSignIn: () => void;
  onSignUp: () => void;
  onSignOut: (deleteCloudData: boolean) => void;
  onDeleteAccount: () => Promise<void>;
  /** The action waiting for "confirm it's you", or null (AUD-005). */
  identityCheck: IdentityCheck | null;
  onConfirmIdentity: () => Promise<void>;
  onCancelIdentityCheck: () => void;
  onEmptyTrash: () => void;
  onBulkPermanentDelete: () => void;
}

/**
 * Every overlay the notes screen can put on top of itself.
 *
 * The counterpart of `MainDialogs.kt`, and split out for the reason that file records: five
 * dialogs interleaved with the list markup made the actual page structure hard to find, and each
 * carried two or three lines of open/close plumbing that has nothing to do with the list.
 *
 * The open flags travel as one object rather than five booleans so a caller cannot leave two
 * overlays open at once by forgetting to close the first — [onOpenChange] takes a patch, and
 * closing is `{ thing: false }`.
 */
export function MainDialogs({
  open,
  onOpenChange,
  noteCount,
  trashedCount,
  selectedCount,
  viewColumns,
  onViewColumnsCycle,
  sortOrder,
  onSortSelect,
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
  onSignIn,
  onSignUp,
  onSignOut,
  onDeleteAccount,
  identityCheck,
  onConfirmIdentity,
  onCancelIdentityCheck,
  onEmptyTrash,
  onBulkPermanentDelete,
}: MainDialogsProps) {
  return (
    <>
      <ProfileSheet
        open={open.profile}
        onClose={() => onOpenChange({ profile: false })}
        noteCount={noteCount}
        viewColumns={viewColumns}
        sortOrder={sortOrder}
        onViewColumnsCycle={onViewColumnsCycle}
        onSortOrderSelect={onSortSelect}
        theme={theme}
        onThemeBaseChange={onThemeBaseChange}
        onAccentChange={onAccentChange}
        onAmoledChange={onAmoledChange}
        isSignedIn={isSignedIn}
        isGuest={isGuest}
        userEmail={userEmail}
        syncStatus={syncStatus}
        onExportBackup={onExportBackup}
        onExportCompleteBackup={() => void onExportCompleteBackup()}
        onExportDeviceBackup={onExportDeviceBackup}
        onShowDiagnostics={onShowDiagnostics}
        onImportBackup={onImportBackup}
        onPrivacyPolicy={() => onOpenChange({ privacyPolicy: true })}
        onSignIn={onSignIn}
        onSignUp={onSignUp}
        onSignOut={() => onOpenChange({ signOutConfirm: true })}
        onDeleteAccount={() => onOpenChange({ deleteAccountConfirm: true })}
      />

      <SortSheet
        open={open.sortSheet}
        onClose={() => onOpenChange({ sortSheet: false })}
        sortOrder={sortOrder}
        onSelect={onSortSelect}
      />

      <SignOutDialog
        open={open.signOutConfirm}
        onCancel={() => onOpenChange({ signOutConfirm: false })}
        onSignOut={() => onSignOut(false)}
        onSignOutAndDelete={() => onSignOut(true)}
      />

      <DeleteAccountDialog
        open={open.deleteAccountConfirm}
        onCancel={() => onOpenChange({ deleteAccountConfirm: false })}
        onExportBackup={onExportCompleteBackup}
        onConfirm={onDeleteAccount}
      />

      <ConfirmIdentityDialog
        open={identityCheck !== null}
        email={userEmail}
        action={identityCheck === 'deleteAccount' ? 'delete your account' : 'delete your cloud data'}
        onCancel={onCancelIdentityCheck}
        onConfirmed={onConfirmIdentity}
      />

      <BulkDeleteDialog
        open={open.bulkDeleteConfirm}
        noteCount={selectedCount}
        onCancel={() => onOpenChange({ bulkDeleteConfirm: false })}
        onConfirm={onBulkPermanentDelete}
      />

      <EmptyTrashDialog
        open={open.emptyTrashConfirm}
        noteCount={trashedCount}
        onCancel={() => onOpenChange({ emptyTrashConfirm: false })}
        onConfirm={onEmptyTrash}
      />

      <PrivacyPolicyDialog
        open={open.privacyPolicy}
        onClose={() => onOpenChange({ privacyPolicy: false })}
      />
    </>
  );
}
