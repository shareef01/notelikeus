import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  FULLSCREEN_EDITOR_SHELL_CLASS,
  editorWritingColumnClass,
} from '@/screens/main/editorShellLayout';

// Read from the vitest root (web/) rather than from import.meta.url, which the transform does not
// hand back as a file: URL.
const editorScreen = readFileSync(
  resolve(process.cwd(), 'src/screens/EditorScreen.tsx'),
  'utf8',
);

function fullscreenShellMarkup(): string {
  const start = editorScreen.indexOf("if (editorLayout === 'fullscreen')");
  expect(start).toBeGreaterThan(-1);
  return editorScreen.slice(start, start + 600);
}

describe('editorWritingColumnClass', () => {
  it('constrains the measure only in fullscreen', () => {
    expect(editorWritingColumnClass('fullscreen', true)).toBe('mx-auto max-w-editor');
    expect(editorWritingColumnClass('dock', true)).toBe('');
    expect(editorWritingColumnClass('float', true)).toBe('');
  });

  it('adds nothing on phones, where the viewport is already the constraint', () => {
    expect(editorWritingColumnClass('fullscreen', false)).toBe('');
    expect(editorWritingColumnClass('dock', false)).toBe('');
  });
});

describe('fullscreen editor shell', () => {
  /**
   * The regression: the shell used to be `max-w-4xl` centred on a plain app-background backdrop,
   * so "full screen" rendered as a narrow coloured panel between two wide grey gutters — most
   * obviously around 1046x512, where the panel is barely wider than the dock layout it replaced.
   */
  it('fills the viewport instead of centring a fixed-width panel', () => {
    expect(FULLSCREEN_EDITOR_SHELL_CLASS).toContain('fixed inset-0');
    expect(FULLSCREEN_EDITOR_SHELL_CLASS).not.toMatch(/max-w-/);
    expect(FULLSCREEN_EDITOR_SHELL_CLASS).not.toMatch(/\bjustify-center\b/);
  });

  it('is the note surface itself, with no backdrop showing through beside it', () => {
    const shell = fullscreenShellMarkup();

    expect(shell).toContain('FULLSCREEN_EDITOR_SHELL_CLASS');
    expect(shell).toContain('style={surface}');
    // A background token here would paint over the note colour at the edges, and a side border
    // would draw the seam the old layout was criticised for.
    expect(shell).not.toContain('background-rgb');
    expect(shell).not.toContain('border-x');
  });

  /** The dialog semantics the focus trap and the overlay keyboard handling depend on. */
  it('keeps the dialog role, label, and panel ref on the shell', () => {
    const shell = fullscreenShellMarkup();

    expect(shell).toContain('ref={overlayPanelRef}');
    expect(shell).toContain('role="dialog"');
    expect(shell).toContain('aria-modal="true"');
    expect(shell).toContain('aria-label="Note editor"');
  });
});
