import { ChecklistIcon, LinkIcon } from '@/components/icons/Icons';

interface RichTextToolbarProps {
  contentColor: string;
  onBold: () => void;
  onItalic: () => void;
  onBullet: () => void;
  onChecklist: () => void;
  onLink: () => void;
}

export function RichTextToolbar({
  contentColor,
  onBold,
  onItalic,
  onBullet,
  onChecklist,
  onLink,
}: RichTextToolbarProps) {
  const buttonClass =
    'flex size-9 items-center justify-center rounded-full text-sm font-semibold hover:bg-black/10';

  return (
    <div
      className="mt-3 flex flex-wrap items-center gap-1 rounded-note border border-black/10 bg-black/8 p-1 shadow-sm"
      style={{ color: contentColor }}
      role="toolbar"
      aria-label="Text formatting"
    >
      <button type="button" className={buttonClass} onClick={onBold} aria-label="Bold">
        B
      </button>
      <button type="button" className={`${buttonClass} italic`} onClick={onItalic} aria-label="Italic">
        I
      </button>
      <button type="button" className={buttonClass} onClick={onLink} aria-label="Link">
        <LinkIcon size={18} />
      </button>
      <span className="mx-1 h-6 w-px bg-current opacity-20" aria-hidden />
      <button type="button" className={buttonClass} onClick={onBullet} aria-label="Bullet list">
        •
      </button>
      <button type="button" className={buttonClass} onClick={onChecklist} aria-label="Checklist">
        <ChecklistIcon size={18} />
      </button>
    </div>
  );
}
