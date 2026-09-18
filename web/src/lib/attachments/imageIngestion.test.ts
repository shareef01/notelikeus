import { describe, expect, it } from 'vitest';
import {
  clipboardShouldAttachImages,
  extractFilesFromDrop,
  extractImageFilesFromClipboard,
  isDragEventWithFiles,
  partitionDroppedFiles,
} from './imageIngestion';

function createMockClipboardData(options: {
  text?: string;
  items?: Array<{ kind: string; type: string; file?: File }>;
  files?: File[];
}): DataTransfer {
  const text = options.text ?? '';
  const items = options.items ?? [];
  const files = options.files ?? [];

  return {
    getData: (format: string) => {
      if (format === 'text/plain' || format === 'text') return text;
      return '';
    },
    types: [
      ...(text ? ['text/plain'] : []),
      ...(items.length > 0 || files.length > 0 ? ['Files'] : []),
    ],
    items: items.map((item) => ({
      kind: item.kind,
      type: item.type,
      getAsFile: () => item.file ?? null,
    })),
    files,
  } as unknown as DataTransfer;
}

describe('imageIngestion — extractImageFilesFromClipboard', () => {
  it('returns empty array when clipboardData is null', () => {
    expect(extractImageFilesFromClipboard(null)).toEqual([]);
  });

  it('extracts File objects from image items in clipboardData.items', () => {
    const pngFile = new File(['fake-png'], 'screenshot.png', { type: 'image/png' });
    const clipboard = createMockClipboardData({
      items: [
        { kind: 'file', type: 'image/png', file: pngFile },
        { kind: 'string', type: 'text/plain' },
      ],
    });

    const result = extractImageFilesFromClipboard(clipboard);
    expect(result).toHaveLength(1);
    expect(result[0]).toBe(pngFile);
  });

  it('ignores non-image file items (e.g. text or pdf file items)', () => {
    const pdfFile = new File(['fake-pdf'], 'document.pdf', { type: 'application/pdf' });
    const clipboard = createMockClipboardData({
      items: [{ kind: 'file', type: 'application/pdf', file: pdfFile }],
    });

    const result = extractImageFilesFromClipboard(clipboard);
    expect(result).toHaveLength(0);
  });

  it('falls back to clipboardData.files if items are absent or empty', () => {
    const webpFile = new File(['fake-webp'], 'photo.webp', { type: 'image/webp' });
    const clipboard = createMockClipboardData({
      files: [webpFile],
    });

    const result = extractImageFilesFromClipboard(clipboard);
    expect(result).toHaveLength(1);
    expect(result[0]).toBe(webpFile);
  });
});

describe('imageIngestion — clipboardShouldAttachImages', () => {
  it('returns false when clipboardData is null', () => {
    expect(clipboardShouldAttachImages(null)).toBe(false);
  });

  it('returns false when clipboard contains only text', () => {
    const clipboard = createMockClipboardData({
      text: 'Just some copied text',
    });
    expect(clipboardShouldAttachImages(clipboard)).toBe(false);
  });

  it('returns true for image-only clipboard (empty text, image item present)', () => {
    const pngFile = new File(['screenshot'], 'screen.png', { type: 'image/png' });
    const clipboard = createMockClipboardData({
      text: '',
      items: [{ kind: 'file', type: 'image/png', file: pngFile }],
    });
    expect(clipboardShouldAttachImages(clipboard)).toBe(true);
  });

  it('returns true when text is only whitespace and image item is present', () => {
    const pngFile = new File(['screenshot'], 'screen.png', { type: 'image/png' });
    const clipboard = createMockClipboardData({
      text: '   \n\t  ',
      items: [{ kind: 'file', type: 'image/png', file: pngFile }],
    });
    expect(clipboardShouldAttachImages(clipboard)).toBe(true);
  });

  it('returns false for mixed payload with meaningful text (conservative text precedence)', () => {
    const pngFile = new File(['article-img'], 'img.png', { type: 'image/png' });
    const clipboard = createMockClipboardData({
      text: 'Article headline and paragraph text copied together',
      items: [{ kind: 'file', type: 'image/png', file: pngFile }],
    });
    // Meaningful text must take precedence to prevent unwanted attachments when copying web articles
    expect(clipboardShouldAttachImages(clipboard)).toBe(false);
  });

  it('returns false when clipboard is completely empty', () => {
    const clipboard = createMockClipboardData({});
    expect(clipboardShouldAttachImages(clipboard)).toBe(false);
  });
});

describe('imageIngestion — isDragEventWithFiles', () => {
  it('returns false when dataTransfer is null', () => {
    expect(isDragEventWithFiles(null)).toBe(false);
  });

  it('returns true when types includes "Files"', () => {
    const dataTransfer = { types: ['Files'] } as unknown as DataTransfer;
    expect(isDragEventWithFiles(dataTransfer)).toBe(true);
  });

  it('returns true when types array-like contains "Files" or legacy "application/x-moz-file"', () => {
    const mozTransfer = { types: ['application/x-moz-file'] } as unknown as DataTransfer;
    expect(isDragEventWithFiles(mozTransfer)).toBe(true);
  });

  it('returns false for internal UI drags (e.g. text selection or links)', () => {
    const textTransfer = { types: ['text/plain'] } as unknown as DataTransfer;
    expect(isDragEventWithFiles(textTransfer)).toBe(false);

    const uriTransfer = { types: ['text/uri-list'] } as unknown as DataTransfer;
    expect(isDragEventWithFiles(uriTransfer)).toBe(false);
  });
});

describe('imageIngestion — extractFilesFromDrop and partitionDroppedFiles', () => {
  it('extractFilesFromDrop returns empty array when dataTransfer or files is null', () => {
    expect(extractFilesFromDrop(null)).toEqual([]);
    expect(extractFilesFromDrop({} as DataTransfer)).toEqual([]);
  });

  it('extractFilesFromDrop returns files in original order', () => {
    const f1 = new File(['1'], 'img1.png', { type: 'image/png' });
    const f2 = new File(['2'], 'img2.jpg', { type: 'image/jpeg' });
    const dataTransfer = { files: [f1, f2] } as unknown as DataTransfer;

    const result = extractFilesFromDrop(dataTransfer);
    expect(result).toEqual([f1, f2]);
  });

  it('partitionDroppedFiles cleanly partitions images from unsupported files', () => {
    const img1 = new File(['1'], 'shot.png', { type: 'image/png' });
    const doc1 = new File(['2'], 'notes.pdf', { type: 'application/pdf' });
    const img2 = new File(['3'], 'photo.webp', { type: 'image/webp' });
    const doc2 = new File(['4'], 'script.js', { type: 'application/javascript' });

    const partitioned = partitionDroppedFiles([img1, doc1, img2, doc2]);
    expect(partitioned.imageFiles).toEqual([img1, img2]);
    expect(partitioned.unsupportedFiles).toEqual([doc1, doc2]);
  });
});
