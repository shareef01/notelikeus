import { CheckIcon } from '@/components/icons/Icons';
import type { AccentColor, ThemeBase, ThemePreference } from '@/store/settingsStore';

export const BASE_ORDER: ThemeBase[] = ['system', 'light', 'dark'];
export const ACCENT_ORDER: AccentColor[] = ['neutral', 'blue', 'green'];

type SwatchMeta = {
  label: string;
  /** Main fill — the surface colour this option produces. */
  surface: string;
  /** The primary hue, shown as a dot. */
  accent: string;
  /** Second half, for the System split. */
  surfaceAlt?: string;
  /** Dark ink on a light surface. */
  lightCheck?: boolean;
};

const BASE_META: Record<ThemeBase, SwatchMeta> = {
  system: {
    label: 'System',
    surface: '#f7f7f7',
    surfaceAlt: '#1c1c1c',
    accent: '#8b8b8b',
    lightCheck: true,
  },
  light: { label: 'Light', surface: '#ffffff', accent: '#111111', lightCheck: true },
  dark: { label: 'Dark', surface: '#1c1c1c', accent: '#f5f5f5' },
};

const ACCENT_META: Record<AccentColor, SwatchMeta> = {
  neutral: { label: 'Neutral', surface: '#ffffff', accent: '#111111', lightCheck: true },
  blue: { label: 'Blue', surface: '#ffffff', accent: '#0b57d0', lightCheck: true },
  green: { label: 'Green', surface: '#ffffff', accent: '#1b6b2e', lightCheck: true },
};

function Swatch({ meta, selected }: { meta: SwatchMeta; selected: boolean }) {
  return (
    <span
      className={`relative flex size-10 items-center justify-center overflow-hidden rounded-full sm:size-11 ${
        selected
          ? 'ring-2 ring-brand-primary ring-offset-2 ring-offset-true-surface'
          : 'ring-1 ring-brand-outline/45'
      }`}
    >
      {meta.surfaceAlt ? (
        <>
          <span
            className="absolute inset-0"
            style={{
              background: `linear-gradient(120deg, ${meta.surface} 49.5%, ${meta.surfaceAlt} 50.5%)`,
            }}
          />
          <span
            className="absolute left-[28%] top-[30%] size-1.5 rounded-full shadow-sm"
            style={{ backgroundColor: '#111111' }}
            aria-hidden
          />
          <span
            className="absolute right-[28%] bottom-[30%] size-1.5 rounded-full shadow-sm"
            style={{ backgroundColor: '#f5f5f5' }}
            aria-hidden
          />
        </>
      ) : (
        <>
          <span className="absolute inset-0" style={{ backgroundColor: meta.surface }} />
          <span
            className="absolute bottom-[22%] left-1/2 size-2 -translate-x-1/2 rounded-full shadow-sm"
            style={{ backgroundColor: meta.accent }}
            aria-hidden
          />
        </>
      )}

      {selected ? (
        <span
          className={`relative z-10 flex size-5 items-center justify-center rounded-full shadow-md ${
            meta.lightCheck ? 'bg-neutral-900 text-white' : 'bg-white text-neutral-900'
          }`}
          aria-hidden
        >
          <CheckIcon size={11} />
        </span>
      ) : null}
    </span>
  );
}

function SwatchRow<T extends string>({
  label,
  order,
  meta,
  value,
  onChange,
}: {
  label: string;
  order: T[];
  meta: Record<T, SwatchMeta>;
  value: T;
  onChange: (next: T) => void;
}) {
  return (
    <div>
      <p className="px-1 pb-2 text-[11px] font-semibold uppercase tracking-wide text-brand-muted">
        {label}
      </p>
      <div className="grid grid-cols-3 gap-x-3 gap-y-4 sm:gap-x-4" role="radiogroup" aria-label={label}>
        {order.map((option) => {
          const optionMeta = meta[option];
          const selected = value === option;
          return (
            <button
              key={option}
              type="button"
              role="radio"
              aria-checked={selected}
              aria-label={optionMeta.label}
              onClick={() => onChange(option)}
              className={`flex flex-col items-center gap-2 rounded-xl px-1 py-1.5 outline-none transition-colors focus-visible:ring-2 focus-visible:ring-brand-primary/50 ${
                selected ? 'bg-brand-primary/[0.06]' : 'hover:bg-brand-primary/[0.03]'
              }`}
            >
              <Swatch meta={optionMeta} selected={selected} />
              <span
                className={`text-center text-[11px] leading-none tracking-tight sm:text-xs ${
                  selected ? 'font-semibold text-brand-primary' : 'font-medium text-brand-muted'
                }`}
              >
                {optionMeta.label}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}

interface ThemePickerProps {
  value: ThemePreference;
  onBaseChange: (base: ThemeBase) => void;
  onAccentChange: (accent: AccentColor) => void;
  onAmoledChange: (amoled: boolean) => void;
}

/**
 * Appearance as three independent settings, matching the Kotlin clients.
 *
 * It used to be one row of six fused themes, where "OLED", "Midnight" and "Forest" each bundled a
 * black level with a hue — so a blue theme could never also be pure black. Splitting them makes
 * every combination reachable, and makes the two clients agree on what appearance even is.
 */
export function ThemePicker({
  value,
  onBaseChange,
  onAccentChange,
  onAmoledChange,
}: ThemePickerProps) {
  // Pure black is a dark-theme idea, and on System it depends on what the OS is doing right now.
  const amoledApplies = value.base !== 'light';

  return (
    <div className="flex flex-col gap-5 px-4 py-4 sm:px-5 sm:py-5">
      <SwatchRow
        label="Theme"
        order={BASE_ORDER}
        meta={BASE_META}
        value={value.base}
        onChange={onBaseChange}
      />
      <SwatchRow
        label="Accent"
        order={ACCENT_ORDER}
        meta={ACCENT_META}
        value={value.accent}
        onChange={onAccentChange}
      />
      <label
        className={`flex items-center justify-between gap-4 rounded-xl px-1 py-1.5 ${
          amoledApplies ? '' : 'opacity-45'
        }`}
      >
        <span>
          <span className="block text-sm font-medium text-brand-primary">Pure black</span>
          <span className="block text-xs text-brand-muted">
            True black backgrounds on dark themes. Saves power on OLED screens.
          </span>
        </span>
        <input
          type="checkbox"
          className="size-5 shrink-0 accent-brand-primary"
          checked={value.amoled}
          disabled={!amoledApplies}
          onChange={(event) => onAmoledChange(event.target.checked)}
        />
      </label>
    </div>
  );
}
