import { describe, expect, it, vi } from 'vitest';
import {
  crc32,
  extractZipEntry,
  isSafeZipEntryName,
  listZipCentralDirectory,
  MAX_ZIP_ENTRIES,
  MAX_ZIP_TOTAL_BYTES,
  readZip,
  writeZip,
  ZipFormatError,
} from '@/lib/backup/bundle/zip';

const encoder = new TextEncoder();
const decoder = new TextDecoder();

function bytes(text: string): Uint8Array {
  return encoder.encode(text);
}

describe('crc32', () => {
  // Fixed vectors, so a rewrite of the table cannot quietly change the checksum a bundle
  // carries — an archive written by an older build has to keep verifying.
  it('matches the known CRC-32 vectors', () => {
    expect(crc32(new Uint8Array(0))).toBe(0);
    expect(crc32(bytes('a'))).toBe(0xe8b7be43);
    expect(crc32(bytes('abc'))).toBe(0x352441c2);
    expect(crc32(bytes('123456789'))).toBe(0xcbf43926);
  });
});

describe('isSafeZipEntryName', () => {
  it('accepts the names the bundle format uses', () => {
    expect(isSafeZipEntryName('manifest.json')).toBe(true);
    expect(isSafeZipEntryName('media/2f1c4b6e-0d3a')).toBe(true);
  });

  it('rejects every shape of path traversal', () => {
    for (const name of [
      '../escape',
      'media/../../escape',
      '/absolute',
      'C:/windows/system32',
      'media\\windows-separator',
      'media//empty-segment',
      'media/./here',
      '',
      'media/\u0000nul',
    ]) {
      expect(isSafeZipEntryName(name), name).toBe(false);
    }
  });
});

describe('writeZip / readZip', () => {
  it('round-trips entries in order, with their bytes intact', async () => {
    const archive = writeZip([
      { name: 'manifest.json', data: bytes('{"formatVersion":4}') },
      { name: 'media/one', data: new Uint8Array([0, 1, 2, 253, 254, 255]) },
    ]);

    const entries = await readZip(archive);
    expect(entries.map((entry) => entry.name)).toEqual(['manifest.json', 'media/one']);
    expect(decoder.decode(entries[0]!.data)).toBe('{"formatVersion":4}');
    expect([...entries[1]!.data]).toEqual([0, 1, 2, 253, 254, 255]);
  });

  it('round-trips an empty entry', async () => {
    const entries = await readZip(writeZip([{ name: 'media/empty', data: new Uint8Array(0) }]));
    expect(entries[0]!.data.byteLength).toBe(0);
  });

  it('round-trips non-ASCII names and content', async () => {
    const archive = writeZip([{ name: 'media/café-☕', data: bytes('naïve — ☕') }]);
    const entries = await readZip(archive);
    expect(entries[0]!.name).toBe('media/café-☕');
    expect(decoder.decode(entries[0]!.data)).toBe('naïve — ☕');
  });

  it('round-trips an archive with no entries', async () => {
    expect(await readZip(writeZip([]))).toEqual([]);
  });

  it('refuses to write an unsafe entry name', () => {
    expect(() => writeZip([{ name: '../escape', data: bytes('x') }])).toThrow(ZipFormatError);
  });

  it('refuses to write more entries than the cap', () => {
    const many = Array.from({ length: MAX_ZIP_ENTRIES + 1 }, (_, index) => ({
      name: `media/${index}`,
      data: new Uint8Array(0),
    }));
    expect(() => writeZip(many)).toThrow(/Too many archive entries/);
  });
});

describe('readZip rejects malformed and hostile archives', () => {
  it('rejects a file that is not a ZIP at all', async () => {
    await expect(readZip(bytes('this is a JSON backup, not a bundle'))).rejects.toThrow(
      ZipFormatError,
    );
  });

  it('rejects a file too short to hold a header', async () => {
    await expect(readZip(new Uint8Array(4))).rejects.toThrow(/too short/);
  });

  it('rejects an archive truncated mid-entry', async () => {
    const archive = writeZip([{ name: 'media/one', data: bytes('0123456789') }]);
    // Corrupt the stored data length in the local header so the entry runs past the file.
    const truncated = archive.slice(0, archive.byteLength - 30);
    await expect(readZip(truncated)).rejects.toThrow(ZipFormatError);
  });

  it('rejects an entry whose bytes were tampered with', async () => {
    const archive = writeZip([{ name: 'media/one', data: bytes('original') }]);
    const tampered = new Uint8Array(archive);
    // Flip a byte inside the stored payload; the CRC in both headers no longer matches.
    const payloadStart = 30 + 'media/one'.length;
    tampered[payloadStart] = tampered[payloadStart]! ^ 0xff;
    await expect(readZip(tampered)).rejects.toThrow(/checksum mismatch/);
  });

  it('rejects an entry whose name would traverse out of the archive', async () => {
    // Built by hand: writeZip refuses to produce this, which is the point.
    const archive = writeZip([{ name: 'media/one', data: bytes('x') }]);
    const hostile = new Uint8Array(archive);
    const nameAt = (offset: number) => {
      hostile.set(bytes('../one'), offset);
    };
    nameAt(30);
    // The central directory copy of the name sits after the local entry and its payload.
    const centralNameOffset = archive.indexOf(0x50, 30 + 'media/one'.length + 1);
    nameAt(centralNameOffset + 46);
    await expect(readZip(hostile)).rejects.toThrow(/unsafe name/);
  });

  it('rejects an archive claiming more entries than the cap', async () => {
    const archive = writeZip([{ name: 'media/one', data: bytes('x') }]);
    const hostile = new Uint8Array(archive);
    const eocd = hostile.byteLength - 22;
    // entriesOnDisk and entriesTotal are the two uint16s at EOCD+8 and EOCD+10.
    new DataView(hostile.buffer).setUint16(eocd + 10, 0xffff, true);
    await expect(readZip(hostile)).rejects.toThrow(/Too many archive entries/);
  });

  it('rejects an entry declaring an implausible expansion ratio', async () => {
    const archive = writeZip([{ name: 'media/one', data: bytes('0123456789') }]);
    const hostile = new Uint8Array(archive);
    const view = new DataView(hostile.buffer);
    const eocd = hostile.byteLength - 22;
    const centralOffset = view.getUint32(eocd + 16, true);
    // Claim it deflates to 100 MB from 10 stored bytes — the shape of a zip bomb.
    view.setUint16(centralOffset + 10, 8, true); // method: deflate
    view.setUint32(centralOffset + 24, 100 * 1024 * 1024, true); // uncompressed size
    await expect(readZip(hostile)).rejects.toThrow(
      /too large|implausible compression ratio|expands to more/,
    );
  });

  it('rejects an unsupported compression method', async () => {
    const archive = writeZip([{ name: 'media/one', data: bytes('0123456789') }]);
    const hostile = new Uint8Array(archive);
    const view = new DataView(hostile.buffer);
    const eocd = hostile.byteLength - 22;
    const centralOffset = view.getUint32(eocd + 16, true);
    view.setUint16(centralOffset + 10, 14, true); // LZMA
    await expect(readZip(hostile)).rejects.toThrow(/Unsupported compression method/);
  });
});

describe('readZip accepts a deflated archive', () => {
  it('reads an entry a different zipper compressed', async () => {
    if (typeof CompressionStream === 'undefined') return;
    // Repetitive enough to actually deflate, varied enough not to trip the ratio ceiling —
    // this test is about the deflate path, not about the limits.
    const payload = bytes(
      Array.from({ length: 256 }, (_, index) => `note ${index}: pack the bags
`).join(''),
    );
    const compressed = new Uint8Array(
      await new Response(
        new Blob([payload as BlobPart]).stream().pipeThrough(new CompressionStream('deflate-raw')),
      ).arrayBuffer(),
    );

    // Assemble the same archive shape writeZip produces, but with method 8.
    const stored = writeZip([{ name: 'media/one', data: payload }]);
    const view = new DataView(stored.buffer);
    const eocd = stored.byteLength - 22;
    const centralOffset = view.getUint32(eocd + 16, true);
    const nameLength = 'media/one'.length;

    const head = stored.slice(0, 30 + nameLength);
    new DataView(head.buffer).setUint16(8, 8, true); // method: deflate
    new DataView(head.buffer).setUint32(18, compressed.byteLength, true); // compressed size

    const central = stored.slice(centralOffset, eocd);
    const centralView = new DataView(central.buffer);
    centralView.setUint16(10, 8, true);
    centralView.setUint32(20, compressed.byteLength, true);

    const tail = stored.slice(eocd);
    const rebuilt = new Uint8Array(
      head.byteLength + compressed.byteLength + central.byteLength + tail.byteLength,
    );
    let at = 0;
    rebuilt.set(head, at);
    at += head.byteLength;
    rebuilt.set(compressed, at);
    at += compressed.byteLength;
    const newCentralOffset = at;
    rebuilt.set(central, at);
    at += central.byteLength;
    rebuilt.set(tail, at);
    const rebuiltView = new DataView(rebuilt.buffer);
    rebuiltView.setUint32(at + 12, central.byteLength, true);
    rebuiltView.setUint32(at + 16, newCentralOffset, true);

    const entries = await readZip(rebuilt);
    expect(entries).toHaveLength(1);
    expect(entries[0]!.data.byteLength).toBe(payload.byteLength);
    expect(decoder.decode(entries[0]!.data)).toBe(decoder.decode(payload));
  });
});

describe('listZipCentralDirectory / extractZipEntry', () => {
  it('lists every entry without requiring extraction', () => {
    const archive = writeZip([
      { name: 'manifest.json', data: bytes('{}') },
      { name: 'media/one', data: bytes('abc') },
      { name: 'media/two', data: bytes('defg') },
    ]);

    const central = listZipCentralDirectory(archive);
    expect(central.map((entry) => entry.name)).toEqual([
      'manifest.json',
      'media/one',
      'media/two',
    ]);
    expect(central.map((entry) => entry.uncompressedSize)).toEqual([2, 3, 4]);
  });

  it('extracts a single named entry on demand', async () => {
    const archive = writeZip([
      { name: 'manifest.json', data: bytes('{"ok":true}') },
      { name: 'media/one', data: bytes('payload-one') },
      { name: 'media/two', data: bytes('payload-two') },
    ]);
    const central = listZipCentralDirectory(archive);
    const only = central.find((entry) => entry.name === 'media/one')!;
    const extracted = await extractZipEntry(archive, only);
    expect(decoder.decode(extracted.data)).toBe('payload-one');
  });

  it('rejects duplicate entry names in the central directory', () => {
    const archive = writeZip([
      { name: 'media/one', data: bytes('first') },
      { name: 'media/two', data: bytes('second') },
    ]);
    const hostile = new Uint8Array(archive);
    // Overwrite the second central-directory name with the first entry's name.
    const view = new DataView(hostile.buffer);
    const eocd = hostile.byteLength - 22;
    const centralOffset = view.getUint32(eocd + 16, true);
    // First central header is 46 + nameLen('media/one'=9) = 55 bytes.
    const secondNameOffset = centralOffset + 55 + 46;
    hostile.set(bytes('media/one'), secondNameOffset);
    expect(() => listZipCentralDirectory(hostile)).toThrow(/duplicate entry name/);
  });

  it('rejects a truncated central directory', () => {
    const archive = writeZip([{ name: 'media/one', data: bytes('x') }]);
    const hostile = new Uint8Array(archive);
    const view = new DataView(hostile.buffer);
    const eocd = hostile.byteLength - 22;
    // Claim the central directory is longer than the file.
    view.setUint32(eocd + 12, 0xffffff, true);
    expect(() => listZipCentralDirectory(hostile)).toThrow(/central directory out of bounds/);
  });

  it('rejects when declared total uncompressed size exceeds the aggregate ceiling', () => {
    const archive = writeZip([{ name: 'media/one', data: bytes('0123456789') }]);
    const hostile = new Uint8Array(archive);
    const view = new DataView(hostile.buffer);
    const eocd = hostile.byteLength - 22;
    const centralOffset = view.getUint32(eocd + 16, true);
    view.setUint32(centralOffset + 24, MAX_ZIP_TOTAL_BYTES + 1, true);
    expect(() => listZipCentralDirectory(hostile)).toThrow(
      /too large|expands to more/,
    );
  });

  it('accepts a large number of tiny valid entries up to the cap', () => {
    const many = Array.from({ length: 200 }, (_, index) => ({
      name: `media/${index}`,
      data: new Uint8Array([index & 0xff]),
    }));
    const archive = writeZip(many);
    const central = listZipCentralDirectory(archive);
    expect(central).toHaveLength(200);
  });

  it('does not invoke inflate when only listing the central directory', async () => {
    if (typeof CompressionStream === 'undefined') return;
    const payload = bytes('note note note note note note note note ');
    const compressed = new Uint8Array(
      await new Response(
        new Blob([payload as BlobPart]).stream().pipeThrough(new CompressionStream('deflate-raw')),
      ).arrayBuffer(),
    );
    const stored = writeZip([{ name: 'media/one', data: payload }]);
    const view = new DataView(stored.buffer);
    const eocd = stored.byteLength - 22;
    const centralOffset = view.getUint32(eocd + 16, true);
    const nameLength = 'media/one'.length;
    const head = stored.slice(0, 30 + nameLength);
    new DataView(head.buffer).setUint16(8, 8, true);
    new DataView(head.buffer).setUint32(18, compressed.byteLength, true);
    const central = stored.slice(centralOffset, eocd);
    const centralView = new DataView(central.buffer);
    centralView.setUint16(10, 8, true);
    centralView.setUint32(20, compressed.byteLength, true);
    const tail = stored.slice(eocd);
    const rebuilt = new Uint8Array(
      head.byteLength + compressed.byteLength + central.byteLength + tail.byteLength,
    );
    let at = 0;
    rebuilt.set(head, at);
    at += head.byteLength;
    rebuilt.set(compressed, at);
    at += compressed.byteLength;
    const newCentralOffset = at;
    rebuilt.set(central, at);
    at += central.byteLength;
    rebuilt.set(tail, at);
    const rebuiltView = new DataView(rebuilt.buffer);
    rebuiltView.setUint32(at + 12, central.byteLength, true);
    rebuiltView.setUint32(at + 16, newCentralOffset, true);

    const inflateSpy = vi.spyOn(globalThis, 'DecompressionStream');
    const listed = listZipCentralDirectory(rebuilt);
    expect(listed).toHaveLength(1);
    expect(inflateSpy).not.toHaveBeenCalled();
    inflateSpy.mockRestore();
  });
});
