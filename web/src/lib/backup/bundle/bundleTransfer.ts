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
import { getPendingAttachmentBlob, storePendingAttachment } from '@/lib/attachments/pendingAttachmentStore';
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
      `${skipped} image${skipped === 1 ? '' : 's'} could not be included: the bytes are only in ` +
        `the cloud on this device. Open those notes while online first, then export again.`,
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

export async function readBundleFile(file: File): Promise<Uint8Array> {
  if (file.size > MAX_BUNDLE_FILE_BYTES) {
    throw new BundleFormatError('Backup bundle is too large');
  }
  return new Uint8Array(await file.arrayBuffer());
}

/**
 * Applies a parsed bundle on top of [existingNotes].
 *
 * Notes go through the ordinary v3 importer, which allocates fresh local ids. Attachments are then
 * re-attached by mapping the bundle's *old* note id to the id the importer just assigned — and
 * given **new** attachment ids, so a second import of the same file cannot collide with the first
 * and cannot point two notes at one staged blob.
 *
 * Staging is awaited per attachment and the reference is only added when the write lands, which is
 * the same rule the editor follows: a persisted note must never reference bytes that are not there.
 */
export async function applyBundle(
  parsed: ParsedBundle,
  existingNotes: Note[],
  options: {
    now?: number;
    /** Staging namespace to write into. Same reason as the export side: it is per account. */
    ownerId?: string | null;
  } = {},
): Promise<BundleImportPlan> {
  const now = options.now ?? Date.now();
  const ownerId = options.ownerId === undefined ? resolveOwnerId() : options.ownerId;
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

  const warnings = [...parsed.warnings];
  const attachmentsByNote = new Map<string, Attachment[]>();
  let attachmentsImported = 0;
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

    const freshId = createAttachmentId();
    // eslint-disable-next-line no-await-in-loop -- each staging write is awaited before use
    const staged = ownerId
      ? await storePendingAttachment(
          freshId,
          new Blob([blobRecord.bytes as BlobPart], { type: blobRecord.mimeType ?? 'image/jpeg' }),
          blobRecord.mimeType ?? 'image/jpeg',
          target.id,
          ownerId,
        ).catch(() => false)
      : false;

    if (!staged) {
      attachmentsSkipped++;
      warnings.push(`Could not save the image for "${target.title || 'a note'}"`);
      continue;
    }

    const list = attachmentsByNote.get(target.id) ?? [];
    list.push({
      id: freshId,
      noteId: target.localId,
      storagePath: pendingStoragePath(freshId),
      type: blobRecord.type || 'image',
      mimeType: blobRecord.mimeType,
      sizeBytes: blobRecord.bytes.byteLength,
    });
    attachmentsByNote.set(target.id, list);
    attachmentsImported++;
  }

  const withAttachments = merged.map((note) => {
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
    merged: withAttachments,
    notesImported: result.notesImported,
    labelsCreated: result.labelsCreated,
    attachmentsImported,
    attachmentsSkipped,
    warnings,
  };
}

/** Parses and plans a bundle import in one step. */
export async function planBundleImport(
  archive: Uint8Array,
  existingNotes: Note[],
  options: { now?: number; ownerId?: string | null } = {},
): Promise<BundleImportPlan> {
  return applyBundle(await parseBackupBundle(archive), existingNotes, options);
}
