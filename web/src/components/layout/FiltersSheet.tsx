import { useId, useMemo, useState, type ReactNode } from 'react';
import { ResponsiveSheet } from '@/components/layout/ResponsiveSheet';
import { ColorSwatch } from '@/components/layout/ColorSwatch';
import { BookmarkIcon, CloseIcon, FilterIcon } from '@/components/icons/Icons';
import { Button } from '@/components/ui/Button';
import { CHROME_FOCUS } from '@/lib/ui/focusStyles';
import { NOTE_COLOR_NAMES, noteColorsForTheme } from '@/theme/colors';
import { useNotePaletteDark } from '@/theme/useNotePaletteDark';
import type { Label } from '@/types/label';
import type { NoteFilter, NoteQueryFilters } from '@/types/note';
import {
  DATE_FIELDS,
  DATE_FIELD_LABELS,
  DATE_PRESETS,
  DATE_PRESET_LABELS,
  NOTE_FLAGS,
  NOTE_FLAG_LABELS,
  colorSelected,
  datePresetOf,
  datePresetRange,
  hasActiveFilters,
  narrowingEqual,
  toggleColor,
  toggleValue,
} from '@/lib/query/noteQuery';
import {
  SAVED_FILTER_MAX,
  SAVED_FILTER_MAX_NAME_LENGTH,
  findSavedFilter,
  type SavedFilter,
} from '@/lib/query/savedFilters';

/** Native `scope_*`: Notes / Archive / Trash, matching the drawer. */
const SCOPES: Array<{ filter: NoteFilter; label: string }> = [
  { filter: 'active', label: 'Notes' },
  { filter: 'archived', label: 'Archive' },
  { filter: 'trashed', label: 'Trash' },
];

/** Past this many labels the section gets a search field (Kotlin `LABEL_SEARCH_THRESHOLD`). */
const LABEL_SEARCH_THRESHOLD = 8;

function Chip({
  label,
  selected,
  onClick,
  disabled = false,
}: {
  label: string;
  selected: boolean;
  onClick: () => void;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      disabled={disabled}
      onClick={onClick}
      className={`filter-chip shrink-0 px-3 text-xs ${CHROME_FOCUS} ${
        selected ? 'filter-chip-active' : 'filter-chip-inactive'
      } ${disabled ? 'cursor-default opacity-60' : 'cursor-pointer'}`}
    >
      <span className="whitespace-nowrap">{label}</span>
    </button>
  );
}

function Section({ title, action, children }: { title: string; action?: ReactNode; children: ReactNode }) {
  const headingId = useId();
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-2 border-t border-brand-outline/25 py-3 first:border-t-0">
      <div className="flex min-h-8 items-center gap-2">
        <h3 id={headingId} className="flex-1 text-section-label uppercase tracking-wider text-brand-muted">
          {title}
        </h3>
        {action}
      </div>
      <div className="flex flex-wrap gap-2">{children}</div>
    </section>
  );
}

export interface FiltersSheetProps {
  open: boolean;
  onClose: () => void;
  filters: NoteQueryFilters;
  onChange: (patch: Partial<NoteQueryFilters>) => void;
  onClearAll: () => void;
  labels: Label[];
  /** Notes the filters leave on screen, from the same matcher the list uses. */
  matchCount: number;
  savedFilters: SavedFilter[];
  onApplySaved: (filter: SavedFilter) => void;
  onDeleteSaved: (name: string) => void;
  onSave: (name: string) => void;
  /** Epoch millis that "today" is counted from. Tests pin it. */
  now?: number;
}

/**
 * Kotlin `FiltersSheet`: every narrowing dimension in one place — where, colours, labels with any
 * or all, status flags, a date window on a chosen field — plus saving what is on screen by name.
 *
 * Changes apply as they are made, as on native: the list behind the sheet is the preview, and the
 * button at the foot says how many notes it holds.
 */
export function FiltersSheet({
  open,
  onClose,
  filters,
  onChange,
  onClearAll,
  labels,
  matchCount,
  savedFilters,
  onApplySaved,
  onDeleteSaved,
  onSave,
  now = Date.now(),
}: FiltersSheetProps) {
  const isDark = useNotePaletteDark();
  const [labelSearch, setLabelSearch] = useState('');
  const [naming, setNaming] = useState(false);
  const [pendingName, setPendingName] = useState('');
  const nameInputId = useId();
  const nameHintId = useId();

  const colors = filters.colors ?? [];
  const labelNames = filters.labelNames ?? [];
  const labelMatch = filters.labelMatch ?? 'ANY';
  const flags = filters.flags ?? [];
  const dateField = filters.dateField ?? 'EDITED';
  const activePreset = datePresetOf(filters.dateRange, now);
  const active = hasActiveFilters(filters);

  // Labels chosen when the sheet opened lead, so a long list never hides what is narrowing it. Not
  // the live selection: re-sorting on every tap moved the next chip out from under the pointer.
  // The sheet is mounted per opening, so this initialiser runs once per open.
  const [ledBy] = useState(() => new Set(labelNames));
  const shownLabels = useMemo(() => {
    const needle = labelSearch.trim().toLowerCase();
    return labels
      .filter((label) => needle === '' || label.name.toLowerCase().includes(needle))
      .sort(
        (a, b) =>
          Number(ledBy.has(b.name)) - Number(ledBy.has(a.name)) ||
          a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }),
      );
  }, [labels, ledBy, labelSearch]);

  const trimmedName = pendingName.trim();
  const replacing = trimmedName ? findSavedFilter(savedFilters, trimmedName) : undefined;
  const nameHint = replacing
    ? `Replaces the filter already saved as “${replacing.name}”`
    : savedFilters.length >= SAVED_FILTER_MAX
      ? `${savedFilters.length} saved already. Saving replaces the oldest.`
      : '';

  const close = () => {
    setNaming(false);
    setLabelSearch('');
    onClose();
  };

  const submitName = () => {
    if (!trimmedName) return;
    onSave(trimmedName);
    setNaming(false);
    setPendingName('');
  };

  return (
    <ResponsiveSheet
      open={open}
      onClose={close}
      ariaLabel="Filters"
      maxWidthClass="md:max-w-xl"
    >
      <div className="flex items-center gap-2 px-5 pb-1 pt-1 sm:px-6">
        <h2 className="flex flex-1 items-center gap-2 text-base font-semibold text-brand-primary">
          <FilterIcon size={18} />
          Filters
        </h2>
        <button
          type="button"
          onClick={close}
          aria-label="Close filters"
          className={`tap-target flex size-9 items-center justify-center rounded-full text-brand-muted hover:bg-true-surface-variant hover:text-brand-primary ${CHROME_FOCUS}`}
        >
          <CloseIcon size={20} />
        </button>
      </div>

      <div className="px-5 sm:px-6">
        <Section title="Where">
          {SCOPES.map(({ filter, label }) => (
            <Chip
              key={filter}
              label={label}
              selected={filters.filter === filter}
              onClick={() => onChange({ filter })}
            />
          ))}
        </Section>

        <Section title="Colors">
          <div role="group" aria-label="Colors" className="flex flex-wrap items-center gap-3">
            {noteColorsForTheme(isDark).map((argb, index) => (
              <ColorSwatch
                key={argb}
                argb={argb}
                label={NOTE_COLOR_NAMES[index]}
                selected={colorSelected(colors, argb)}
                onClick={() => onChange({ colors: toggleColor(colors, argb) })}
              />
            ))}
          </div>
        </Section>

        {labels.length > 0 ? (
          <Section
            title="Labels"
            action={
              // Any/all only means something once two labels are chosen, so it appears then.
              labelNames.length > 1 ? (
                <Chip
                  label={labelMatch === 'ALL' ? 'All of these' : 'Any of these'}
                  selected={labelMatch === 'ALL'}
                  onClick={() => onChange({ labelMatch: labelMatch === 'ALL' ? 'ANY' : 'ALL' })}
                />
              ) : null
            }
          >
            {labels.length > LABEL_SEARCH_THRESHOLD ? (
              <input
                type="search"
                value={labelSearch}
                onChange={(event) => setLabelSearch(event.target.value)}
                placeholder="Search labels"
                aria-label="Search labels"
                className={`mb-1 w-full rounded-note border border-brand-outline/40 bg-true-surface-variant/40 px-3 py-2 text-sm text-brand-primary placeholder:text-brand-muted ${CHROME_FOCUS}`}
              />
            ) : null}
            {shownLabels.map((label) => (
              <Chip
                key={label.name}
                label={label.name}
                selected={labelNames.includes(label.name)}
                onClick={() => onChange({ labelNames: toggleValue(labelNames, label.name) })}
              />
            ))}
          </Section>
        ) : null}

        <Section title="Status">
          {NOTE_FLAGS.map((flag) => (
            <Chip
              key={flag}
              label={NOTE_FLAG_LABELS[flag]}
              selected={flags.includes(flag)}
              onClick={() => onChange({ flags: toggleValue(flags, flag) })}
            />
          ))}
        </Section>

        <Section title="Date">
          <div role="group" aria-label="Date field" className="flex w-full flex-wrap gap-2">
            {DATE_FIELDS.map((field) => (
              <Chip
                key={field}
                label={DATE_FIELD_LABELS[field]}
                selected={dateField === field}
                onClick={() => onChange({ dateField: field })}
              />
            ))}
          </div>
          <div role="group" aria-label="Date range" className="flex w-full flex-wrap gap-2">
            {DATE_PRESETS.map((preset) => (
              <Chip
                key={preset}
                label={DATE_PRESET_LABELS[preset]}
                selected={activePreset === preset}
                onClick={() => onChange({ dateRange: datePresetRange(preset, now) })}
              />
            ))}
          </div>
        </Section>

        {/* Saved filters live here as well as in the drawer: this is where they are made, and
            something you can create where you cannot delete it is a one-way door. */}
        {savedFilters.length > 0 ? (
          <section aria-label="Saved filters" className="flex flex-col gap-1 border-t border-brand-outline/25 py-3">
            <h3 className="text-section-label uppercase tracking-wider text-brand-muted">Saved</h3>
            <ul className="flex flex-col gap-1">
              {savedFilters.map((saved) => (
                <SavedFilterRow
                  key={saved.name}
                  filter={saved}
                  selected={narrowingEqual(saved.query, filters)}
                  onApply={() => onApplySaved(saved)}
                  onDelete={() => onDeleteSaved(saved.name)}
                />
              ))}
            </ul>
          </section>
        ) : null}

        {naming ? (
          <form
            className="flex flex-col gap-2 border-t border-brand-outline/25 py-3"
            onSubmit={(event) => {
              event.preventDefault();
              submitName();
            }}
          >
            <label htmlFor={nameInputId} className="text-sm font-semibold text-brand-primary">
              Name this filter
            </label>
            <input
              id={nameInputId}
              autoFocus
              value={pendingName}
              maxLength={SAVED_FILTER_MAX_NAME_LENGTH}
              onChange={(event) => setPendingName(event.target.value.slice(0, SAVED_FILTER_MAX_NAME_LENGTH))}
              onKeyDown={(event) => {
                // Escape here cancels the naming, not the whole sheet: stopping it at React's root
                // keeps it from the focus trap's document listener. (Toggling the sheet's
                // closeOnEscape instead re-ran the trap and threw focus back to its first control.)
                if (event.key === 'Escape') {
                  event.stopPropagation();
                  setNaming(false);
                }
              }}
              placeholder="e.g. Invoices this month"
              aria-describedby={nameHint ? nameHintId : undefined}
              className={`w-full rounded-note border border-brand-outline/40 bg-true-surface-variant/40 px-3 py-2 text-sm text-brand-primary placeholder:text-brand-muted ${CHROME_FOCUS}`}
            />
            {nameHint ? (
              <p id={nameHintId} className="text-xs text-brand-muted">
                {nameHint}
              </p>
            ) : null}
            <div className="flex justify-end gap-2">
              <Button type="button" variant="ghost" size="sm" onClick={() => setNaming(false)}>
                Cancel
              </Button>
              {/* An unnamed filter is not saveable: a Save that does nothing is worse than one that cannot be pressed yet. */}
              <Button type="submit" variant="primary" size="sm" disabled={!trimmedName}>
                Save
              </Button>
            </div>
          </form>
        ) : null}
      </div>

      <div className="flex flex-wrap items-center gap-2 border-t border-brand-outline/25 px-5 pb-safe-action pt-3 sm:px-6">
        <Button
          variant="ghost"
          size="sm"
          // Nothing to save when nothing narrows: an unfiltered list is not a filter.
          disabled={!active || naming}
          leadingIcon={<BookmarkIcon size={16} />}
          onClick={() => {
            setPendingName('');
            setNaming(true);
          }}
        >
          Save these filters
        </Button>
        <Button variant="ghost" size="sm" disabled={!active} onClick={onClearAll}>
          Clear all
        </Button>
        <span className="flex-1" />
        <Button variant="primary" size="sm" onClick={close}>
          {matchCount === 1 ? 'Show 1 note' : `Show ${matchCount} notes`}
        </Button>
      </div>
    </ResponsiveSheet>
  );
}

function SavedFilterRow({
  filter,
  selected,
  onApply,
  onDelete,
}: {
  filter: SavedFilter;
  selected: boolean;
  onApply: () => void;
  onDelete: () => void;
}) {
  return (
    <li className="flex items-center gap-1">
      <button
        type="button"
        onClick={onApply}
        aria-current={selected ? 'true' : undefined}
        className={`flex min-h-11 min-w-0 flex-1 items-center gap-3 rounded-note px-3 text-left text-sm ${CHROME_FOCUS} ${
          selected ? 'bg-brand-primary/[0.12] font-semibold text-brand-primary' : 'text-brand-secondary hover:bg-true-surface-variant/60'
        }`}
      >
        <BookmarkIcon size={18} className="shrink-0 text-brand-muted" />
        <span className="truncate">{filter.name}</span>
      </button>
      <button
        type="button"
        onClick={onDelete}
        aria-label={`Delete saved filter ${filter.name}`}
        title={`Delete saved filter ${filter.name}`}
        className={`tap-target flex size-9 shrink-0 items-center justify-center rounded-full text-brand-muted hover:bg-true-surface-variant hover:text-brand-primary ${CHROME_FOCUS}`}
      >
        <CloseIcon size={18} />
      </button>
    </li>
  );
}
