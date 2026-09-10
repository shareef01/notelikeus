import type { EditorLayout } from '@/store/uiStore';

/**
 * The fullscreen editor's outer shell: the viewport, and nothing narrower.
 *
 * It carries the note's own surface colour, so there is no seam between "the editor" and "the rest
 * of the screen" — in fullscreen there is no rest of the screen. The previous shell put a
 * `max-w-4xl` box in the middle of a plain app-background backdrop and drew a border down each
 * side of it, which at ordinary laptop widths (1046px, say) read as a narrow floating panel with
 * wide grey gutters rather than as a full-screen mode at all.
 */
export const FULLSCREEN_EDITOR_SHELL_CLASS = 'fixed inset-0 z-40 flex flex-col';

/**
 * The width constraint on the writing column itself.
 *
 * Filling the viewport is right for the shell and wrong for the text: a line of prose the width of
 * a wide monitor is unreadable. So the measure is constrained here, one level in, where the
 * gutters it creates are the note's own colour and read as margins.
 *
 * Empty for every other layout. Float and dock are already narrow panels, and constraining inside
 * one would leave the column floating in a box that is itself floating in a box. On phones the
 * viewport is narrower than the constraint anyway, so applying it would only add a no-op class.
 */
export function editorWritingColumnClass(layout: EditorLayout, isTabletUp: boolean): string {
  if (!isTabletUp || layout !== 'fullscreen') return '';
  return 'mx-auto max-w-editor';
}
