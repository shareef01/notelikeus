/**
 * The search box's operators, ported from Kotlin `domain/query/NoteQueryParser.kt` (NL-04).
 *
 * `label:work` `color:green` `is:pinned` `has:reminder` `before:2026-08-01` `after:yesterday`
 * `in:trash`
 *
 * Web used to search for these as literal text, so a query learned on Android found nothing here
 * and said nothing about why. Both parsers read the same golden vectors
 * (`contracts/query/parser-vectors.json`), so the syntax cannot drift apart again unnoticed.
 *
 * Like the Kotlin parser this only reads: label and colour come back as names, and resolving them
 * against the user's labels and the palette is the caller's job. Anything not recognised as an
 * operator stays free text, so `TODO: fix` or `ratio: 3:1` still searches for what was typed.
 */

/** Same names as Kotlin `NoteFlag`, so the shared vectors can name them. */
export type NoteFlag =
  | 'PINNED'
  | 'HAS_REMINDER'
  | 'REMINDER_OVERDUE'
  | 'HAS_CHECKLIST'
  | 'HAS_UNCHECKED_ITEMS'
  | 'HAS_LINKS'
  | 'UNTITLED'
  | 'UNLABELED';

/** Same names as Kotlin `NoteScope`. */
export type NoteScope = 'ACTIVE' | 'ARCHIVE' | 'TRASH' | 'ALL';

/** Same names as Kotlin `DateField`. */
export type DateField = 'CREATED' | 'EDITED' | 'REMINDER';

export interface ParsedQuery {
  text: string;
  labelNames: string[];
  colorNames: string[];
  flags: NoteFlag[];
  scope: NoteScope | null;
  dateField: DateField | null;
  /** Exclusive upper bound, epoch millis. */
  before: number | null;
  /** Inclusive lower bound, epoch millis. */
  after: number | null;
  /** Operator-shaped tokens that were not understood, so the UI can say so. */
  unknown: string[];
}

/** Epoch millis at local midnight, [daysFromToday] days from today. */
export type DayStart = (daysFromToday: number) => number;
/** Epoch millis at local midnight of a civil date, or null if that date does not exist. */
export type DayStartOfDate = (year: number, month: number, day: number) => number | null;

const SCOPE_WORDS: Record<string, NoteScope> = {
  archived: 'ARCHIVE',
  archive: 'ARCHIVE',
  trashed: 'TRASH',
  trash: 'TRASH',
  deleted: 'TRASH',
};

const IS_FLAGS: Record<string, NoteFlag> = {
  pinned: 'PINNED',
  untitled: 'UNTITLED',
  unlabeled: 'UNLABELED',
  unlabelled: 'UNLABELED',
  overdue: 'REMINDER_OVERDUE',
};

const HAS_FLAGS: Record<string, NoteFlag> = {
  reminder: 'HAS_REMINDER',
  reminders: 'HAS_REMINDER',
  checklist: 'HAS_CHECKLIST',
  unchecked: 'HAS_UNCHECKED_ITEMS',
  link: 'HAS_LINKS',
  links: 'HAS_LINKS',
};

const IN_SCOPES: Record<string, NoteScope> = {
  trash: 'TRASH',
  archive: 'ARCHIVE',
  all: 'ALL',
  notes: 'ACTIVE',
  active: 'ACTIVE',
};

const KNOWN_PREFIXES = new Set(['label', 'color', 'colour', 'is', 'has', 'before', 'after', 'in']);

const own = <T>(map: Record<string, T>, key: string): T | undefined =>
  Object.prototype.hasOwnProperty.call(map, key) ? map[key] : undefined;

export function emptyParsedQuery(): ParsedQuery {
  return {
    text: '',
    labelNames: [],
    colorNames: [],
    flags: [],
    scope: null,
    dateField: null,
    before: null,
    after: null,
    unknown: [],
  };
}

export function parseNoteQuery(
  input: string,
  dayStart: DayStart,
  dayStartOfDate: DayStartOfDate,
): ParsedQuery {
  const result = emptyParsedQuery();
  const freeText: string[] = [];

  for (const token of tokenize(input)) {
    const colon = token.indexOf(':');
    const prefix = colon > 0 ? token.slice(0, colon).toLowerCase() : null;
    if (prefix == null || !KNOWN_PREFIXES.has(prefix)) {
      freeText.push(token);
      continue;
    }
    const value = unquote(token.slice(colon + 1)).trim();
    // `label:` with nothing after it: mid-thought, never a search for the literal "label:".
    if (value === '' || !applyOperator(result, prefix, value, dayStart, dayStartOfDate)) {
      result.unknown.push(token);
    }
  }

  result.text = freeText.join(' ').trim();
  return result;
}

function addFlag(result: ParsedQuery, flag: NoteFlag | undefined): boolean {
  if (!flag) return false;
  if (!result.flags.includes(flag)) result.flags.push(flag);
  return true;
}

function applyOperator(
  result: ParsedQuery,
  prefix: string,
  value: string,
  dayStart: DayStart,
  dayStartOfDate: DayStartOfDate,
): boolean {
  const lower = value.toLowerCase();
  switch (prefix) {
    case 'label':
      result.labelNames.push(value);
      return true;
    case 'color':
    case 'colour':
      result.colorNames.push(lower);
      return true;
    case 'is': {
      if (addFlag(result, own(IS_FLAGS, lower))) return true;
      const scope = own(SCOPE_WORDS, lower);
      if (!scope) return false;
      result.scope = scope;
      return true;
    }
    case 'has':
      return addFlag(result, own(HAS_FLAGS, lower));
    case 'in': {
      const scope = own(IN_SCOPES, lower);
      if (!scope) return false;
      result.scope = scope;
      return true;
    }
    case 'before':
    case 'after': {
      const day = parseDate(lower, dayStart, dayStartOfDate);
      if (day == null) return false;
      // `after:monday` means from the start of that day onwards; `before:` is exclusive.
      if (prefix === 'before') result.before = day;
      else result.after = day;
      result.dateField ??= 'EDITED';
      return true;
    }
    default:
      return false;
  }
}

function parseDate(value: string, dayStart: DayStart, dayStartOfDate: DayStartOfDate): number | null {
  switch (value) {
    case 'today': return dayStart(0);
    case 'yesterday': return dayStart(-1);
    case 'tomorrow': return dayStart(1);
    case 'week': return dayStart(-7);
    case 'month': return dayStart(-30);
    case 'year': return dayStart(-365);
    default: return parseIsoDate(value, dayStartOfDate);
  }
}

/** Integer parse matching Kotlin `String.toIntOrNull()`: an optional sign and digits, nothing else. */
function toIntOrNull(value: string): number | null {
  return /^[+-]?\d+$/.test(value) ? Number.parseInt(value, 10) : null;
}

function parseIsoDate(value: string, dayStartOfDate: DayStartOfDate): number | null {
  const parts = value.split('-');
  if (parts.length !== 3) return null;
  const [year, month, day] = parts.map(toIntOrNull);
  if (year == null || month == null || day == null) return null;
  if (month < 1 || month > 12 || day < 1 || day > 31) return null;
  return dayStartOfDate(year, month, day);
}

/** Splits on whitespace, keeping `label:"two words"` together; quotes count only after a colon. */
function tokenize(input: string): string[] {
  const tokens: string[] = [];
  let current = '';
  let inQuotes = false;
  for (const c of input) {
    if (c === '"' && (inQuotes || current.endsWith(':'))) {
      inQuotes = !inQuotes;
      current += c;
    } else if (/\s/.test(c) && !inQuotes) {
      if (current) {
        tokens.push(current);
        current = '';
      }
    } else {
      current += c;
    }
  }
  if (current) tokens.push(current);
  return tokens;
}

function unquote(value: string): string {
  if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) return value.slice(1, -1);
  return value.startsWith('"') ? value.slice(1) : value;
}

/**
 * Local-midnight day functions for live use; the parser takes them injected so tests pin dates.
 * [localDayStartFrom] counts from a given instant, so "today" is the caller's now.
 */
export const localDayStartFrom = (now: number): DayStart => (daysFromToday) => {
  const date = new Date(now);
  date.setHours(0, 0, 0, 0);
  date.setDate(date.getDate() + daysFromToday);
  return date.getTime();
};

export const localDayStartOfDate: DayStartOfDate = (year, month, day) => {
  const date = new Date(2000, 0, 1);
  // setFullYear, not the constructor: `new Date(26, …)` means 1926.
  date.setFullYear(year, month - 1, day);
  date.setHours(0, 0, 0, 0);
  // Feb 30 rolls over to March; a date that does not exist is not a day the user typed.
  if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) return null;
  return date.getTime();
};
