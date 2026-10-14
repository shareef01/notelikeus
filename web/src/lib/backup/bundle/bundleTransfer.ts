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
  attachmentsIncluded: number;
  attachmentsSkipped: number;
  warnings: string[];
}

/**
 * Reads the bytes for [attachment] from local storage, or null when they are not here.
 *
 * Only locally-staged bytes are reachable without the network. An attachment already committed to
 * R2 lives behind an authenticated Worker call, and fetching it would make an export depend on
 * being online and signed in — which the product principles rule out. Those are reported as
 * skipped rather than silently omitted.
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
 * Builds a bundle from [notes] and whatever attachment bytes are on this device.
 *
 * `backup` is produced by the *unchanged* JSON exporter, so a bundle and a JSON backup of the
 * same library carry byte-identical note data.
 */
export async function buildBundleFromNotes(
  notes: Note[],
  /**
   * The staging namespace to read from. Explicit rather than ambient because staged bytes are
   * namespaced per account: reading the wrong one would silently export a bundle with no images,
   * or — far worse — with the previous account's.
   */
  ownerId: string | null = resolveOwnerId(),
): Promise<BundleExportResult> {
  const document = exportBackupPayload(notes);
  const sources: BundleAttachmentSource[] = [];
  const warnings: string[] = [];
  let skipped = 0;

  for (const note of notes) {
    for (const attachment of note.attachments) {
      // eslint-disable-next-line no-await-in-loop -- reads go through IndexedDB one at a time
      const local = ownerId
        ? await readLocalAttachmentBytes(attachment, ownerId).catch(() => null)
        : null;
      if (!local) {
        skipped++;
        continue;
      }
      sources.push({
        noteId: note.localId,
        attachmentId: attachment.id,
        type: attachment.type || 'image',
        mimeType: local.mimeType,
        bytes: local.bytes,
      });
    }
  }

  if (skipped > 0) {
    warnings.push(
      `${skipped} image${skipped === 1 ? ' was' : 's were'} not included because ${
        skipped === 1 ? 'its bytes are' : 'their bytes are'
      } only available in cloud storage on this device.`,
    );
  }

  return {
    bytes: await buildBackupBundle(document, sources),
    attachmentsIncluded: sources.length,
    attachmentsSkipped: skipped,
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
