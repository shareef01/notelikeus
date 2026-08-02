import { BlockIcon, CheckIcon } from '@/components/icons/Icons';
import { argbToCss, noteColorsForTheme, noteColorsMatch } from '@/theme/colors';
import { contentColorForBackground } from '@/theme/contrast';
import { useNotePaletteDark } from '@/theme/useNotePaletteDark';

interface ColorSwatchProps {
  argb: number;
  selected: boolean;
  onClick: () => void;
  label?: string;
}

export function ColorSwatch({ argb, selected, onClick, label }: ColorSwatchProps) {
  const isDefault = argb === 0;
  const checkColor = isDefault ? undefined : contentColorForBackground(argb);

  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label ?? (isDefault ? 'No color' : 'Note color')}
      aria-pressed={selected}
      className={`relative flex size-[26px] shrink-0 items-center justify-center rounded-full transition-[box-shadow,transform] ${
        selected
          ? 'scale-105 ring-2 ring-brand-primary ring-offset-2 ring-offset-true-surface'
          : 'hover:scale-105 hover:ring-1 hover:ring-brand-outline/50 hover:ring-offset-1 hover:ring-offset-true-surface'
      }`}
      style={{ backgroundColor: isDefault ? 'rgb(var(--surface-variant-rgb))' : argbToCss(argb) }}
    >
      {isDefault && !selected ? <BlockIcon size={11} className="text-brand-muted/55" /> : null}
      {selected ? (
        <span
          className={isDefault ? 'text-brand-primary' : undefined}
          style={checkColor ? { color: checkColor } : undefined}
        >
          <CheckIcon size={11} />
        </span>
      ) : null}
    </button>
  );
}

interface ColorSwatchRowProps {
  selectedColor: number | null;
  onSelect: (color: number | null) => void;
  /** When true, highlight the “all colors” control instead of a specific swatch. */
  allSelected?: boolean;
  onSelectAll?: () => void;
}

export function ColorSwatchRow({
  selectedColor,
  onSelect,
  allSelected = false,
  onSelectAll,
}: ColorSwatchRowProps) {
  const isDark = useNotePaletteDark();
  const colors = noteColorsForTheme(isDark).filter((argb) => !(onSelectAll && argb === 0));

  return (
    <div className="flex items-center gap-1.5">
      {onSelectAll ? (
        <>
          <button
            type="button"
            onClick={onSelectAll}
            aria-label="All colors"
            aria-pressed={allSelected}
            className={`shrink-0 rounded-full px-2.5 py-1 text-overline uppercase transition-colors ${
              allSelected
                ? 'bg-brand-primary/15 text-brand-primary'
                : 'text-brand-muted hover:bg-brand-primary/[0.04] hover:text-brand-secondary'
            }`}
          >
            All
          </button>
          <span className="mx-0.5 h-4 w-px shrink-0 bg-brand-outline/45" aria-hidden />
        </>
      ) : null}
      <div className="flex items-center gap-1.5 pr-0.5">
        {colors.map((argb) => (
          <ColorSwatch
            key={argb}
            argb={argb}
            selected={
              !allSelected &&
              selectedColor != null &&
              noteColorsMatch(selectedColor, argb)
            }
            onClick={() =>
              onSelect(
                selectedColor != null && noteColorsMatch(selectedColor, argb)
                  ? null
                  : argb,
              )
            }
          />
        ))}
      </div>
    </div>
  );
}
