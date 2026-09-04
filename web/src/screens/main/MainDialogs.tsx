import type { ViewColumns } from '@/store/uiStore';
import { BulkDeleteDialog } from '@/components/notes/BulkDeleteDialog';
import { EmptyTrashDialog } from '@/components/notes/EmptyTrashDialog';
import { PrivacyPolicyDialog } from '@/components/settings/PrivacyPolicyDialog';
import { ProfileSheet } from '@/components/settings/ProfileSheet';
import { SignOutDialog } from '@/components/settings/SignOutDialog';

/** Which of the mutually-exclusive overlays is open. */
export interface MainDialogState {
  profile: boolean;
  signOutConfirm: boolean;
  privacyPolicy: boolean;
  emptyTrashConfirm: boolean;
  bulkDeleteConfirm: boolean;
}

export const NO_DIALOGS_OPEN: MainDialogState = {
  profile: false,
  signOutConfirm: false,
  privacyPolicy: false,
  emptyTrashConfirm: false,
  bulkDeleteConfirm: false,
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
  onSortOrderCycle: () => void;

  theme: React.ComponentProps<typeof ProfileSheet>['theme'];
  onThemeBaseChange: React.ComponentProps<typeof ProfileSheet>['onThemeBaseChange'];
  onAccentChange: React.ComponentProps<typeof ProfileSheet>['onAccentChange'];
  onAmoledChange: React.ComponentProps<typeof ProfileSheet>['onAmoledChange'];

  isGoogleAccount: boolean;
  isGuest: boolean;
  userEmail: string | null;
  syncStatus: React.ComponentProps<typeof ProfileSheet>['syncStatus'];
  syncedNoteCount: number;

  onExportBackup: () => void | Promise<void>;
  onImportBackup: () => void;
  onSignIn: () => void;
  onSignUp: () => void;
  onSignOut: (deleteCloudData: boolean) => void;
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
  onSortOrderCycle,
  theme,
  onThemeBaseChange,
  onAccentChange,
  onAmoledChange,
  isGoogleAccount,
  isGuest,
  userEmail,
  syncStatus,
  syncedNoteCount,
  onExportBackup,
  onImportBackup,
  onSignIn,
  onSignUp,
  onSignOut,
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
        onSortOrderCycle={onSortOrderCycle}
        theme={theme}
        onThemeBaseChange={onThemeBaseChange}
        onAccentChange={onAccentChange}
        onAmoledChange={onAmoledChange}
        isGoogleAccount={isGoogleAccount}
        isGuest={isGuest}
        userEmail={userEmail}
        syncStatus={syncStatus}
        syncedNoteCount={syncedNoteCount}
        onExportBackup={onExportBackup}
        onImportBackup={onImportBackup}
        onPrivacyPolicy={() => onOpenChange({ privacyPolicy: true })}
        onSignIn={onSignIn}
        onSignUp={onSignUp}
        onSignOut={() => onOpenChange({ signOutConfirm: true })}
      />

      <SignOutDialog
        open={open.signOutConfirm}
        onCancel={() => onOpenChange({ signOutConfirm: false })}
        onSignOut={() => onSignOut(false)}
        onSignOutAndDelete={() => onSignOut(true)}
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
