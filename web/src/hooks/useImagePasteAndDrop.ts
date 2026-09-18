import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ClipboardEvent as ReactClipboardEvent,
  type DragEvent as ReactDragEvent,
} from 'react';
import {
  clipboardShouldAttachImages,
  extractFilesFromDrop,
  extractImageFilesFromClipboard,
  isDragEventWithFiles,
  partitionDroppedFiles,
} from '@/lib/attachments/imageIngestion';
import { useToastStore } from '@/store/toastStore';

interface UseImagePasteAndDropOptions {
  addAttachment: (file: File) => Promise<void>;
  attachmentsEnabled: boolean;
}

export interface UseImagePasteAndDropReturn {
  isDragActive: boolean;
  handlePaste: (event: ReactClipboardEvent | ClipboardEvent) => Promise<void>;
  handleDragEnter: (event: ReactDragEvent) => void;
  handleDragOver: (event: ReactDragEvent) => void;
  handleDragLeave: (event: ReactDragEvent) => void;
  handleDrop: (event: ReactDragEvent) => Promise<void>;
}

/**
 * Hook managing clipboard paste and drag-and-drop image ingestion for the note editor.
 *
 * Guarantees:
 * - Normal text paste bubbles to focused inputs without interference.
 * - Image-only clipboard payloads attach sequentially through canonical editor.addAttachment.
 * - Drag overlay maintains stable state across nested editor children via a drag depth counter.
 * - Abnormal sequences (e.g. window blur or component unmount while dragging) cleanly reset overlay state.
 * - Dropping files (supported or unsupported) always calls preventDefault() to protect against browser navigation.
 * - Unsupported files in a drop trigger a single consolidated error toast.
 */
export function useImagePasteAndDrop({
  addAttachment,
  attachmentsEnabled,
}: UseImagePasteAndDropOptions): UseImagePasteAndDropReturn {
  const [isDragActive, setIsDragActive] = useState(false);
  const dragDepthRef = useRef(0);

  // Reset drag depth and active overlay if window loses focus or unmounts during drag
  useEffect(() => {
    const handleWindowBlur = () => {
      dragDepthRef.current = 0;
      setIsDragActive(false);
    };
    window.addEventListener('blur', handleWindowBlur);
    return () => {
      window.removeEventListener('blur', handleWindowBlur);
      dragDepthRef.current = 0;
    };
  }, []);

  const handlePaste = useCallback(
    async (event: ReactClipboardEvent | ClipboardEvent) => {
      const clipboardData = 'clipboardData' in event ? event.clipboardData : null;
      if (!clipboardData) return;

      if (!clipboardShouldAttachImages(clipboardData)) {
        // Let ordinary text paste proceed normally to the focused input/textarea
        return;
      }

      // Image-only payload: intercept and prevent raw image text insertion into inputs
      event.preventDefault();

      if (!attachmentsEnabled) {
        useToastStore.getState().show('Attachments are not enabled', 'error');
        return;
      }

      const imageFiles = extractImageFilesFromClipboard(clipboardData);
      for (const file of imageFiles) {
        await addAttachment(file);
      }
    },
    [addAttachment, attachmentsEnabled],
  );

  const handleDragEnter = useCallback(
    (event: ReactDragEvent) => {
      if (!isDragEventWithFiles(event.dataTransfer)) return;
      event.preventDefault();

      if (!attachmentsEnabled) return;

      dragDepthRef.current += 1;
      if (dragDepthRef.current === 1) {
        setIsDragActive(true);
      }
    },
    [attachmentsEnabled],
  );

  const handleDragOver = useCallback(
    (event: ReactDragEvent) => {
      if (!isDragEventWithFiles(event.dataTransfer)) return;
      // Critical: always preventDefault on dragOver to signal a drop target and prevent browser navigation
      event.preventDefault();

      if (attachmentsEnabled && event.dataTransfer) {
        event.dataTransfer.dropEffect = 'copy';
      }
    },
    [attachmentsEnabled],
  );

  const handleDragLeave = useCallback(
    (event: ReactDragEvent) => {
      if (!isDragEventWithFiles(event.dataTransfer)) return;
      event.preventDefault();

      if (!attachmentsEnabled) return;

      dragDepthRef.current = Math.max(0, dragDepthRef.current - 1);
      if (dragDepthRef.current === 0) {
        setIsDragActive(false);
      }
    },
    [attachmentsEnabled],
  );

  const handleDrop = useCallback(
    async (event: ReactDragEvent) => {
      if (!isDragEventWithFiles(event.dataTransfer)) return;
      // Critical: always preventDefault on drop to avoid browser navigating away from PWA
      event.preventDefault();

      dragDepthRef.current = 0;
      setIsDragActive(false);

      if (!attachmentsEnabled) {
        useToastStore.getState().show('Attachments are not enabled', 'error');
        return;
      }

      const files = extractFilesFromDrop(event.dataTransfer);
      if (files.length === 0) return;

      const { imageFiles, unsupportedFiles } = partitionDroppedFiles(files);

      // Single consolidated toast for invalid/non-image files
      if (unsupportedFiles.length > 0) {
        useToastStore.getState().show('Only images are supported', 'error');
      }

      // Process valid images sequentially through the canonical attachment pipeline
      for (const file of imageFiles) {
        await addAttachment(file);
      }
    },
    [addAttachment, attachmentsEnabled],
  );

  return {
    isDragActive,
    handlePaste,
    handleDragEnter,
    handleDragOver,
    handleDragLeave,
    handleDrop,
  };
}
