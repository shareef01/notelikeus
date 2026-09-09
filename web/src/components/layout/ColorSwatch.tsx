import { BlockIcon, CheckIcon, PaletteIcon } from '@/components/icons/Icons';
import { argbToCss, NOTE_COLOR_NAMES, noteColorsForTheme, noteColorsMatch } from '@/theme/colors';
import { contentColorForBackground } from '@/theme/contrast';
import { useNotePaletteDark } from '@/theme/useNotePaletteDark';
import { CHROME_FOCUS } from '@/lib/ui/focusStyles';

const SWATCH_BASE =
  'relative flex size-7 shrink-0 items-center justify-center rounded-full border transition-[box-shadow,transform,border-color]';
const SWATCH_SELECTED =
  'scale-105 border-brand-primary shadow-[0_0_0_2px_rgb(var(--surface-rgb)),0_0_0_4px_rgb(var(--primary-rgb))]';
const SWATCH_IDLE = 'border-black/15 hover:scale-105 dark:border-white/15';

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
      className={`${SWATCH_BASE} ${CHROME_FOCUS} ${selected ? SWATCH_SELECTED : SWATCH_IDLE}`}
      style={{ backgroundColor: isDefault ? 'rgb(var(--surface-variant-rgb))' : argbToCss(argb) }}
    >
      {isDefault && !selected ? <BlockIcon size={11} className="text-brand-muted/55" /> : null}
      {selected ? (
        <span
          className={isDefault ? 'text-brand-primary' : undefined}
          style={checkColor ? { color: checkColor } : undefined}
        >
          <CheckIcon size={12} />
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
  /** Allow the palette to wrap in constrained sheet layouts. */
  wrap?: boolean;
}

export function ColorSwatchRow({
  selectedColor,
  onSelect,
  allSelected = false,
  onSelectAll,
  wrap = false,
}: ColorSwatchRowProps) {
  const isDark = useNotePaletteDark();
  const colors = noteColorsForTheme(isDark).filter((argb) => !(onSelectAll && argb === 0));

  return (
    <div className={`flex items-center gap-2 ${wrap ? 'flex-wrap' : ''}`}>
      {onSelectAll ? (
        <button
          type="button"
          onClick={onSelectAll}
          aria-label="All colors"
          aria-pressed={allSelected}
          title="All colors"
          className={`${SWATCH_BASE} bg-true-surface-variant/40 text-brand-muted ${CHROME_FOCUS} ${
            allSelected ? `${SWATCH_SELECTED} text-brand-primary` : SWATCH_IDLE
          }`}
        >
          {allSelected ? <CheckIcon size={12} /> : <PaletteIcon size={14} />}
        </button>
      ) : null}
      {colors.map((argb, index) => {
        const nameIndex = onSelectAll ? index + 1 : index;
        return (
          <ColorSwatch
            key={argb}
            argb={argb}
            label={NOTE_COLOR_NAMES[nameIndex] ?? undefined}
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
        );
      })}
    </div>
  );
}
