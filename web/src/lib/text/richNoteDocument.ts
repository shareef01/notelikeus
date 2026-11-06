import type { JSONContent } from '@tiptap/react';
import { toSafeHref } from './markdown';

type Mark = NonNullable<JSONContent['marks']>[number];
function inline(text: string, marks: Mark[] = [], depth = 0): JSONContent[] {
  if (!text) return [];
  if (depth > 16) return [{ type: 'text', text, marks }];
  const nodes: JSONContent[] = [];
  // Deliberately match the native note format, including marks around whitespace.
  // A general Markdown parser changes literal syntax and rejects legacy spans.
  const pattern = /\*\*(.+?)\*\*|_(.+?)_|\[(.+?)\]\(([^)]+)\)/g;
  let offset = 0;
  for (const match of text.matchAll(pattern)) {
    if (match.index > offset) nodes.push({ type: 'text', text: text.slice(offset, match.index), marks });
    if (match[1] || match[2]) {
      nodes.push(...inline(match[1] ?? match[2], [...marks, { type: match[1] ? 'bold' : 'italic' }], depth + 1));
    } else if (toSafeHref(match[4]) !== '#') {
      nodes.push(...inline(match[3], [...marks, { type: 'link', attrs: { href: toSafeHref(match[4]) } }], depth + 1));
    } else nodes.push({ type: 'text', text: match[0], marks });
    offset = match.index + match[0].length;
  }
  if (offset < text.length) nodes.push({ type: 'text', text: text.slice(offset), marks });
  return nodes;
}

export function parseNoteDocument(text: string): JSONContent {
  const content: JSONContent[] = [];
  for (const line of text.split('\n')) {
    const paragraph = { type: 'paragraph', content: inline(line.startsWith('• ') ? line.slice(2) : line) };
    if (line.startsWith('• ')) {
      let list = content.at(-1);
      if (list?.type !== 'bulletList') {
        list = { type: 'bulletList', content: [] };
        content.push(list);
      }
      list.content!.push({ type: 'listItem', content: [paragraph] });
    } else content.push(paragraph);
  }
  return { type: 'doc', content };
}

function inlineText(nodes: JSONContent[]): string {
  let result = '';
  let active: Mark[] = [];
  const close = (mark: Mark) => mark.type === 'link' ? `](${mark.attrs?.href})` : mark.type === 'bold' ? '**' : '_';
  const open = (mark: Mark) => mark.type === 'link' ? '[' : mark.type === 'bold' ? '**' : '_';
  for (const node of [...nodes, { type: 'text', text: '' }]) {
    if (node.type === 'hardBreak') {
      for (let i = active.length - 1; i >= 0; i--) result += close(active[i]);
      active = [];
      result += '\n';
      continue;
    }
    const marks = [...(node.marks ?? [])].filter(m => ['link', 'bold', 'italic'].includes(m.type))
      .sort((a, b) => ['link', 'bold', 'italic'].indexOf(a.type) - ['link', 'bold', 'italic'].indexOf(b.type));
    let common = 0;
    while (common < active.length && common < marks.length && JSON.stringify(active[common]) === JSON.stringify(marks[common])) common++;
    for (let i = active.length - 1; i >= common; i--) result += close(active[i]);
    for (let i = common; i < marks.length; i++) result += open(marks[i]);
    result += node.type === 'hardBreak' ? '\n' : node.text ?? '';
    active = marks;
  }
  return result;
}

export function serializeNoteDocument(doc: JSONContent): string {
  const block = (node: JSONContent): string => {
    if (node.type === 'paragraph') return inlineText(node.content ?? []);
    if (node.type === 'bulletList') return (node.content ?? []).map(item => `• ${(item.content ?? []).map(block).join('\n')}`).join('\n');
    return (node.content ?? []).map(block).join('\n');
  };
  return block(doc);
}
