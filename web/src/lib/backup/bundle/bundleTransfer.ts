/**
 * Turning notes into a `.nlkbak` bundle and back, on the web client.
 *
 * The two invariants this file exists to hold:
 *
 * 1. **A note never depends on its attachment.** Export skips bytes it cannot read; import drops
 *    attachments it cannot verify. Neither loses a note, and both say what they dropped.
 * 2. **Import stays additive**, exactly as JSON import is. Notes arrive with newly allocated ids,
 *    and attachments are re-minted onto those ids — so importing the same bundle twice produces
 *    two independent copies rather than two notes fighting over one attachment id.
 */
import { exportBackupPayload } from '@/lib/backup/exportBackup';
import { importNotesFromBackup } from '@/lib/backup/importBackup';
import {
  BUNDLE_FILE_EXTENSION,
  BundleFormatError,
  MAX_BUNDLE_FILE_BYTES,
  assertSupportedEmbeddedBackup,
  buildBackupBundle,
  isBundleableAttachment,
  parseBackupBundle,
  type BundleAttachmentSource,
  type ParsedBundle,
} from '@/lib/backup/bundle/backupBundle';
import {
  createAttachmentId,
  isPendingAttachment,
  pendingStoragePath,
  ATTACHMENT_PENDING_PREFIX,
} from '@/lib/attachments/attachmentPaths';
import {
  getPendingAttachmentBlob,
  releasePendingAttachment,
  storePendingAttachment,
} from '@/lib/attachments/pendingAttachmentStore';
import { resolveOwnerId } from '@/lib/local/ownerNamespace';
import type { Attachment } from '@/types/attachment';
import type { Note } from '@/types/note';

export interface BundleExportResult {
  bytes: Uint8Array;
  /** Every image the exported notes reference. */
  attachmentsTotal: number;
  attachmentsIncluded: number;
  /** Not included for any reason: `attachmentsTotal - attachmentsIncluded`. */
  attachmentsSkipped: number;
  /** The part of `attachmentsSkipped` that was tried and failed: unreadable, a failed download, or rejected. */
  attachmentsFailed: number;
  /**
   * True only when every referenced image is in the archive. Nothing else may call a bundle complete:
   * the delete-account flow relies on this to decide whether the user still needs a warning.
   */
  complete: boolean;
  /** Whether images that live only in the cloud were fetched (`complete`) or left out (`device`). */
  mode: BundleExportMode;
  warnings: string[];
}

/**
 * `device` is the offline backup: only bytes already on this device, never a network request.
 * `complete` also downloads images that live only in the cloud, through the same authenticated Worker
 * GET the editor uses, so the archive can hold the whole library.
 */
export type BundleExportMode = 'device' | 'complete';

/** How complete an exported bundle is, for the places that must not overstate it. */
export interface BackupExportOutcome {
  complete: boolean;
  /** Images in the file. */
  included: number;
  /** Images the exported notes reference. */
  total: number;
}

export interface BundleExportOptions {
  mode?: BundleExportMode;
  /** The authenticated Worker GET. Required for `complete`. */
  downloadCloudAttachment?: (noteId: string, attachmentId: string) => Promise<Blob>;
  /** Simultaneous reads and downloads. Small on purpose: the Worker throttles per user. */
  concurrency?: number;
  /** Extra attempts for a download that failed outright; 429s are already retried by the store. */
  retries?: number;
  onProgress?: (done: number, total: number) => void;
}

/** Downloads in flight at once for a complete export. */
export const CLOUD_EXPORT_CONCURRENCY = 3;
const CLOUD_EXPORT_RETRIES = 1;

/**
 * Reads the bytes for [attachment] from local storage, or null when they are not here.
 *
 * Only locally-staged bytes are reachable without the network. An attachment already committed to
 * R2 lives behind an authenticated Worker call; a `device` export never makes one, and reports those
 * as skipped rather than silently omitting them.
 */
async function readLocalAttachmentBytes(
  attachment: Attachment,
  ownerId: string,
): Promise<{ bytes: Uint8Array; mimeType?: string } | null> {
  if (!isPendingAttachment(attachment.storagePath)) return null;
  const pendingId = attachment.storagePath.slice(ATTACHMENT_PENDING_PREFIX.length);
  const staged = await getPendingAttachmentBlob(pendingId, String(attachment.noteId), ownerId);
  if (!staged) return null;
  const buffer = await staged.blob.arrayBuffer();
  return { bytes: new Uint8Array(buffer), mimeType: staged.mimeType ?? attachment.mimeType };
}

/**
 * Downloads one cloud-only image and checks it is the one the note references.
 *
 * Identity comes from the request itself: the Worker derives the object key from the signed-in user
 * and this note and attachment id, and answers 404 for anything else, so a body that arrives is that
 * attachment. What can still go wrong is the body (truncated, empty, oversized), so its size is held
 * to the size the server recorded at upload and to the bundle's per-image cap. Returns the reason
 * when it fails.
 */
async function downloadCloudAttachment(
  note: Note,
  attachment: Attachment,
  download: NonNullable<BundleExportOptions['downloadCloudAttachment']>,
  retries: number,
): Promise<{ bytes: Uint8Array; mimeType?: string } | string> {
  let lastFailure = 'download failed';
  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      // eslint-disable-next-line no-await-in-loop -- a retry follows the failed attempt
      const blob = await download(note.id, attachment.id);
      // eslint-disable-next-line no-await-in-loop -- read only once the download succeeded
      const bytes = new Uint8Array(await blob.arrayBuffer());
      if (bytes.byteLength === 0) return 'the server returned an empty file';
      const expected = attachment.sizeBytes;
      if (expected != null && expected > 0 && bytes.byteLength !== expected) {
        // A short read is the likeliest cause, so it is worth another attempt.
        lastFailure = `the download was ${bytes.byteLength} bytes, expected ${expected}`;
        continue;
      }
      if (!isBundleableAttachment(attachment.id, bytes.byteLength)) return 'a backup cannot store it';
      return { bytes, mimeType: attachment.mimeType || blob.type || undefined };
    } catch (error) {
      lastFailure = error instanceof Error ? error.message : 'download failed';
    }
  }
  return lastFailure;
}

/** Runs [tasks] with at most [limit] in flight, keeping results in input order. */
async function runBounded<T>(tasks: (() => Promise<T>)[], limit: number): Promise<T[]> {
  // Filled by index as tasks finish, so the order is the input's whatever order they finish in.
  const results: T[] = [];
  let next = 0;
  const workers = Array.from({ length: Math.max(1, Math.min(limit, tasks.length)) }, async () => {
    while (next < tasks.length) {
      const index = next++;
      // eslint-disable-next-line no-await-in-loop -- each worker takes the next task when it is free
      results[index] = await tasks[index]!();
    }
  });
  await Promise.all(workers);
  return results;
}

function bundleSource(
  note: Note,
  attachment: Attachment,
  read: { bytes: Uint8Array; mimeType?: string },
): BundleAttachmentSource {
  return {
    noteId: note.localId,
    attachmentId: attachment.id,
    type: attachment.type || 'image',
    mimeType: read.mimeType,
    bytes: read.bytes,
  };
}

/**
 * Builds a bundle from [notes] and their images.
 *
 * `backup` is produced by the *unchanged* JSON exporter, so a bundle and a JSON backup of the
 * same library carry byte-identical note data. Both modes write the same v4 layout; a complete
 * export only has more `media/` entries, so every client reads either.
 */
export async function buildBundleFromNotes(
  notes: Note[],
  /**
   * The staging namespace to read from. Explicit rather than ambient because staged bytes are
   * namespaced per account: reading the wrong one would silently export a bundle with no images,
   * or — far worse — with the previous account's.
   */
  ownerId: string | null = resolveOwnerId(),
  options: BundleExportOptions = {},
): Promise<BundleExportResult> {
  const mode = options.mode ?? 'device';
  const download = options.downloadCloudAttachment;
  if (mode === 'complete' && !download) {
    throw new Error('A complete backup needs a signed-in connection to download cloud images.');
  }
  const document = exportBackupPayload(notes);
  const warnings: string[] = [];
  let cloudOnly = 0;
  let unreadable = 0;
  let downloadFailed = 0;
  let rejected = 0;

  const items = notes.flatMap((note) => note.attachments.map((attachment) => ({ note, attachment })));
  const total = items.length;
  let done = 0;

  const tasks = items.map(({ note, attachment }) => async (): Promise<BundleAttachmentSource | null> => {
    try {
      if (!isPendingAttachment(attachment.storagePath)) {
        if (mode === 'device' || !download) {
          cloudOnly++;
          return null;
        }
        const fetched = await downloadCloudAttachment(
          note,
          attachment,
          download,
          options.retries ?? CLOUD_EXPORT_RETRIES,
        );
        if (typeof fetched === 'string') {
          downloadFailed++;
          warnings.push(`An image in "${note.title || 'a note'}" could not be downloaded: ${fetched}.`);
          return null;
        }
        return bundleSource(note, attachment, fetched);
      }
      const local = ownerId ? await readLocalAttachmentBytes(attachment, ownerId).catch(() => null) : null;
      if (!local) {
        unreadable++;
        return null;
      }
      if (!isBundleableAttachment(attachment.id, local.bytes.byteLength)) {
        rejected++;
        return null;
      }
      return bundleSource(note, attachment, local);
    } finally {
      options.onProgress?.(++done, total);
    }
  });

  const settled = await runBounded(tasks, options.concurrency ?? CLOUD_EXPORT_CONCURRENCY);
  const sources: BundleAttachmentSource[] = [];
  const seen = new Set<string>();
  for (const item of settled) {
    if (!item) continue;
    // buildBackupBundle keeps the first of two identical ids and drops the rest; count it the same way.
    if (seen.has(item.attachmentId)) {
      rejected++;
      continue;
    }
    seen.add(item.attachmentId);
    sources.push(item);
  }

  if (cloudOnly > 0) {
    warnings.push(
      `${cloudOnly} image${cloudOnly === 1 ? ' was' : 's were'} not included because ${
        cloudOnly === 1 ? 'its bytes are' : 'their bytes are'
      } only available in cloud storage on this device.`,
    );
  }
  if (unreadable > 0) {
    warnings.push(
      `${unreadable} image${unreadable === 1 ? ' was' : 's were'} skipped because local storage could not be read.`,
    );
  }
  if (rejected > 0) {
    warnings.push(
      `${rejected} image${rejected === 1 ? ' was' : 's were'} skipped because a backup cannot store ${
        rejected === 1 ? 'it' : 'them'
      }.`,
    );
  }

  return {
    bytes: await buildBackupBundle(document, sources),
    attachmentsTotal: total,
    attachmentsIncluded: sources.length,
    attachmentsSkipped: total - sources.length,
    attachmentsFailed: unreadable + downloadFailed + rejected,
    complete: sources.length === total,
    mode,
    warnings,
  };
}

export function bundleFileName(now: Date = new Date()): string {
  return `notelikeus_backup_${now.toISOString().slice(0, 10)}${BUNDLE_FILE_EXTENSION}`;
}

/** Hands the bundle to the browser as a download. */
export function downloadBundle(bytes: Uint8Array, fileName = bundleFileName()): void {
  const blob = new Blob([bytes as BlobPart], { type: 'application/zip' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName;
  anchor.click();
  URL.revokeObjectURL(url);
}

export interface BundleImportPlan {
  merged: Note[];
  notesImported: number;
  labelsCreated: number;
  attachmentsImported: number;
  attachmentsSkipped: number;
  warnings: string[];
}

/** An image the plan will restore, held in memory until the import is confirmed. */
interface PlannedAttachment {
  target: Note;
  bytes: Uint8Array;
  mimeType?: string;
  type?: string;
}

/**
 * What importing a bundle would do, worked out without writing anything. `merged` carries the
 * imported notes without their images; `attachmentsImported` is how many images confirming would
 * restore. Nothing is staged until {@link stagePlannedAttachments}, so a preview that is cancelled,
 * or a tab closed while it is open, leaves no bytes behind.
 */
export interface PlannedBundleImport extends BundleImportPlan {
  planned: PlannedAttachment[];
}

/** A confirmed import with its images staged; `staged` is what to take back if the commit fails. */
export interface StagedBundleImport extends BundleImportPlan {
  staged: { attachmentId: string; noteId: string }[];
}

export async function readBundleFile(file: File): Promise<Uint8Array> {
  if (file.size > MAX_BUNDLE_FILE_BYTES) {
    throw new BundleFormatError('Backup bundle is too large');
  }
  return new Uint8Array(await file.arrayBuffer());
}

/**
 * Plans [parsed] on top of [existingNotes] without writing anything.
 *
 * Notes go through the ordinary v3 importer, which allocates fresh local ids. Each image is matched
 * to its note by mapping the bundle's *old* note id to the id the importer just assigned.
 */
export function planBundle(
  parsed: ParsedBundle,
  existingNotes: Note[],
  options: { now?: number } = {},
): PlannedBundleImport {
  const now = options.now ?? Date.now();
  assertSupportedEmbeddedBackup(parsed.manifest.backup);

  const backupNotes = Array.isArray(
    (parsed.manifest.backup as { notes?: unknown[] }).notes,
  )
    ? ((parsed.manifest.backup as { notes?: unknown[] }).notes as Record<string, unknown>[])
    : [];

  const { merged, result } = importNotesFromBackup(parsed.manifest.backup, existingNotes, now);
  const imported = merged.slice(existingNotes.length);

  // The importer preserves order, so the nth imported note is the nth entry in the document.
  // That is what lets an attachment's original note id be mapped onto its new local id.
  const newIdByOldId = new Map<number, Note>();
  imported.forEach((note, index) => {
    const original = backupNotes[index];
    const oldId = typeof original?.id === 'number' ? Math.trunc(original.id) : null;
    if (oldId != null && !newIdByOldId.has(oldId)) newIdByOldId.set(oldId, note);
  });

  const planned: PlannedAttachment[] = [];
  // Starts at what the parse already refused, so the reported total covers the whole journey
  // rather than only the part this function saw.
  let attachmentsSkipped = parsed.droppedAttachments;
  for (const entry of parsed.manifest.attachments) {
    const target = newIdByOldId.get(entry.noteId);
    const blobRecord = parsed.media.get(entry.attachmentId);
    if (!target || !blobRecord) {
      attachmentsSkipped++;
      continue;
    }
    planned.push({
      target,
      bytes: blobRecord.bytes,
      mimeType: blobRecord.mimeType,
      type: blobRecord.type,
    });
  }

  return {
    merged,
    notesImported: result.notesImported,
    labelsCreated: result.labelsCreated,
    attachmentsImported: planned.length,
    attachmentsSkipped,
    warnings: [...parsed.warnings],
    planned,
  };
}

/**
 * Stages the planned images and attaches them to their notes: the confirm step.
 *
 * Each image gets a **new** attachment id, so a second import of the same file cannot collide with
 * the first and cannot point two notes at one staged blob. Staging is awaited per image and the
 * reference is only added when the write lands — the editor's rule: a persisted note must never
 * reference bytes that are not there. A write that fails (a full disk, typically) drops that image
 * and keeps its note.
 */
export async function stagePlannedAttachments(
  plan: PlannedBundleImport,
  /** Staging namespace to write into. Same reason as the export side: it is per account. */
  ownerId: string | null = resolveOwnerId(),
): Promise<StagedBundleImport> {
  const warnings = [...plan.warnings];
  const attachmentsByNote = new Map<string, Attachment[]>();
  const staged: StagedBundleImport['staged'] = [];
  let attachmentsSkipped = plan.attachmentsSkipped;

  for (const item of plan.planned) {
    const freshId = createAttachmentId();
    const mimeType = item.mimeType ?? 'image/jpeg';
    // eslint-disable-next-line no-await-in-loop -- each staging write is awaited before use
    const ok = ownerId
      ? await storePendingAttachment(
          freshId,
          new Blob([item.bytes as BlobPart], { type: mimeType }),
          mimeType,
          item.target.id,
          ownerId,
        ).catch(() => false)
      : false;

    if (!ok) {
      attachmentsSkipped++;
      warnings.push(`Could not save the image for "${item.target.title || 'a note'}"`);
      continue;
    }

    staged.push({ attachmentId: freshId, noteId: item.target.id });
    const list = attachmentsByNote.get(item.target.id) ?? [];
    list.push({
      id: freshId,
      noteId: item.target.localId,
      storagePath: pendingStoragePath(freshId),
      type: item.type || 'image',
      mimeType: item.mimeType,
      sizeBytes: item.bytes.byteLength,
    });
    attachmentsByNote.set(item.target.id, list);
  }

  const merged = plan.merged.map((note) => {
    const attachments = attachmentsByNote.get(note.id);
    return attachments ? { ...note, attachments } : note;
  });

  if (attachmentsSkipped > 0) {
    warnings.push(
      `${attachmentsSkipped} image${attachmentsSkipped === 1 ? '' : 's'} could not be restored. ` +
        `The notes they belonged to were imported.`,
    );
  }

  return {
    merged,
    notesImported: plan.notesImported,
    labelsCreated: plan.labelsCreated,
    attachmentsImported: staged.length,
    attachmentsSkipped,
    warnings,
    staged,
  };
}

/**
 * Takes back the images a confirmed import staged, for when committing its notes then fails: no
 * note references them, and on a large bundle they are hundreds of megabytes of nothing.
 */
export async function releaseStagedBundle(
  staged: Pick<StagedBundleImport, 'staged'>,
  ownerId: string | null = resolveOwnerId(),
): Promise<void> {
  if (!ownerId) return;
  for (const { attachmentId, noteId } of staged.staged) {
    // eslint-disable-next-line no-await-in-loop -- best-effort deletes, one at a time
    await releasePendingAttachment(attachmentId, noteId, ownerId);
  }
}

/** Plans and stages in one step: what an import does once confirmed. */
export async function applyBundle(
  parsed: ParsedBundle,
  existingNotes: Note[],
  options: { now?: number; ownerId?: string | null } = {},
): Promise<StagedBundleImport> {
  const ownerId = options.ownerId === undefined ? resolveOwnerId() : options.ownerId;
  return stagePlannedAttachments(planBundle(parsed, existingNotes, options), ownerId);
}

/** Parses and plans a bundle import, writing nothing; see {@link stagePlannedAttachments}. */
export async function planBundleImport(
  archive: Uint8Array,
  existingNotes: Note[],
  options: { now?: number; ownerId?: string | null } = {},
): Promise<PlannedBundleImport> {
  return planBundle(await parseBackupBundle(archive), existingNotes, options);
}
