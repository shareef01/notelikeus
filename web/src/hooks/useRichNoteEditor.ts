import { useEffect, useRef } from 'react';
import { Extension, useEditor, useEditorState } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import Placeholder from '@tiptap/extension-placeholder';
import { useToastStore } from '@/store/toastStore';
import { EditorState, Plugin } from '@tiptap/pm/state';
import { MAX_NOTE_CONTENT_CHARS } from '@/lib/backup/constants';
import { parseNoteDocument, serializeNoteDocument } from '@/lib/text/richNoteDocument';

const lengthLimit = Extension.create({
  name: 'noteLengthLimit',
  addProseMirrorPlugins() {
    return [new Plugin({ filterTransaction: transaction => {
      if (!transaction.docChanged || serializeNoteDocument(transaction.doc.toJSON()).length <= MAX_NOTE_CONTENT_CHARS) return true;
      useToastStore.getState().show('This note is at the text limit. Shorten it or start another note.', 'error');
      return false;
    } })];
  },
});

export function useRichNoteEditor(content: string, onChange: (text: string) => void, boundary?: {undo: () => boolean; redo: () => boolean}) {
  const boundaryRef = useRef(boundary);
  boundaryRef.current = boundary;
  const lastContent = useRef(content);
  const changeRef = useRef(onChange);
  changeRef.current = onChange;
  const editor = useEditor({
    extensions: [Extension.create({
      name: 'conversionHistoryBoundary', priority: 1100,
      addKeyboardShortcuts() { return {
        'Mod-z': () => !this.editor.can().undo() && (boundaryRef.current?.undo() ?? false),
        'Mod-Shift-z': () => !this.editor.can().redo() && (boundaryRef.current?.redo() ?? false),
        'Mod-y': () => !this.editor.can().redo() && (boundaryRef.current?.redo() ?? false),
      }; },
    }), StarterKit.configure({
      heading: false, blockquote: false, codeBlock: false, code: false,
      horizontalRule: false, orderedList: false, strike: false, underline: false,
      trailingNode: false,
      link: { openOnClick: false, autolink: false, isAllowedUri: url => /^(https?:|mailto:)/i.test(url) },
    }), Placeholder.configure({ placeholder: 'Start writing…' }), lengthLimit],
    content: parseNoteDocument(content),
    shouldRerenderOnTransaction: false,
    editorProps: {
      // Keep the caret clear of the sticky formatting toolbar and floating footer.
      scrollThreshold: { top: 64, bottom: 96, left: 0, right: 0 },
      scrollMargin: { top: 64, bottom: 96, left: 8, right: 8 },
      attributes: { role: 'textbox', 'aria-label': 'Note body', 'aria-multiline': 'true', spellcheck: 'true', class: 'rich-note-body', placeholder: 'Start writing…' },
    },
    onUpdate: ({ editor: current }) => {
      const next = serializeNoteDocument(current.getJSON());
      if (next === lastContent.current) return;
      lastContent.current = next;
      changeRef.current(next);
    },
  });
  useEffect(() => {
    if (!editor || content === lastContent.current) return;
    lastContent.current = content;
    // Hydration and mode changes must never enter typing history.
    editor.view.updateState(EditorState.create({schema: editor.schema,
      doc: editor.schema.nodeFromJSON(parseNoteDocument(content)), plugins: editor.state.plugins}));
    editor.view.dispatch(editor.state.tr.setMeta('preventUpdate', true));
  }, [content, editor]);
  const active = useEditorState({ editor, selector: ({ editor: current }) => ({
    bold: current?.isActive('bold') ?? false,
    italic: current?.isActive('italic') ?? false,
    bullet: current?.isActive('bulletList') ?? false,
    link: current?.isActive('link') ?? false,
  }) });
  return { editor, active };
}
