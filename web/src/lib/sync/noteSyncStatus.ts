/**
 * The save & sync vocabulary of DESIGN_SYSTEM §5.1 for one note, shared by every surface that
 * shows it. "Synced" requires the server to have accepted the note's latest edit: being signed in
 * and online says nothing about whether this edit got there.
 */
export type NoteSyncStatus =
  | 'save-failed'
  | 'saving'
  | 'unsaved'
  | 'on-device'
  | 'offline'
  | 'syncing'
  | 'sync-failed'
  | 'waiting'
  | 'synced';

export interface NoteSyncInput {
  saveFailed: boolean;
  isSaving: boolean;
  isSavedLocally: boolean;
  /** Signed in with cloud sync, as opposed to a guest whose notes live only here. */
  isSignedIn: boolean;
  isOnline: boolean;
  /** The note holds an edit or an image the cloud has not confirmed. */
  isPending: boolean;
  /** This account's queue is being pushed right now. */
  isFlushing: boolean;
  /** This account's last push left edits behind; they are retried on the next sync. */
  flushFailed: boolean;
}

export function noteSyncStatus(input: NoteSyncInput): NoteSyncStatus {
  if (input.saveFailed) return 'save-failed';
  if (input.isSaving) return 'saving';
  if (!input.isSavedLocally) return 'unsaved';
  if (!input.isSignedIn) return 'on-device';
  if (!input.isPending) return 'synced';
  if (!input.isOnline) return 'offline';
  if (input.isFlushing) return 'syncing';
  if (input.flushFailed) return 'sync-failed';
  return 'waiting';
}

export const NOTE_SYNC_LABELS: Record<NoteSyncStatus, string> = {
  'save-failed': 'Couldn’t save',
  saving: 'Saving…',
  unsaved: 'Not saved yet',
  'on-device': 'Saved on this device',
  offline: 'Offline — changes saved here',
  syncing: 'Syncing…',
  'sync-failed': 'Couldn’t sync — saved on this device',
  waiting: 'Waiting to sync',
  synced: 'Synced',
};

/** States that carry the time of the last edit after the label. */
export function showsEditedTime(status: NoteSyncStatus): boolean {
  return status === 'on-device' || status === 'synced';
}
