/** Matches Android `NoteBackupExporter.BACKUP_VERSION`. */
export const BACKUP_VERSION = 3;

/** Soft caps to avoid OOM / tab hang on hostile backup files. */
export const MAX_BACKUP_FILE_BYTES = 10 * 1024 * 1024;
export const MAX_BACKUP_NOTES = 5_000;
