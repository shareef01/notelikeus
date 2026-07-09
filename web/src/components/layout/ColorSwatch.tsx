import { argbToCss, noteColorsForTheme } from '@/theme/colors';

interface ColorSwatchProps {
  argb: number;
  selected: boolean;
  onClick: () => void;
  label?: string;
}

export function ColorSwatch({ argb, selected, onClick, label }: ColorSwatchProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label ?? 'Note color'}
      aria-pressed={selected}
      className={`size-7 shrink-0 rounded-full border-2 transition-transform ${
        selected ? 'scale-110 border-brand-primary' : 'border-transparent hover:scale-105'
      }`}
      style={{ backgroundColor: argbToCss(argb) }}
    />
  );
}

interface ColorSwatchRowProps {
  selectedColor: number | null;
  onSelect: (color: number | null) => void;
}

export function ColorSwatchRow({ selectedColor, onSelect }: ColorSwatchRowProps) {
  const colors = noteColorsForTheme(true);
  return (
    <>
      {colors.map((argb) => (
        <ColorSwatch
          key={argb}
          argb={argb}
          selected={selectedColor === argb}
          onClick={() => onSelect(selectedColor === argb ? null : argb)}
        />
      ))}
    </>
  );
}
