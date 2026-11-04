import type { PendingImport } from './useAccountActions';

export function formatPendingImportDescription(pendingImport: PendingImport | null): string {
  if (pendingImport == null) return '';

  const base =
    "They're added alongside what's already here, so importing this file again will create another copy of each note.";

  if (pendingImport.bundle != null) {
    const parts: string[] = [base];
    if (pendingImport.attachmentsImported > 0) {
      parts.push(
        `${pendingImport.attachmentsImported} image${
          pendingImport.attachmentsImported === 1 ? '' : 's'
        } will be restored too.`,
      );
    }
    if (pendingImport.attachmentsSkipped > 0) {
      parts.push(
        `${pendingImport.attachmentsSkipped} image${
          pendingImport.attachmentsSkipped === 1 ? '' : 's'
        } in this file couldn't be read and will be skipped.`,
      );
    }
    return parts.join(' ');
  }

  return `${base} Images aren't part of a notes-only JSON backup and won't come across.`;
}
