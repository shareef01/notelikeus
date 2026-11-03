/**
 * A minimal ZIP reader and writer, sized to the backup bundle and nothing else.
 *
 * Written rather than depended on because the alternative is a runtime dependency in the one
 * code path whose whole job is recovering a user's data from a file they may have carried across
 * machines and years — and because the security properties below have to be *ours*, stated in one
 * place, rather than inherited from whatever a library happens to do this major version.
 *
 * Entries are written **stored** (method 0, no compression). Attachments are already-compressed
 * images, so deflating them saves almost nothing, and not deflating removes an entire class of
 * failure from the write path. Reading accepts stored and deflated entries, so a bundle a user
 * re-zipped with their operating system still imports.
 *
 * Every limit is enforced on read, against the archive's own declared numbers *and* against the
 * bytes that actually arrive — a hostile archive lies in its headers.
 *
 * Extraction is selective by design: {@link listZipCentralDirectory} parses headers only;
 * {@link extractZipEntry} decompresses one named entry. Callers that only need the manifest must
 * not pay to hold every attachment in memory at once.
 */

/** Nothing legitimate has more entries than this: one manifest plus its attachments. */
export const MAX_ZIP_ENTRIES = 10_000;
/** No single entry may exceed this once decompressed. */
export const MAX_ZIP_ENTRY_BYTES = 32 * 1024 * 1024;
/** Nor may the whole archive, decompressed. This is the decompression-bomb ceiling. */
export const MAX_ZIP_TOTAL_BYTES = 512 * 1024 * 1024;
/**
 * A cheap early rejection, not the real boundary.
 *
 * The bomb defence is the pair of absolute caps above plus the byte counter in `inflateRaw`:
 * an archive that *declares* a huge expansion is refused by the caps, and one that declares a
 * small size and then expands anyway is cut off as the bytes arrive. This ratio only catches the
 * obvious case earlier, so it is set where no legitimate file lands — highly repetitive text
 * genuinely deflates several hundred to one, while a classic bomb runs a thousand to one per
 * layer and up.
 */
export const MAX_ZIP_COMPRESSION_RATIO = 1_000;

const LOCAL_HEADER_SIGNATURE = 0x04034b50;
const CENTRAL_HEADER_SIGNATURE = 0x02014b50;
const EOCD_SIGNATURE = 0x06054b50;
const ZIP64_EOCD_LOCATOR_SIGNATURE = 0x07064b50;

const METHOD_STORE = 0;
const METHOD_DEFLATE = 8;

export interface ZipEntryInput {
  name: string;
  data: Uint8Array;
}

export interface ZipEntry {
  name: string;
  data: Uint8Array;
}

/** Central-directory metadata for one entry — no decompressed payload. */
export interface ZipCentralEntry {
  name: string;
  method: number;
  compressedSize: number;
  uncompressedSize: number;
  crc: number;
  localOffset: number;
}

export class ZipFormatError extends Error {}

// ---- CRC32 ----------------------------------------------------------------

let crcTable: Uint32Array | null = null;

function crc32Table(): Uint32Array {
  if (crcTable) return crcTable;
  const table = new Uint32Array(256);
  for (let index = 0; index < 256; index++) {
    let value = index;
    for (let bit = 0; bit < 8; bit++) {
      value = value & 1 ? 0xedb88320 ^ (value >>> 1) : value >>> 1;
    }
    table[index] = value >>> 0;
  }
  crcTable = table;
  return table;
}

export function crc32(bytes: Uint8Array): number {
  const table = crc32Table();
  let crc = 0xffffffff;
  // Indexed rather than `for...of`: the iterator protocol doubled the time of a large export.
  for (let index = 0; index < bytes.length; index++) {
    crc = table[(crc ^ bytes[index]!) & 0xff]! ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

// ---- entry names ----------------------------------------------------------

/**
 * Whether an archive entry name is one this reader will even look at.
 *
 * Rejects absolute paths, drive letters, backslashes, `.`/`..` segments, NUL, and control
 * characters. Path traversal is additionally impossible by construction — the bundle reader
 * addresses entries by an id it has already validated, never by a path taken from the archive —
 * but a name that could traverse is evidence of a hostile file, and the reader says so rather
 * than quietly working around it.
 */
export function isSafeZipEntryName(name: string): boolean {
  if (name.length === 0 || name.length > 255) return false;
  // Control chars, DEL, backslash, colon (drive letters).
  // oxlint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f\\:]/.test(name)) return false;
  if (name.startsWith('/')) return false;
  const segments = name.split('/');
  return segments.every((segment) => segment.length > 0 && segment !== '.' && segment !== '..');
}

// ---- writer ---------------------------------------------------------------

/** Fixed part of a local file header, before the name. */
const LOCAL_HEADER_BYTES = 30;
/** Fixed part of a central directory header, before the name. */
const CENTRAL_HEADER_BYTES = 46;
const EOCD_BYTES = 22;

/** Writes the fields of one header, in order, as little-endian 16- and 32-bit values. */
function writeFields(view: DataView, offset: number, fields: ReadonlyArray<readonly [16 | 32, number]>): number {
  let at = offset;
  for (const [bits, value] of fields) {
    if (bits === 16) {
      view.setUint16(at, value, true);
      at += 2;
    } else {
      view.setUint32(at, value >>> 0, true);
      at += 4;
    }
  }
  return at;
}

/**
 * Builds a stored ZIP archive from [entries], in the order given.
 *
 * No zip64: the caller is bounded well below 4 GB by {@link MAX_ZIP_TOTAL_BYTES}, and refusing
 * to produce an archive a plain reader cannot open is better than producing one that needs an
 * extension to read back.
 *
 * Every header's size is known before a byte is written, so the archive is allocated once and each
 * entry's bytes are copied into it with `set`. Peak memory is the entries plus one archive. This used
 * to append every byte to a `number[]`, which cost about fifteen times the input and stopped at V8's
 * array-length limit near 120 MiB (AUD-002). The output is byte-for-byte what that writer produced.
 */
export function writeZip(entries: ZipEntryInput[]): Uint8Array {
  if (entries.length > MAX_ZIP_ENTRIES) {
    throw new ZipFormatError(`Too many archive entries (max ${MAX_ZIP_ENTRIES})`);
  }
  const encoder = new TextEncoder();
  const prepared: { nameBytes: Uint8Array; data: Uint8Array; checksum: number; offset: number }[] = [];
  let total = 0;
  let localLength = 0;
  let centralLength = 0;

  for (const entry of entries) {
    if (!isSafeZipEntryName(entry.name)) {
      throw new ZipFormatError(`Refusing to write unsafe archive entry name: ${entry.name}`);
    }
    total += entry.data.byteLength;
    if (total > MAX_ZIP_TOTAL_BYTES) {
      throw new ZipFormatError('Backup bundle is too large');
    }

    const nameBytes = encoder.encode(entry.name);
    prepared.push({ nameBytes, data: entry.data, checksum: crc32(entry.data), offset: localLength });
    localLength += LOCAL_HEADER_BYTES + nameBytes.length + entry.data.byteLength;
    centralLength += CENTRAL_HEADER_BYTES + nameBytes.length;
  }

  const out = new Uint8Array(localLength + centralLength + EOCD_BYTES);
  const view = new DataView(out.buffer);

  let at = 0;
  for (const { nameBytes, data, checksum } of prepared) {
    at = writeFields(view, at, [
      [32, LOCAL_HEADER_SIGNATURE],
      [16, 20], // version needed
      [16, 0x0800], // UTF-8 names
      [16, METHOD_STORE],
      [16, 0], // mod time
      [16, 0], // mod date
      [32, checksum],
      [32, data.byteLength],
      [32, data.byteLength],
      [16, nameBytes.length],
      [16, 0], // extra length
    ]);
    out.set(nameBytes, at);
    at += nameBytes.length;
    out.set(data, at);
    at += data.byteLength;
  }

  for (const { nameBytes, data, checksum, offset } of prepared) {
    at = writeFields(view, at, [
      [32, CENTRAL_HEADER_SIGNATURE],
      [16, 20], // version made by
      [16, 20], // version needed
      [16, 0x0800],
      [16, METHOD_STORE],
      [16, 0],
      [16, 0],
      [32, checksum],
      [32, data.byteLength],
      [32, data.byteLength],
      [16, nameBytes.length],
      [16, 0], // extra
      [16, 0], // comment
      [16, 0], // disk number
      [16, 0], // internal attrs
      [32, 0], // external attrs
      [32, offset],
    ]);
    out.set(nameBytes, at);
    at += nameBytes.length;
  }

  writeFields(view, at, [
    [32, EOCD_SIGNATURE],
    [16, 0],
    [16, 0],
    [16, entries.length],
    [16, entries.length],
    [32, centralLength],
    [32, localLength],
    [16, 0], // comment length
  ]);
  return out;
}

// ---- reader ---------------------------------------------------------------

function findEndOfCentralDirectory(view: DataView): number {
  // The EOCD is at the end, but a trailing comment can push it back up to 64 KB.
  const start = Math.max(0, view.byteLength - 22 - 0xffff);
  for (let offset = view.byteLength - 22; offset >= start; offset--) {
    if (view.getUint32(offset, true) === EOCD_SIGNATURE) return offset;
  }
  throw new ZipFormatError('Not a ZIP archive: no end-of-central-directory record');
}

async function inflateRaw(data: Uint8Array, expectedSize: number): Promise<Uint8Array> {
  if (typeof DecompressionStream === 'undefined') {
    throw new ZipFormatError('This browser cannot read compressed archive entries');
  }
  const stream = new Blob([data as BlobPart]).stream().pipeThrough(
    new DecompressionStream('deflate-raw'),
  );
  const reader = stream.getReader();
  const chunks: Uint8Array[] = [];
  let produced = 0;
  for (;;) {
    // eslint-disable-next-line no-await-in-loop -- a stream is read one chunk at a time
    const { done, value } = await reader.read();
    if (done) break;
    produced += value.byteLength;
    // Checked as bytes arrive, not from the header: a bomb declares a small size and expands.
    if (produced > MAX_ZIP_ENTRY_BYTES || produced > expectedSize) {
      await reader.cancel();
      throw new ZipFormatError('Archive entry expanded beyond its declared size');
    }
    chunks.push(value);
  }
  const out = new Uint8Array(produced);
  let cursor = 0;
  for (const chunk of chunks) {
    out.set(chunk, cursor);
    cursor += chunk.byteLength;
  }
  return out;
}

/**
 * Parses the central directory only — no entry payloads are decompressed or retained.
 *
 * Callers that need a single entry (the bundle manifest, then named media) should use this with
 * {@link extractZipEntry} rather than {@link readZip}, so unreferenced attachment bytes never
 * land in memory.
 */
export function listZipCentralDirectory(archive: Uint8Array): ZipCentralEntry[] {
  if (archive.byteLength < 22) throw new ZipFormatError('Not a ZIP archive: too short');
  const view = new DataView(archive.buffer, archive.byteOffset, archive.byteLength);
  const eocd = findEndOfCentralDirectory(view);

  // zip64 is not produced by this writer and not accepted; the sizes it exists for are far
  // beyond every limit above, so an archive needing it is already out of bounds.
  for (let offset = Math.max(0, eocd - 20); offset < eocd; offset++) {
    if (view.getUint32(offset, true) === ZIP64_EOCD_LOCATOR_SIGNATURE) {
      throw new ZipFormatError('zip64 archives are not supported');
    }
  }

  const entryCount = view.getUint16(eocd + 10, true);
  if (entryCount > MAX_ZIP_ENTRIES) {
    throw new ZipFormatError(`Too many archive entries (max ${MAX_ZIP_ENTRIES})`);
  }
  const centralSize = view.getUint32(eocd + 12, true);
  const centralOffset = view.getUint32(eocd + 16, true);
  if (centralOffset + centralSize > archive.byteLength) {
    throw new ZipFormatError('Corrupt ZIP archive: central directory out of bounds');
  }

  const decoder = new TextDecoder('utf-8', { fatal: false });
  const central: ZipCentralEntry[] = [];
  const seenNames = new Set<string>();
  let cursor = centralOffset;
  let declaredTotal = 0;

  for (let index = 0; index < entryCount; index++) {
    if (cursor + 46 > centralOffset + centralSize) {
      throw new ZipFormatError('Corrupt ZIP archive: truncated central directory');
    }
    if (view.getUint32(cursor, true) !== CENTRAL_HEADER_SIGNATURE) {
      throw new ZipFormatError('Corrupt ZIP archive: bad central directory header');
    }
    const method = view.getUint16(cursor + 10, true);
    const crc = view.getUint32(cursor + 16, true);
    const compressedSize = view.getUint32(cursor + 20, true);
    const uncompressedSize = view.getUint32(cursor + 24, true);
    const nameLength = view.getUint16(cursor + 28, true);
    const extraLength = view.getUint16(cursor + 30, true);
    const commentLength = view.getUint16(cursor + 32, true);
    const localOffset = view.getUint32(cursor + 42, true);
    const name = decoder.decode(archive.subarray(cursor + 46, cursor + 46 + nameLength));

    // Directory entries carry no data and are simply not part of the bundle format.
    if (!name.endsWith('/')) {
      if (!isSafeZipEntryName(name)) {
        throw new ZipFormatError(`Refusing archive entry with unsafe name: ${name}`);
      }
      if (seenNames.has(name)) {
        throw new ZipFormatError(`Corrupt ZIP archive: duplicate entry name: ${name}`);
      }
      seenNames.add(name);
      if (uncompressedSize > MAX_ZIP_ENTRY_BYTES) {
        throw new ZipFormatError(`Archive entry is too large: ${name}`);
      }
      if (
        compressedSize > 0 &&
        uncompressedSize / compressedSize > MAX_ZIP_COMPRESSION_RATIO
      ) {
        throw new ZipFormatError(`Archive entry has an implausible compression ratio: ${name}`);
      }
      declaredTotal += uncompressedSize;
      if (declaredTotal > MAX_ZIP_TOTAL_BYTES) {
        throw new ZipFormatError('Archive expands to more than this app will read');
      }
      central.push({ name, method, compressedSize, uncompressedSize, crc, localOffset });
    }
    cursor += 46 + nameLength + extraLength + commentLength;
  }

  return central;
}

/**
 * Decompresses and verifies a single central-directory entry from [archive].
 */
export async function extractZipEntry(
  archive: Uint8Array,
  entry: ZipCentralEntry,
): Promise<ZipEntry> {
  const view = new DataView(archive.buffer, archive.byteOffset, archive.byteLength);
  if (entry.localOffset + 30 > archive.byteLength) {
    throw new ZipFormatError(`Corrupt ZIP archive: entry out of bounds: ${entry.name}`);
  }
  if (view.getUint32(entry.localOffset, true) !== LOCAL_HEADER_SIGNATURE) {
    throw new ZipFormatError(`Corrupt ZIP archive: bad local header: ${entry.name}`);
  }
  // The local header's own name and extra lengths, which may differ from the central copy.
  const localNameLength = view.getUint16(entry.localOffset + 26, true);
  const localExtraLength = view.getUint16(entry.localOffset + 28, true);
  const dataStart = entry.localOffset + 30 + localNameLength + localExtraLength;
  const dataEnd = dataStart + entry.compressedSize;
  if (dataEnd > archive.byteLength) {
    throw new ZipFormatError(`Corrupt ZIP archive: truncated entry: ${entry.name}`);
  }
  const raw = archive.subarray(dataStart, dataEnd);

  let data: Uint8Array;
  if (entry.method === METHOD_STORE) {
    // Copy so callers can drop the archive buffer without retaining a view into it.
    data = raw.slice();
  } else if (entry.method === METHOD_DEFLATE) {
    data = await inflateRaw(raw, entry.uncompressedSize);
  } else {
    throw new ZipFormatError(`Unsupported compression method for ${entry.name}`);
  }

  if (data.byteLength !== entry.uncompressedSize) {
    throw new ZipFormatError(`Corrupt ZIP archive: wrong size for ${entry.name}`);
  }
  if (crc32(data) !== entry.crc) {
    throw new ZipFormatError(`Corrupt ZIP archive: checksum mismatch for ${entry.name}`);
  }
  return { name: entry.name, data };
}

/**
 * Reads every entry of [archive] whose name passes {@link isSafeZipEntryName}.
 *
 * Prefer {@link listZipCentralDirectory} + {@link extractZipEntry} when only some entries are
 * needed. This helper remains for tests and callers that genuinely need the full map.
 *
 * Entries with unsafe names are rejected outright rather than skipped: a legitimate bundle has
 * none, so their presence means the file is not one.
 */
export async function readZip(archive: Uint8Array): Promise<ZipEntry[]> {
  const central = listZipCentralDirectory(archive);
  const entries: ZipEntry[] = [];
  for (const entry of central) {
    // eslint-disable-next-line no-await-in-loop -- entries are decompressed one at a time
    entries.push(await extractZipEntry(archive, entry));
  }
  return entries;
}
