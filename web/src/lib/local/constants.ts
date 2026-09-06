/** IndexedDB database for web local-first note storage. */
export const NOTES_DB_NAME = 'notelikeus-notes';
/** v2: pending attachment blobs + owner-scoped note-id sequences. */
export const NOTES_DB_VERSION = 2;

export const NOTES_STORE = 'notes';
export const META_STORE = 'meta';
export const PENDING_ATTACHMENTS_STORE = 'pendingAttachments';
export const ID_SEQUENCE_STORE = 'idSequences';

/** Namespace for guest-mode notes — never used as a cloud user id. */
export const GUEST_OWNER_ID = '__guest__';
