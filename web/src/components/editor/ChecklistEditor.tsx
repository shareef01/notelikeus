import type { ChecklistItem } from '@/types/checklist';
import { CloseIcon } from '@/components/icons/Icons';
import { sortChecklistItems } from '@/types/checklist';

interface ChecklistEditorProps {
  items: ChecklistItem[];
  contentColor: string;
  onUpdate: (id: string, text: string, isChecked: boolean) => void;
  onAdd: () => void;
  onRemove: (id: string) => void;
  onConvertToText?: () => void;
}

export function ChecklistEditor({
  items,
  contentColor,
  onUpdate,
  onAdd,
  onRemove,
  onConvertToText,
}: ChecklistEditorProps) {
  const sorted = sortChecklistItems(items);

  return (
    <div className="mt-4 space-y-1">
      {sorted.map((item) => (
        <div key={item.id} className="flex min-h-12 items-center gap-2">
          <input
            type="checkbox"
            checked={item.isChecked}
            onChange={(event) => onUpdate(item.id, item.text, event.target.checked)}
            className="size-5 shrink-0 rounded border-current accent-current"
            style={{ color: contentColor }}
            aria-label={item.text || 'Checklist item'}
          />
          <input
            type="text"
            value={item.text}
            onChange={(event) => onUpdate(item.id, event.target.value, item.isChecked)}
            placeholder="List item"
            className={`min-w-0 flex-1 bg-transparent text-base leading-relaxed outline-none placeholder:opacity-40 ${
              item.isChecked ? 'line-through opacity-60' : ''
            }`}
            style={{ color: contentColor }}
          />
          <button
            type="button"
            onClick={() => onRemove(item.id)}
            className="flex size-9 shrink-0 items-center justify-center rounded-full opacity-60 hover:bg-black/10 hover:opacity-100"
            style={{ color: contentColor }}
            aria-label="Remove item"
          >
            <CloseIcon size={18} />
          </button>
        </div>
      ))}

      <button
        type="button"
        onClick={onAdd}
        className="mt-2 flex items-center gap-2 rounded-note px-2 py-2 text-base font-medium opacity-90 hover:opacity-100"
        style={{ color: contentColor }}
      >
        <span className="text-xl leading-none">+</span>
        List item
      </button>

      {onConvertToText && items.length > 0 ? (
        <button
          type="button"
          onClick={onConvertToText}
          className="mt-1 px-2 py-2 text-sm font-medium opacity-75 hover:opacity-100"
          style={{ color: contentColor }}
        >
          Convert to text
        </button>
      ) : null}
    </div>
  );
}
