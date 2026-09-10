/**
 * The `.nlkbak` bundle: a v3 JSON backup plus the attachment bytes it refers to.
 *
 * The format's one structural decision is that `manifest.backup` is a **v3 backup document,
 * verbatim** — byte-for-byte what the JSON exporter already writes. Everything follows from that:
 *
 * - Import reuses the unchanged v3 importer, so every cap, coercion and validation it already
 *   performs applies to a bundle too, and there is no second implementation to keep in agreement.
 * - A user with only an older build can rename the file to `.zip`, open it, and lift
 *   `manifest.json` -> `backup` out as a working JSON backup. The new format is not a trap.
 * - Attachments are additive. They can fail, be dropped, or be absent, and the notes still import.
 *
 * See `contracts/backup/v4-bundle-manifest.json` for the wire format itself.
 */
import { BACKUP_VERSION, MAX_BACKUP_FILE_BYTES } from '@/lib/backup/constants';
import {
  extractZipEntry,
  listZipCentralDirectory,
  writeZip,
  ZipFormatError,
  type ZipCentralEntry,
} from '@/lib/backup/bundle/zip';
import { assertJsonNestingWithinLimit } from '@/lib/backup/jsonNesting';

export const BUNDLE_FORMAT_VERSION = 4;
export const BUNDLE_FILE_EXTENSION = '.nlkbak';
export const BUNDLE_MANIFEST_ENTRY = 'manifest.json';
export const BUNDLE_MEDIA_PREFIX = 'media/';

/** Whole-file ceiling before anything is parsed. */
export const MAX_BUNDLE_FILE_BYTES = 256 * 1024 * 1024;
/** Per-attachment ceiling, matching the Worker's own upload cap. */
export const MAX_BUNDLE_ATTACHMENT_BYTES = 10 * 1024 * 1024;
/** More attachments than any real library has; bounds the manifest and the extraction loop. */
export const MAX_BUNDLE_ATTACHMENTS = 5_000;
/**
 * Manifest entry ceiling: the embedded v3 backup (already capped at {@link MAX_BACKUP_FILE_BYTES})
 * plus a modest attachment index. Enforced before the entry is decompressed.
 */
export const MAX_BUNDLE_MANIFEST_BYTES = MAX_BACKUP_FILE_BYTES + 2 * 1024 * 1024;

/**
 * The only attachment ids a bundle may name.
 *
 * This is what makes path traversal structurally impossible rather than merely checked for: a
 * media entry is addressed as `media/` + a *validated id*, never as a path read out of the
 * archive, so there is no attacker-controlled path anywhere in the extraction.
 */
const ATTACHMENT_ID_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;

export interface BundleAttachmentEntry {
  noteId: number;
  attachmentId: string;
  path: string;
  type: string;
  mimeType?: string;
  sizeBytes?: number;
  sha256?: string;
}

export interface BundleManifest {
  formatVersion: number;
  app: string;
  appVersion: string;
  exportedAt: number;
  /** A v3 backup document, verbatim. */
  backup: Record<string, unknown>;
  attachments: BundleAttachmentEntry[];
}

export interface BundleAttachmentSource {
  noteId: number;
  attachmentId: string;
  type: string;
  mimeType?: string;
  bytes: Uint8Array;
}

export class BundleFormatError extends Error {}

/** Lowercase hex SHA-256 of [bytes], or undefined where WebCrypto is unavailable. */
export async function sha256Hex(bytes: Uint8Array): Promise<string | undefined> {
  if (typeof crypto === 'undefined' || !crypto.subtle) return undefined;
  try {
    const digest = await crypto.subtle.digest('SHA-256', bytes as unknown as BufferSource);
    return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
  } catch {
    return undefined;
  }
}

export function mediaEntryName(attachmentId: string): string {
  return `${BUNDLE_MEDIA_PREFIX}${attachmentId}`;
}

// ---- writing --------------------------------------------------------------

/**
 * Builds a `.nlkbak` archive.
 *
 * [attachments] carries only what is available **locally**. A bundle never reaches the network to
 * fetch bytes: an export must work offline and must not turn a backup into a cloud operation.
 * Anything missing is simply not in the archive, and the notes still carry their text.
 */
export async function buildBackupBundle(
  backupDocument: Record<string, unknown>,
  attachments: BundleAttachmentSource[],
  options?: { app?: string; appVersion?: string; exportedAt?: number },
): Promise<Uint8Array> {
  if (attachments.length > MAX_BUNDLE_ATTACHMENTS) {
    throw new BundleFormatError(
      `Too many attachments for one bundle (max ${MAX_BUNDLE_ATTACHMENTS})`,
    );
  }

  const index: BundleAttachmentEntry[] = [];
  const media: { name: string; data: Uint8Array }[] = [];
  const seen = new Set<string>();

  for (const source of attachments) {
    if (!ATTACHMENT_ID_PATTERN.test(source.attachmentId)) continue;
    if (seen.has(source.attachmentId)) continue;
    if (source.bytes.byteLength > MAX_BUNDLE_ATTACHMENT_BYTES) continue;
    seen.add(source.attachmentId);

    const name = mediaEntryName(source.attachmentId);
    index.push({
      noteId: source.noteId,
      attachmentId: source.attachmentId,
      path: name,
      type: source.type || 'image',
      mimeType: source.mimeType,
      sizeBytes: source.bytes.byteLength,
      // eslint-disable-next-line no-await-in-loop -- digests are computed per entry
      sha256: await sha256Hex(source.bytes),
    });
    media.push({ name, data: source.bytes });
  }

  const manifest: BundleManifest = {
    formatVersion: BUNDLE_FORMAT_VERSION,
    app: options?.app ?? 'Notelikeus',
    appVersion: options?.appVersion ?? '1.0.0 (web)',
    exportedAt: options?.exportedAt ?? Date.now(),
    backup: backupDocument,
    attachments: index,
  };

  return writeZip([
    { name: BUNDLE_MANIFEST_ENTRY, data: new TextEncoder().encode(JSON.stringify(manifest, null, 2)) },
    ...media,
  ]);
}

// ---- reading --------------------------------------------------------------

export interface ParsedBundle {
  manifest: BundleManifest;
  /** Attachment id -> bytes, for every media entry that was present and verified. */
  media: Map<string, { bytes: Uint8Array; mimeType?: string; type: string; noteId: number }>;
  /**
   * Attachments the manifest listed but this parse refused — missing file, wrong size, failed
   * checksum, unusable id. Counted here so the import report can include them: they are dropped
   * before `manifest.attachments` is built, so a caller counting that list alone under-reports.
   */
  droppedAttachments: number;
  /** Human-readable reasons individual attachments were dropped. Notes are never dropped. */
  warnings: string[];
}

function asString(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback;
}

function asFiniteInt(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? Math.trunc(value) : fallback;
}

/** Whether [file] looks like a bundle rather than a plain JSON backup. */
export function looksLikeBundle(file: { name?: string }, head: Uint8Array): boolean {
  if (head.byteLength >= 2 && head[0] === 0x50 && head[1] === 0x4b) return true;
  return (file.name ?? '').toLowerCase().endsWith(BUNDLE_FILE_EXTENSION);
}

/**
 * Parses a `.nlkbak` archive into its manifest and verified media.
 *
 * Manifest-first and selective: the central directory is listed without decompressing payloads,
 * `manifest.json` is extracted and validated on its own, and only media entries the manifest
 * names are then extracted — one at a time. Unreferenced archive members are never held in
 * memory as decompressed bytes.
 *
 * The whole-file guarantee is one-sided on purpose: a malformed *manifest* fails the import,
 * because without it there are no notes to recover; a malformed *attachment* is dropped with a
 * warning, because the notes are still there and losing a photo is not a reason to lose the text
 * around it.
 */
export async function parseBackupBundle(archive: Uint8Array): Promise<ParsedBundle> {
  if (archive.byteLength > MAX_BUNDLE_FILE_BYTES) {
    throw new BundleFormatError('Backup bundle is too large');
  }

  let central: ZipCentralEntry[];
  try {
    central = listZipCentralDirectory(archive);
  } catch (error) {
    if (error instanceof ZipFormatError) throw new BundleFormatError(error.message);
    throw error;
  }

  const byName = new Map(central.map((entry) => [entry.name, entry]));
  const manifestCentral = byName.get(BUNDLE_MANIFEST_ENTRY);
  if (!manifestCentral) {
    throw new BundleFormatError('Backup bundle has no manifest.json');
  }
  if (manifestCentral.uncompressedSize > MAX_BUNDLE_MANIFEST_BYTES) {
    throw new BundleFormatError('Backup bundle manifest is too large');
  }

  let manifestBytes: Uint8Array;
  try {
    manifestBytes = (await extractZipEntry(archive, manifestCentral)).data;
  } catch (error) {
    if (error instanceof ZipFormatError) throw new BundleFormatError(error.message);
    throw error;
  }

  const manifestText = new TextDecoder().decode(manifestBytes);
  try {
    assertJsonNestingWithinLimit(manifestText);
  } catch (error) {
    throw new BundleFormatError(
      error instanceof Error ? error.message : 'Backup bundle manifest is too deeply nested',
    );
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(manifestText);
  } catch {
    throw new BundleFormatError('Backup bundle manifest is not valid JSON');
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new BundleFormatError('Backup bundle manifest is not an object');
  }

  const raw = parsed as Record<string, unknown>;
  const formatVersion = asFiniteInt(raw.formatVersion, 0);
  if (formatVersion > BUNDLE_FORMAT_VERSION) {
    throw new BundleFormatError(`Unsupported bundle version: ${formatVersion}`);
  }
  const backup = raw.backup;
  if (!backup || typeof backup !== 'object' || Array.isArray(backup)) {
    throw new BundleFormatError('Backup bundle manifest carries no backup document');
  }

  const warnings: string[] = [];
  let droppedAttachments = 0;
  const media: ParsedBundle['media'] = new Map();
  const attachments: BundleAttachmentEntry[] = [];
  const referencedNames = new Set<string>([BUNDLE_MANIFEST_ENTRY]);

  const rawAttachments = Array.isArray(raw.attachments) ? raw.attachments : [];
  if (rawAttachments.length > MAX_BUNDLE_ATTACHMENTS) {
    throw new BundleFormatError(
      `Bundle lists more attachments than this app will read (max ${MAX_BUNDLE_ATTACHMENTS})`,
    );
  }

  for (const candidate of rawAttachments) {
    if (!candidate || typeof candidate !== 'object') continue;
    const entry = candidate as Record<string, unknown>;
    const attachmentId = asString(entry.attachmentId);
    if (!ATTACHMENT_ID_PATTERN.test(attachmentId)) {
      droppedAttachments++;
      warnings.push('Skipped an attachment with an unusable id');
      continue;
    }
    if (media.has(attachmentId)) continue;

    // Derived from the validated id, never from `entry.path`. The manifest's `path` is
    // informational; trusting it is exactly how zip-slip gets in.
    const mediaName = mediaEntryName(attachmentId);
    referencedNames.add(mediaName);
    const mediaCentral = byName.get(mediaName);
    if (!mediaCentral) {
      droppedAttachments++;
      warnings.push(`Attachment ${attachmentId} is listed but its file is missing`);
      continue;
    }
    if (mediaCentral.uncompressedSize > MAX_BUNDLE_ATTACHMENT_BYTES) {
      droppedAttachments++;
      warnings.push(`Attachment ${attachmentId} is too large to import`);
      continue;
    }

    let bytes: Uint8Array;
    try {
      // eslint-disable-next-line no-await-in-loop -- attachments are extracted one at a time
      bytes = (await extractZipEntry(archive, mediaCentral)).data;
    } catch (error) {
      droppedAttachments++;
      warnings.push(
        error instanceof ZipFormatError
          ? `Attachment ${attachmentId} failed archive checks and was skipped`
          : `Attachment ${attachmentId} could not be read and was skipped`,
      );
      continue;
    }

    if (bytes.byteLength > MAX_BUNDLE_ATTACHMENT_BYTES) {
      droppedAttachments++;
      warnings.push(`Attachment ${attachmentId} is too large to import`);
      continue;
    }

    const declaredSize = asFiniteInt(entry.sizeBytes, bytes.byteLength);
    if (declaredSize !== bytes.byteLength) {
      droppedAttachments++;
      warnings.push(`Attachment ${attachmentId} does not match its recorded size`);
      continue;
    }

    const declaredHash = asString(entry.sha256).toLowerCase();
    if (declaredHash) {
      // eslint-disable-next-line no-await-in-loop -- digests are computed per entry
      const actual = await sha256Hex(bytes);
      // An environment with no WebCrypto returns undefined; that is not a mismatch, it is an
      // unverifiable file, and refusing it would make the bundle unimportable there.
      if (actual && actual !== declaredHash) {
        droppedAttachments++;
        warnings.push(`Attachment ${attachmentId} failed its checksum and was skipped`);
        continue;
      }
    }

    const record = {
      bytes,
      mimeType: typeof entry.mimeType === 'string' ? entry.mimeType : undefined,
      type: asString(entry.type, 'image'),
      noteId: asFiniteInt(entry.noteId, 0),
    };
    media.set(attachmentId, record);
    attachments.push({
      noteId: record.noteId,
      attachmentId,
      path: mediaName,
      type: record.type,
      mimeType: record.mimeType,
      sizeBytes: bytes.byteLength,
      sha256: declaredHash || undefined,
    });
  }

  const strayMedia = central.filter(
    (entry) => entry.name.startsWith(BUNDLE_MEDIA_PREFIX) && !referencedNames.has(entry.name),
  ).length;
  if (strayMedia > 0) {
    warnings.push(`${strayMedia} file(s) in the bundle are not listed in its manifest`);
  }

  return {
    manifest: {
      formatVersion: formatVersion || BUNDLE_FORMAT_VERSION,
      app: asString(raw.app, 'Notelikeus'),
      appVersion: asString(raw.appVersion),
      exportedAt: asFiniteInt(raw.exportedAt, 0),
      backup: backup as Record<string, unknown>,
      attachments,
    },
    media,
    droppedAttachments,
    warnings,
  };
}

/** The `backup` document a bundle carries must itself be a v3 document this build accepts. */
export function assertSupportedEmbeddedBackup(backup: Record<string, unknown>): void {
  const version = backup.version;
  if (version != null && typeof version === 'number' && version > BACKUP_VERSION) {
    throw new BundleFormatError(`Unsupported backup version: ${version}`);
  }
}
