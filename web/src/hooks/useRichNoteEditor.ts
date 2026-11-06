import { useEffect, useRef } from 'react';
import { Extension, useEditor, useEditorState } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import Placeholder from '@tiptap/extension-placeholder';
import { useToastStore } from '@/store/toastStore';
import { Plugin } from '@tiptap/pm/state';
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

export function useRichNoteEditor(content: string, onChange: (text: string) => void) {
  const lastContent = useRef(content);
  const changeRef = useRef(onChange);
  changeRef.current = onChange;
  const editor = useEditor({
    extensions: [StarterKit.configure({
      heading: false, blockquote: false, codeBlock: false, code: false,
      horizontalRule: false, orderedList: false, strike: false, underline: false,
      trailingNode: false,
      link: { openOnClick: false, autolink: false, isAllowedUri: url => /^(https?:|mailto:)/i.test(url) },
    }), Placeholder.configure({ placeholder: 'Start writing…' }), lengthLimit],
    content: parseNoteDocument(content),
    shouldRerenderOnTransaction: false,
    editorProps: { attributes: { role: 'textbox', 'aria-label': 'Note body', 'aria-multiline': 'true', spellcheck: 'true', class: 'rich-note-body', placeholder: 'Start writing…' } },
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
    editor.commands.setContent(parseNoteDocument(content), { emitUpdate: false });
  }, [content, editor]);
  const active = useEditorState({ editor, selector: ({ editor: current }) => ({
    bold: current?.isActive('bold') ?? false,
    italic: current?.isActive('italic') ?? false,
    bullet: current?.isActive('bulletList') ?? false,
    link: current?.isActive('link') ?? false,
  }) });
  return { editor, active };
}
