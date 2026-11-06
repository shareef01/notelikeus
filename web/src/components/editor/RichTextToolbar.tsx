import { BoldIcon, BulletListIcon, ChecklistIcon, ImageIcon, ItalicIcon, LinkIcon } from '@/components/icons/Icons';
import { type ReactNode, type SyntheticEvent, useRef, useState, useCallback } from 'react';

interface RichTextToolbarProps {
  contentColor: string;
  backgroundColor?: string;
  active?: { bold: boolean; italic: boolean; bullet: boolean; link: boolean };
  onBold: () => void;
  onItalic: () => void;
  onBullet: () => void;
  onChecklist: () => void;
  onLink: () => void;
  onAddImage?: () => void;
}

export function RichTextToolbar({
  contentColor,
  backgroundColor,
  active,
  onBold,
  onItalic,
  onBullet,
  onChecklist,
  onLink,
  onAddImage,
}: RichTextToolbarProps) {
  const [focusedIndex, setFocusedIndex] = useState(0);
  const containerRef = useRef<HTMLDivElement>(null);

  const buttonClass =
    'flex size-10 items-center justify-center rounded-xl transition-colors hover:bg-[color-mix(in_srgb,currentColor_12%,transparent)] active:bg-[color-mix(in_srgb,currentColor_18%,transparent)] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-current';

  // Click supports pointer, keyboard, and assistive activation. Preventing pointer focus
  // keeps the editor's selection and the on-screen keyboard in place during formatting.
  const keepBodyFocus = (event: SyntheticEvent) => event.preventDefault();

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (!containerRef.current) return;
    const buttons = Array.from(
      containerRef.current.querySelectorAll<HTMLButtonElement>('button')
    );
    if (buttons.length === 0) return;

    const activeIdx = buttons.findIndex((btn) => btn === document.activeElement);
    const currentIdx = activeIdx !== -1 ? activeIdx : focusedIndex;
    let nextIdx = -1;

    switch (e.key) {
      case 'ArrowRight':
      case 'ArrowDown':
        e.preventDefault();
        nextIdx = (currentIdx + 1) % buttons.length;
        break;
      case 'ArrowLeft':
      case 'ArrowUp':
        e.preventDefault();
        nextIdx = (currentIdx - 1 + buttons.length) % buttons.length;
        break;
      case 'Home':
        e.preventDefault();
        nextIdx = 0;
        break;
      case 'End':
        e.preventDefault();
        nextIdx = buttons.length - 1;
        break;
      default:
        return;
    }

    if (nextIdx !== -1 && buttons[nextIdx]) {
      setFocusedIndex(nextIdx);
      buttons[nextIdx].focus();
    }
  }, []);

  const formatButton = (
    index: number,
    label: string,
    icon: ReactNode,
    action: () => void,
    shortcut?: string,
    pressed?: boolean
  ) => (
    <button
      type="button"
      className={buttonClass}
      tabIndex={focusedIndex === index ? 0 : -1}
      onFocus={() => setFocusedIndex(index)}
      onPointerDown={keepBodyFocus}
      onMouseDown={keepBodyFocus}
      onClick={action}
      aria-label={label}
      aria-pressed={pressed}
      style={pressed ? { background: 'color-mix(in srgb, currentColor 16%, transparent)' } : undefined}
      aria-keyshortcuts={shortcut}
      title={shortcut ? `${label} (${shortcut.replace('Control', 'Ctrl')})` : label}
    >
      {icon}
    </button>
  );

  return (
    <div
      ref={containerRef}
      onKeyDown={handleKeyDown}
      className="sticky top-0 z-10 mt-5 inline-flex self-start max-w-full flex-wrap items-center gap-0.5 rounded-xl border border-brand-outline/50 p-1"
      style={{ color: contentColor, backgroundColor }}
      role="toolbar"
      aria-label="Text formatting"
    >
      {formatButton(0, 'Bold', <BoldIcon size={18} />, onBold, 'Control+B', active?.bold)}
      {formatButton(1, 'Italic', <ItalicIcon size={18} />, onItalic, 'Control+I', active?.italic)}
      {formatButton(2, 'Link', <LinkIcon size={18} />, onLink, undefined, active?.link)}
      <span className="mx-0.5 h-4 w-px shrink-0 bg-current opacity-20" aria-hidden />
      {formatButton(3, 'Bullet list', <BulletListIcon size={18} />, onBullet, undefined, active?.bullet)}
      {formatButton(4, 'Checklist', <ChecklistIcon size={18} />, onChecklist)}
      {onAddImage ? (
        <>
          <span className="mx-0.5 h-4 w-px shrink-0 bg-current opacity-20" aria-hidden />
          {formatButton(5, 'Add image', <ImageIcon size={18} />, onAddImage)}
        </>
      ) : null}
    </div>
  );
}
