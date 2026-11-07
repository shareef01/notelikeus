import type { EditorLayout } from '@/store/uiStore';

/** Fullscreen fills the viewport with the neutral editor surface. */
export const FULLSCREEN_EDITOR_SHELL_CLASS = 'fixed inset-0 z-40 flex flex-col';

/** Keep a comfortable writing measure inside every desktop editor layout. */
export function editorWritingColumnClass(layout: EditorLayout, isTabletUp: boolean): string {
  if (!isTabletUp) return '';
  return layout === 'dock' ? 'max-w-editor' : 'mx-auto max-w-editor';
}
