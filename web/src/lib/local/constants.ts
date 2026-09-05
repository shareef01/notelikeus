/** IndexedDB database for web local-first note storage (Phase 2 migration). */
export const NOTES_DB_NAME = 'notelikeus-notes';
export const NOTES_DB_VERSION = 1;

export const NOTES_STORE = 'notes';
export const META_STORE = 'meta';

/** Namespace for guest-mode notes — never used as a cloud user id. */
export const GUEST_OWNER_ID = '__guest__';
