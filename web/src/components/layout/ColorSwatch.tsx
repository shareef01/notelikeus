import { BlockIcon, CheckIcon } from '@/components/icons/Icons';
import { argbToCss, noteColorsForTheme } from '@/theme/colors';
import { contentColorForBackground } from '@/theme/contrast';
import { useSettingsStore, type AppTheme } from '@/store/settingsStore';
import { useEffect, useState } from 'react';

function isDarkForTheme(appTheme: AppTheme): boolean {
  if (appTheme === 'light') return false;
  if (appTheme === 'auto') {
    return window.matchMedia('(prefers-color-scheme: dark)').matches;
  }
  return true;
}

function useNotePaletteDark(): boolean {
  const appTheme = useSettingsStore((s) => s.appTheme);
  const [isDark, setIsDark] = useState(() => isDarkForTheme(appTheme));

  useEffect(() => {
    const sync = () => setIsDark(isDarkForTheme(appTheme));
    sync();
    if (appTheme !== 'auto') return;
    const media = window.matchMedia('(prefers-color-scheme: dark)');
    media.addEventListener('change', sync);
    return () => media.removeEventListener('change', sync);
  }, [appTheme]);

  return isDark;
}

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
      className={`relative flex size-8 shrink-0 items-center justify-center rounded-full border-2 transition-[transform,box-shadow,border-color] ${
        selected
          ? 'scale-110 border-brand-primary shadow-[0_0_0_2px_rgb(var(--primary-rgb)/0.2)]'
          : 'border-brand-outline/55 hover:scale-105 hover:border-brand-outline'
      }`}
      style={{ backgroundColor: isDefault ? 'rgb(var(--surface-variant-rgb))' : argbToCss(argb) }}
    >
      {isDefault && !selected ? <BlockIcon size={14} className="text-brand-muted/55" /> : null}
      {selected ? (
        <span
          className={isDefault ? 'text-brand-primary' : undefined}
          style={checkColor ? { color: checkColor } : undefined}
        >
          <CheckIcon size={14} />
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
        <button
          type="button"
          onClick={onSelectAll}
          aria-label="All colors"
          aria-pressed={allSelected}
          className={`relative flex size-8 shrink-0 items-center justify-center rounded-full border-2 text-[10px] font-bold uppercase tracking-wide transition-[transform,border-color] ${
            allSelected
              ? 'scale-110 border-brand-primary bg-brand-primary text-true-surface shadow-[0_0_0_2px_rgb(var(--primary-rgb)/0.2)]'
              : 'border-brand-outline/60 bg-true-surface text-brand-muted hover:border-brand-outline hover:text-brand-primary'
          }`}
        >
          All
        </button>
      ) : null}
      {colors.map((argb) => (
        <ColorSwatch
          key={argb}
          argb={argb}
          selected={!allSelected && selectedColor === argb}
          onClick={() => onSelect(selectedColor === argb ? null : argb)}
        />
      ))}
    </div>
  );
}
