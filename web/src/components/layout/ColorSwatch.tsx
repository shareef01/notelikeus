import { useIsDarkTheme } from '@/hooks/useIsDarkTheme';
import { argbToCss, noteColorsForTheme } from '@/theme/colors';

interface ColorSwatchProps {
  argb: number;
  selected: boolean;
  onClick: () => void;
  label?: string;
  compact?: boolean;
}

export function ColorSwatch({ argb, selected, onClick, label, compact = false }: ColorSwatchProps) {
  const isDefault = argb === 0;
  const sizeClass = compact ? 'size-8' : 'size-11';
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label ?? (isDefault ? 'Default note color' : 'Note color')}
      aria-pressed={selected}
      className={`relative flex ${sizeClass} shrink-0 items-center justify-center rounded-full border-2 transition-transform ${
        selected ? 'scale-105 border-brand-primary' : 'border-brand-outline/40 hover:scale-105'
      }`}
      style={{ backgroundColor: isDefault ? 'var(--surface-variant)' : argbToCss(argb) }}
    >
      {isDefault ? (
        <span className={`font-bold uppercase tracking-wide text-brand-muted/50 ${compact ? 'text-[8px]' : 'text-[10px]'}`}>
          Def
        </span>
      ) : null}
      {selected && !isDefault ? <span className="text-xs font-bold text-brand-primary">✓</span> : null}
    </button>
  );
}

interface ColorSwatchRowProps {
  selectedColor: number | null;
  onSelect: (color: number | null) => void;
  compact?: boolean;
}

export function ColorSwatchRow({ selectedColor, onSelect, compact = false }: ColorSwatchRowProps) {
  const isDark = useIsDarkTheme();
  const colors = noteColorsForTheme(isDark);
  return (
    <>
      {colors.map((argb) => (
        <ColorSwatch
          key={argb}
          argb={argb}
          compact={compact}
          selected={selectedColor === argb}
          onClick={() => onSelect(selectedColor === argb ? null : argb)}
        />
      ))}
    </>
  );
}
