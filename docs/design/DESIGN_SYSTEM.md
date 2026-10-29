# Notelikeus design system

Machine-readable tokens: [`tokens.json`](tokens.json). This document says how to use them and what
each client ships today. It describes the system the app *already has* — Inter, neutral surfaces,
eight note colours, 18 px card radius — tightened where the audit found problems, not a new brand.

## 1. Principles

1. **The notes are the interface.** Chrome is neutral and quiet; colour belongs to notes and to
   status that needs attention.
2. **Calm by default, loud only when acting is required.** Saved and synced are quiet; a failed
   save or a conflict is not.
3. **Same meaning everywhere.** A status, a colour or an icon means the same thing on Android,
   Windows and the web, even where the component that shows it is platform-native.
4. **Accessible is the baseline.** Every text role clears WCAG 2.2 AA on every surface of every
   theme, and two tests enforce it (§6).

## 2. Colour

### 2.1 Neutral roles

| Role | Use | Light | Dark | True black |
|---|---|---|---|---|
| `background` | App canvas | `#F4F4F2` | `#101010` | `#000000` |
| `surface` | Cards, editor, sheets | `#FFFFFF` | `#1B1B1B` | `#000000` |
| `surfaceVariant` | Inputs, selected rows, chips | `#ECECEA` | `#272727` | `#121212` |
| `onSurface` | Text, icons | `#151515` | `#EDEDED` | `#FFFFFF` |
| `onSurfaceSecondary` | Body preview, labels | `#4D4D4B` | `#B0B0B0` | `#A8A8A8` |
| `muted` | Placeholders, timestamps | `#666663` | `#949494` | `#999999` |
| `outline` | Card borders, dividers | `#D6D6D3` | `#323232` | `#262626` |

The light neutrals carry a trace of warmth (≈ 2 % toward yellow) so a page of white cards reads as
paper rather than as a spreadsheet; dark stays neutral so note colours keep their hue.

### 2.2 Accent and semantic colour

Accent (neutral / blue / green) tints `primary` and focus only — never surfaces. Semantic colours
are reserved for status:

| Token | Light | Dark | Meaning |
|---|---|---|---|
| `success` | `#1B6B2E` | `#7FD08F` | Synced |
| `info` | `#0B57D0` | `#A8C7FA` | Syncing, waiting to sync |
| `caution` | `#9A5B00` (chip fill `#F59E0B` @ 15–18 %) | `#F5B544` | Conflict copy; offline with unsynced changes |
| `danger` | `#B3261E` | `#F2B8B5` | Save failed, sync failed, destructive confirmation |

Status is never colour alone: every status has a word and an icon (§5).

### 2.3 Note colours

Eight pastel/dim pairs (`tokens.json → color.note`), unchanged from the shipped palette and already
contrast-tested (`NotePaletteContrastTest`). **Rule:** on a coloured note, secondary text is the
note's own text colour at 80 % opacity, never the `muted` token — `muted` on the dark blue note is
3.0:1, the note colour at 80 % is 5.5:1.

### 2.4 One source for every client

The chrome colours of all seven themes (light, dark, true black, midnight, forest, and midnight and
forest at true black) live in [`contracts/design/theme-colors.json`](../../contracts/design/theme-colors.json).
`ThemeTokensContractTest` holds Compose's `colorSchemeFor` to it and `themeTokens.test.ts` holds the
cascaded variables of `globals.css` to it, so a colour changed on one client fails the other's build
until it changes there too. Before M5 the light and dark palettes differed slightly per client (e.g. a
light background of `#F7F7F7` on the web and `#F0F0F0` in Compose), and the web lost an accent's hue
at true black.

## 3. Typography

Inter everywhere (bundled on both clients), system UI fallback.

| Style | Size / line | Weight | Tracking | Web | Compose |
|---|---|---|---|---|---|
| Overline / section label | 11 / 16 | 600 | 0.08em, uppercase | `text-overline` | `AppType.chromeLabel` |
| Card title | 18 / 28 | 600 | −0.02em | `text-note-title` | `AppType.noteCardTitle` |
| Card body | 15 / 24 | 400 | 0.005em | `text-note-body` | `AppType.noteCardBody` |
| Editor title | 22 / 28 | 600 | −0.03em | — | `AppType.editorTitle` |
| Editor body | 16 / 25 | 400 | 0.01em | — | `AppType.editorBody` |
| Metadata (time, label chips, checklist progress, "+N") | 12 / 16 (11 in compact) | 500 | tabular numerals; user text keeps its own case | `text-[12px]` in `NoteCard` | `NoteCardLabelChipStyle`, `timestampFontSize` |

Readability rules: editor measure ≤ 48 rem (~70 characters); body never below 15 px; titles clamp at
2–3 lines on cards, never in the editor; untitled notes show no title line (D15).

## 4. Space, shape, elevation, motion

- **Spacing** 2 / 4 / 8 / 12 / 16 / 20 / 24 / 32; card gap 14; layout gap 20 (`Spacing.kt`, `--spacing-*`).
- **Radius** 4 / 8 / 12; cards 18; sheets 16; pills fully rounded. Cards are the only large radius.
- **Borders** 1 px `outline`; selection adds a 2 px accent ring, never only a colour change.
- **Elevation** card 2 dp / `--shadow-card`; hover 3 dp; overlays 6 dp. No glass, no blur behind text.
- **Motion** 100 / 150 / 250 / 400 ms, `cubic-bezier(0.2, 0, 0, 1)`. Under reduced motion,
  translation and scale are removed and fades cap at 100 ms. Nothing animates in front of a text
  caret: opening the editor focuses the field before any transition finishes.

## 5. Components

| Component | Rules | Web | Compose |
|---|---|---|---|
| App shell | Rail/sidebar ≥ 840 px, drawer below; top bar holds search, layout, settings, new note | `SideDrawer`, `TopBar` | `MainScreen`, drawer |
| Note card | Title, ≤ 7 preview lines keeping line breaks, ☐/☑ for task syntax, chips, status cluster, relative time | `NoteCard` | `NoteCard` |
| Label chip | Card text colour at 14 % fill (`NoteEmphasis.Decorative`), label as typed, long names truncate with the full name as tooltip; "+N" names the hidden labels to assistive technology | `NoteCard` | `NoteCardLabelChips` |
| Conflict chip | Caution fill, "Conflict copy", tooltip/description explains; accessible status "Kept from a sync conflict" | `NoteCard` | `NoteCardLabelChips` |
| Editor | Float / dock / full-screen on web; full-screen on phone; save state line at the bottom | `EditorScreen` | `EditorScreen` |
| Save & sync status | §5.1 | `EditorBottomBar`, sync banner | `CloudSyncStatus` |
| Dialog vs sheet | Confirmation is a dialog (D7); option lists are sheets on phones, menus on desktop | | |
| Toast / snackbar | One at a time; undo for every reversible destructive action; failures that need action stay until dismissed | `toastStore` | `SnackbarHost` |
| Focus | 2 px accent ring, 2 px offset, on every interactive element (F43) | `CHROME_FOCUS` class | Compose focus indication (F43) |
| Hit targets | ≥ 48 dp touch, ≥ 32 px pointer; small icons grow their hit area, not their box (D26) | | |

### 5.1 Save & sync status vocabulary

One vocabulary for every client. "Synced" is shown only after the server confirmed the write.

| State | When | Presentation | Tone |
|---|---|---|---|
| Saving… | Local write in flight | Editor status line | Quiet |
| Saved on this device | Local write done, guest or sync off | Status line | Quiet |
| Waiting to sync | Local write done, upload pending | Status line + small dot on avatar | Quiet |
| Syncing | Upload or pull running | Animated dot (static under reduced motion) | Quiet |
| Synced | Server confirmed | Status line, then fades | Quiet |
| Offline — changes saved here | No network, pending writes | Banner on main screen, dismissible | Caution |
| Couldn't sync | Sync error, retrying | Banner with Retry | Caution |
| Conflict copy kept | A conflicted copy was created | Inline notice in editor, chip on card | Caution |
| Couldn't save | Local write failed | Persistent notice with Retry / Discard; unload guard | Danger |

## 6. Accessibility guarantees

- Contrast: `ThemeTextContrastTest` (Compose, every base × accent × black level × system setting)
  and `themeContrast.test.ts` (web theme classes) fail the build below 4.5:1 for primary and muted
  text on background, surface and surface-variant. `NotePaletteContrastTest` covers note colours.
- Non-colour indicators: status always has text; selection has a ring and a check; conflict has a
  word, not only amber.
- Screen readers: every card's accessible name includes status parts ("Pinned, Reminder set, Kept
  from a sync conflict"); toggles expose state (F17, F20).
- Reduced motion honoured on both clients.

## 7. Mapping

| Token | Tailwind (web) | Compose |
|---|---|---|
| `color.*` neutrals | `bg-true-surface`, `text-brand-primary`, `text-brand-muted`, `border-brand-outline` (CSS vars `--*-rgb`) | `MaterialTheme.colorScheme.surface / onSurface / onSurfaceVariant / outline` |
| `color.note.*` | `--color-note-*-light/dark` | `NotePalette` |
| `typography.*` | `text-note-title`, `text-note-body`, `text-overline` | `AppType.*` |
| `spacing.*` | `p-2…`, `--spacing-note-gap` | `Spacing.*` |
| `radius.*` | `rounded-note`, `rounded-sheet` | `Radius.*`, `Shapes` |
| `elevation.*` | `shadow-card`, `shadow-card-lg` | `Elevation.*` |
| `motion.*` | Tailwind `duration-*`, `motion-reduce:` | `Motion.*Ms` |
