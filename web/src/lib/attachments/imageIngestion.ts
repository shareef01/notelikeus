/**
 * Pure extraction and policy helpers for clipboard paste and drag-and-drop image ingestion.
 *
 * Implements conservative ingestion:
 * - Image-only clipboard payloads (screenshots, copied image files) attach as images.
 * - Text payloads (plain text, markdown, copied web articles with text) take precedence as normal text pastes.
 * - External file drops are detected, filtered for image mime-types, and partitioned without browser navigation.
 */

/**
 * Extracts any image File objects directly present in a clipboard data payload.
 */
export function extractImageFilesFromClipboard(clipboardData: DataTransfer | null): File[] {
  if (!clipboardData) return [];
  const files: File[] = [];

  if (clipboardData.items && clipboardData.items.length > 0) {
    for (let i = 0; i < clipboardData.items.length; i++) {
      const item = clipboardData.items[i];
      if (item.kind === 'file' && item.type.startsWith('image/')) {
        const file = item.getAsFile();
        if (file) {
          files.push(file);
        }
      }
    }
  } else if (clipboardData.files && clipboardData.files.length > 0) {
    for (let i = 0; i < clipboardData.files.length; i++) {
      const file = clipboardData.files[i];
      if (file.type.startsWith('image/')) {
        files.push(file);
      }
    }
  }

  return files;
}

/**
 * Decides whether a clipboard event should be intercepted to attach image files.
 *
 * Conservative policy:
 * - If the clipboard contains non-empty, meaningful text (`text/plain`), normal text paste
 *   takes precedence and this returns false. This prevents accidental image creation when copying
 *   articles or rich text containing inline graphics.
 * - If there is no meaningful text and at least one image file item exists, this returns true.
 */
export function clipboardShouldAttachImages(clipboardData: DataTransfer | null): boolean {
  if (!clipboardData) return false;

  const text = clipboardData.getData('text/plain') ?? clipboardData.getData('text') ?? '';
  if (text.trim().length > 0) {
    return false;
  }

  const imageFiles = extractImageFilesFromClipboard(clipboardData);
  return imageFiles.length > 0;
}

/**
 * Determines whether a drag event represents external files being dragged into the browser.
 *
 * Distinguishes external OS files from internal DOM drags (text selection, link dragging, internal elements).
 */
export function isDragEventWithFiles(dataTransfer: DataTransfer | null): boolean {
  if (!dataTransfer || !dataTransfer.types) return false;
  const types = new Set(dataTransfer.types);
  return types.has('Files') || types.has('application/x-moz-file');
}

/**
 * Extracts dropped File objects from a DataTransfer payload in order.
 */
export function extractFilesFromDrop(dataTransfer: DataTransfer | null): File[] {
  if (!dataTransfer || !dataTransfer.files) return [];
  return Array.from(dataTransfer.files);
}

export interface PartitionedDroppedFiles {
  imageFiles: File[];
  unsupportedFiles: File[];
}

/**
 * Partitions dropped files into supported images (`image/*`) and unsupported files.
 */
export function partitionDroppedFiles(files: File[]): PartitionedDroppedFiles {
  const imageFiles: File[] = [];
  const unsupportedFiles: File[] = [];

  for (const file of files) {
    if (file.type.startsWith('image/')) {
      imageFiles.push(file);
    } else {
      unsupportedFiles.push(file);
    }
  }

  return { imageFiles, unsupportedFiles };
}
