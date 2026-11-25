import { useEffect, useState } from 'react';
import { Extension, type Editor } from '@tiptap/react';
import { Plugin, PluginKey } from '@tiptap/pm/state';
import { Decoration, DecorationSet } from '@tiptap/pm/view';
import { bodyDigest, comparePlacementOrder, placementPositions } from '@/lib/attachments/attachmentPlacement';
import type { Attachment } from '@/types/attachment';

const key = new PluginKey<DecorationSet>('inlineAttachmentWidgets');
const emptySlots = new Map<string, HTMLElement>();

export const inlineAttachmentWidgets = Extension.create({
  name: 'inlineAttachmentWidgets',
  addProseMirrorPlugins() {
    return [new Plugin({
      key,
      state: {
        init: () => DecorationSet.empty,
        apply: (transaction, current) => transaction.getMeta(key) ?? current.map(transaction.mapping, transaction.doc),
      },
      props: { decorations: (state) => key.getState(state) },
    })];
  },
});

export function useInlineAttachmentWidgets(editor: Editor | null, body: string, attachments: Attachment[], enabled: boolean) {
  const [slots, setSlots] = useState(emptySlots);
  useEffect(() => {
    if (!editor || editor.isDestroyed) return;
    const next = new Map<string, HTMLElement>();
    const decorations: Decoration[] = [];
    if (enabled && attachments.some((attachment) => attachment.placement)) {
      const digest = bodyDigest(body);
      const positions = placementPositions(editor.state.doc, body);
      const ordered = attachments.flatMap(({ id, placement }) => {
        if (placement?.bodySha256 !== digest) return [];
        const position = positions.get(placement.offset);
        return position === undefined ? [] : [{ id, position, ordinal: placement.ordinal }];
      }).sort((a, b) => a.ordinal - b.ordinal || comparePlacementOrder(a.id, b.id));

      for (const [index, { id, position }] of ordered.entries()) {
        // A stable host keeps the active image controls and viewer mounted as text moves.
        const host = slots.get(id) ?? document.createElement('div');
        host.dataset.inlineAttachment = '';
        next.set(id, host);
        decorations.push(Decoration.widget(position, host, {
          side: index + 1,
          stopEvent: () => true,
          ignoreSelection: true,
        }));
      }
    }
    const current = key.getState(editor.state);
    if (decorations.length || current?.find().length) {
      editor.view.dispatch(editor.state.tr
        .setMeta(key, DecorationSet.create(editor.state.doc, decorations))
        .setMeta('addToHistory', false));
    }
    if (next.size !== slots.size || Array.from(next.keys()).some((id) => !slots.has(id))) {
      setSlots(next);
    }
  }, [attachments, body, editor, enabled, slots]);

  return enabled ? slots : emptySlots;
}
