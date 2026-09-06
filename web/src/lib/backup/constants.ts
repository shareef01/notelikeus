/** Matches Android `NoteBackupExporter.BACKUP_VERSION`. */
export const BACKUP_VERSION = 3;

/** Soft caps to avoid OOM / tab hang on hostile backup files. */
export const MAX_BACKUP_FILE_BYTES = 10 * 1024 * 1024;
export const MAX_BACKUP_NOTES = 5_000;
/**
 * Distinct label names accepted from one backup. Matches Kotlin's
 * `NoteBackupImporter.MAX_BACKUP_LABELS`; without it a 10 MB file of nothing but label strings
 * built a Set of roughly a million entries before any note was imported.
 */
export const MAX_BACKUP_LABELS = 2_000;

/**
 * Per-note caps applied on import, matching Android's `NoteBackupImporter` and the
 * `validate_note_payload` / `apply_note_change` Supabase boundary. Without these, an oversized
 * field imports fine but is rejected on upload, surfacing later as an opaque sync failure.
 */
export const MAX_NOTE_TITLE_CHARS = 2_000;
export const MAX_NOTE_CONTENT_CHARS = 100_000;
export const MAX_NOTE_CHECKLIST_ITEMS = 500;
export const MAX_NOTE_LABELS = 100;

/** Matches Kotlin `NoteBackupImporter.MAX_JSON_DEPTH`. */
export const MAX_JSON_DEPTH = 64;
