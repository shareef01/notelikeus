import { BoldIcon, BulletListIcon, ChecklistIcon, ItalicIcon, LinkIcon } from '@/components/icons/Icons';
import type { MouseEventHandler } from 'react';

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
    'flex size-9 items-center justify-center rounded-full transition-colors hover:bg-black/12 active:bg-black/18 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-current';

  // Keep the textarea focused so selection isn't lost before formatting runs.
  const keepEditorFocus: MouseEventHandler<HTMLButtonElement> = (event) => {
    event.preventDefault();
  };

  return (
    <div
      className="sticky top-0 z-10 mt-4 inline-flex max-w-full flex-wrap items-center gap-0.5 rounded-full border border-black/10 bg-black/10 p-1 shadow-sm backdrop-blur-md supports-[backdrop-filter]:bg-black/[0.07]"
      style={{ color: contentColor }}
      role="toolbar"
      aria-label="Text formatting"
    >
      <button
        type="button"
        className={buttonClass}
        onMouseDown={keepEditorFocus}
        onClick={onBold}
        aria-label="Bold"
        title="Bold"
      >
        <BoldIcon size={18} />
      </button>
      <button
        type="button"
        className={buttonClass}
        onMouseDown={keepEditorFocus}
        onClick={onItalic}
        aria-label="Italic"
        title="Italic"
      >
        <ItalicIcon size={18} />
      </button>
      <button
        type="button"
        className={buttonClass}
        onMouseDown={keepEditorFocus}
        onClick={onLink}
        aria-label="Link"
        title="Link"
      >
        <LinkIcon size={18} />
      </button>
      <span className="mx-0.5 h-5 w-px shrink-0 bg-current opacity-20" aria-hidden />
      <button
        type="button"
        className={buttonClass}
        onMouseDown={keepEditorFocus}
        onClick={onBullet}
        aria-label="Bullet list"
        title="Bullet list"
      >
        <BulletListIcon size={18} />
      </button>
      <button
        type="button"
        className={buttonClass}
        onMouseDown={keepEditorFocus}
        onClick={onChecklist}
        aria-label="Checklist"
        title="Checklist"
      >
        <ChecklistIcon size={18} />
      </button>
    </div>
  );
}
