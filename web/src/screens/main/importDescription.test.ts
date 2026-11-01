import { describe, it, expect } from 'vitest';
import { formatPendingImportDescription } from './importDescription';
import type { PendingImport } from './useAccountActions';

describe('formatPendingImportDescription', () => {
  it('returns empty string for null input', () => {
    expect(formatPendingImportDescription(null)).toBe('');
  });

  it('formats notes-only JSON backup notice correctly', () => {
    const jsonImport: PendingImport = {
      merged: [],
      result: { notesImported: 5, labelsCreated: 0 },
      attachmentsImported: 0,
      attachmentsSkipped: 0,
      warnings: [],
      bundle: undefined,
    };

    expect(formatPendingImportDescription(jsonImport)).toBe(
      "They're added alongside what's already here, so importing this file again will create another copy of each note. Images aren't part of a notes-only JSON backup and won't come across.",
    );
  });

  it('formats archive bundle with restored images correctly', () => {
    const bundleImport: PendingImport = {
      merged: [],
      result: { notesImported: 3, labelsCreated: 1 },
      attachmentsImported: 2,
      attachmentsSkipped: 0,
      warnings: [],
      bundle: {} as any,
    };

    expect(formatPendingImportDescription(bundleImport)).toBe(
      "They're added alongside what's already here, so importing this file again will create another copy of each note. 2 images will be restored too.",
    );
  });

  it('formats archive bundle with single restored image correctly', () => {
    const bundleImport: PendingImport = {
      merged: [],
      result: { notesImported: 1, labelsCreated: 0 },
      attachmentsImported: 1,
      attachmentsSkipped: 0,
      warnings: [],
      bundle: {} as any,
    };

    expect(formatPendingImportDescription(bundleImport)).toBe(
      "They're added alongside what's already here, so importing this file again will create another copy of each note. 1 image will be restored too.",
    );
  });

  it('formats archive bundle with both restored and skipped images without JSON warning', () => {
    const bundleImport: PendingImport = {
      merged: [],
      result: { notesImported: 4, labelsCreated: 0 },
      attachmentsImported: 1,
      attachmentsSkipped: 2,
      warnings: [],
      bundle: {} as any,
    };

    expect(formatPendingImportDescription(bundleImport)).toBe(
      "They're added alongside what's already here, so importing this file again will create another copy of each note. 1 image will be restored too. 2 images in this file couldn't be read and will be skipped.",
    );
  });

  it('formats archive bundle with 0 restored images and skipped images without JSON warning', () => {
    const bundleImport: PendingImport = {
      merged: [],
      result: { notesImported: 4, labelsCreated: 0 },
      attachmentsImported: 0,
      attachmentsSkipped: 3,
      warnings: [],
      bundle: {} as any,
    };

    expect(formatPendingImportDescription(bundleImport)).toBe(
      "They're added alongside what's already here, so importing this file again will create another copy of each note. 3 images in this file couldn't be read and will be skipped.",
    );
  });

  it('formats archive bundle with 0 images cleanly', () => {
    const bundleImport: PendingImport = {
      merged: [],
      result: { notesImported: 2, labelsCreated: 0 },
      attachmentsImported: 0,
      attachmentsSkipped: 0,
      warnings: [],
      bundle: {} as any,
    };

    expect(formatPendingImportDescription(bundleImport)).toBe(
      "They're added alongside what's already here, so importing this file again will create another copy of each note.",
    );
  });
});
