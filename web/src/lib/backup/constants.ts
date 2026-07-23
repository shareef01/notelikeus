/** Matches Android `NoteBackupExporter.BACKUP_VERSION`. */
export const BACKUP_VERSION = 3;

/** Soft caps to avoid OOM / tab hang on hostile backup files. */
export const MAX_BACKUP_FILE_BYTES = 10 * 1024 * 1024;
export const MAX_BACKUP_NOTES = 5_000;

/**
 * Per-note caps applied on import, matching `isValidNote` in firestore.rules and Android's
 * `NoteBackupImporter`. Without these, an oversized field imports fine but is rejected by the
 * rules on upload, surfacing later as an opaque permission-denied that blocks sync.
 */
export const MAX_NOTE_TITLE_CHARS = 2_000;
export const MAX_NOTE_CONTENT_CHARS = 100_000;
export const MAX_NOTE_CHECKLIST_ITEMS = 500;
export const MAX_NOTE_LABELS = 100;
