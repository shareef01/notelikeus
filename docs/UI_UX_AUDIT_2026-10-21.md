# UI/UX audit — keyboard, feedback and dialogs (2026-10-21)

Baseline: `main` at `dfc3c061`. Branch: `claude/ui-a11y-keyboard-toasts`.

This is the fourth UI/UX pass on this repository. The earlier three
([`AUDIT_UI_UX_2026.md`](AUDIT_UI_UX_2026.md), [`UI_UX_AUDIT.md`](UI_UX_AUDIT.md),
[`design/UI_UX_REDESIGN.md`](design/UI_UX_REDESIGN.md)) already settled the design system
([`design/DESIGN_SYSTEM.md`](design/DESIGN_SYSTEM.md)), reflow, touch targets, contrast, sync
vocabulary and most journeys. This pass did not redo that work. It looked for what those passes'
methods could not see — **controls that render correctly and pass axe but cannot be operated** —
and fixed what it found.

## Method, and what was not done

- **Source reading** of every web component under `web/src/components`, `web/src/screens`, and the
  Compose editor, toolbar and main screen.
- **A real browser.** Headless Chromium (Playwright) against the Vite dev server in guest mode,
  with `@axe-core/playwright` (WCAG 2.0/2.1/2.2 A and AA tags), scripted keyboard input, reflow
  measurement at 320/375/390/768/1024/1280/1440 px, and console capture.
- **Compose desktop UI tests** (`runComposeUiTest`) for the toolbar's focus behaviour.
- **Not done:** no Android emulator or device run, no Windows desktop app driven by hand, no
  screen reader (NVDA/TalkBack) session. Statements about those are source-backed and marked.

### What the baseline already got right (measured, not assumed)

| Check | Result on `main` |
|---|---|
| axe, main screen (empty and with notes), float editor, note options sheet | 0 violations |
| Horizontal overflow at 320–1440 px | none (`scrollWidth == clientWidth` at every width) |
| Console errors through create → edit → trash | none |
| Web unit suite | 136 files, 1000 tests after this change (all passing) |

axe passing everywhere is exactly why the defects below survived: each control has a name, a
role and enough contrast. They fail on **operation**, which axe does not test.

## Deliverable A — issue register

Legend for *Evidence*: **R** = reproduced in a real browser, **T** = reproduced by an automated
test, **S** = source inspection only.

| ID | Component / screen | Problem | User impact | Sev | Evidence | Fix | Status |
|---|---|---|---|---|---|---|---|
| A1 | Web editor — `RichTextToolbar.tsx` | Every formatting button (Bold, Italic, Link, Bullet list, Checklist, Add image) acted on `pointerdown` only. Enter, Space and a screen reader's activation fire `click`, so they did nothing. No Ctrl+B / Ctrl+I either. | A keyboard-only or screen-reader user cannot format a note at all. WCAG 2.1.1. | **P1** | **R**: Bold focused, Enter pressed, body unchanged (`"hello world"`); a mouse click on the same button bolded. | Action moves to `onClick`; `pointerdown`/`mousedown` are still cancelled so a click keeps the body's focus and selection. Ctrl/Cmd+B and +I in the body. `aria-keyshortcuts` + tooltip. | Fixed |
| A2 | Compose editor — `RichTextToolbar.kt` | All six buttons had `focusProperties { canFocus = false }`; no shortcuts. | On Windows (and Android with a keyboard) formatting is unreachable without a mouse. WCAG 2.1.1. | **P1** | **S**, then **T**: making them focusable naively made a mouse click take focus from the body (desktop UI test failed) — which is why they had been removed from the focus order. | Buttons are focusable; Bold/Italic/List return focus to the body (`returnFocusTo`), so the caret and selection survive a mouse click. Ctrl/Cmd+B and +I via `Modifier.formattingShortcuts`. | Fixed |
| A3 | Web — `ToastHost.tsx` | Undo toast: fixed 5 s, not paused by hover or focus, no keyboard route. Error toasts: 3.5 s. Live region mounted together with its text. | The only way back from "Delete permanently" / "Empty trash" could expire under the user's pointer, and was practically unreachable by Tab. Errors vanished before they could be read. Announcements unreliable. WCAG 2.2.1, 4.1.3. | **P1** | **R**: toast gone after 5.3 s with the pointer resting on it. | Pause on hover/focus with remaining-time resume; 8 s with an action, 7 s error, 4 s notice; Ctrl/Cmd+Z runs Undo from anywhere outside a text field; a Dismiss button; two always-mounted live regions (`status` polite; `aria-live="assertive"` for errors, without `role="alert"` so an idle region never reads as an alert); focus rings on its buttons. | Fixed |
| A4 | Compose — `MainScreen.kt` undo snackbar | `SnackbarDuration.Short` (4 s) for Undo. | Same as A3 on Android/Windows. (On Android, M3's host stretches the timeout when an accessibility service asks for it; desktop has no such stretch.) | P2 | **S** | `SnackbarDuration.Long`. | Fixed |
| A5 | Web — `LinkDialog.tsx` | Enter did not add the link (no form); the dialog was a hand copy of `ModalDialog` without the page scroll lock that component's own comment calls a defect; focus shown only as a faint border tint. | Keyboard friction; on phones the page underneath could pan. | P2 | **S** | Rebuilt on `ModalDialog` with a `<form noValidate>` (keeps accepting `example.com`). | Fixed |
| A6 | Web — `EditorOptionsSheet.tsx` | "New label" field: Enter did nothing. Delete confirmation was another hand copy of the dialog scaffold. | Friction; two dialogs to keep in agreement. | P2 | **S** | Field and Add button are a form; confirmation is the shared `ConfirmDialog`. | Fixed |
| A7 | Web — `EditorScreen.tsx` delete | Trashing from a card offers Undo; trashing from the editor did not. | The same action reversible in one place and not the other. | P2 | **S** | Editor trash shows the same Undo toast, restoring the pre-trash note. | Fixed |
| A8 | Web — `NoteStaggeredGrid.tsx` reorder handle | Reordering by pointer drag only. | No keyboard or single-pointer alternative. WCAG 2.1.1 / 2.5.7. Reachable only when a legacy `manual` sort is stored (F18/F21), so few users see it. | P2 | **S** | ↑/↓ on the focused handle moves the note one place and commits; `aria-description` says so. | Fixed |
| A9 | Web — text fields | Focus shown by tinting a 1 px border at 40–50 % opacity (options sheet, link dialog, reminder input). | Hard to see which field has the caret. WCAG 2.4.7. | P2 | **S** | Shared `INPUT_FOCUS` token: the same 2 px ring as chrome controls. | Fixed (fields touched here) |
| A10 | Web — dialog footer buttons, checklist remove/add/convert, image remove, toast action | No explicit focus style; relied on the UA default ring, which on a coloured note or the dark image scrim can be faint. | Focus hard to track in the editor. | P2 | **S** | `CHROME_FOCUS` in the shared dialog button classes; `outline-current` on note-coloured controls; white ring on the image scrim. | Fixed |
| A11 | Web — card quick actions | Pin/Archive/Delete were 24 px. | Under the design system's own 32 px pointer minimum (§5). | P3 | **S** | 32 px buttons, 16 px icons. | Fixed |
| A12 | Web — main screen | After trashing or archiving from a card's quick actions, the card disappears and focus falls to `<body>`. | A keyboard user starts again from the top of the page. WCAG 2.4.3. | P2 | **S** | Focus moves to the card that takes the removed one's place (or the new last card). | Fixed |
| A13 | Web — `NoteCard` memo | `noteCardPropsAreEqual` compares callbacks that `NoteStaggeredGrid` recreates on every render, so the memo never holds. | Every card re-renders on any main-screen render. | P3 | **S** — *potential*; not profiled. | Stable per-note callbacks, or compare by note id. Profile first. | **Open** |
| A14 | Web — `SyncStatusBanner.tsx` | `role="status"` element mounts with its message. | Offline / failed-sync banner may not be announced. | P3 | **S** | Region kept mounted; only its content changes (as for toasts). | Fixed |
| A15 | Web — editor toolbar | `role="toolbar"` with every button a separate Tab stop. | Allowed, but the ARIA toolbar pattern expects one stop with arrow keys. | P3 | **S** — subjective. | Roving tabindex, if the team wants the pattern. | Not done (judgement call) |

Things checked and **not** found defective this pass: dialog focus trapping and nested Escape
ownership (`useFocusTrap`), layout radiogroup arrow keys, checklist Enter/Backspace, selection
checkbox naming, conflict-copy chip, save-failure banner with Retry/Discard, reflow.

## Deliverable B — design system

No new system. [`design/DESIGN_SYSTEM.md`](design/DESIGN_SYSTEM.md) and `tokens.json` stand; this
pass added the rules that were implicit and enforced them where it touched code:

| Rule | Token / implementation |
|---|---|
| Every interactive element shows a 2 px focus ring, offset 2 px. On chrome surfaces it is `brand-primary`; on a note's own colour it is `currentColor`; on an image scrim it is white. | `CHROME_FOCUS`, `focus-visible:outline-current` |
| Text fields show the same ring, not a border tint. | `INPUT_FOCUS` (new, `web/src/lib/ui/focusStyles.ts`) |
| A control acts on `click` — never on `pointerdown` alone. To keep focus where it is, cancel `pointerdown`/`mousedown`; do not move the action there. | `RichTextToolbar.tsx` |
| Toast timing: notice 4 s, error 7 s, with action 8 s; paused while hovered or focused; Ctrl/Cmd+Z runs Undo. Compose: Undo uses `SnackbarDuration.Long`. | `toastDurationMs`, `ToastHost.tsx`, `MainScreen.kt` |
| Live regions are mounted before their content changes. | `ToastHost.tsx` |
| Confirmations use `ConfirmDialog`; small form dialogs use `ModalDialog` and a `<form>` so Enter submits. | `ModalDialog.tsx`, `LinkDialog.tsx`, `EditorOptionsSheet.tsx` |
| Formatting shortcuts on both clients: Ctrl/Cmd+B bold, Ctrl/Cmd+I italic. | web body `onKeyDown`; Compose `formattingShortcuts` |
| Pointer targets ≥ 32 px (existing §5 rule, now met by card quick actions). | `NoteCard.tsx` |

## Deliverable C/D — changes

| Change | Why | Files | Risk left |
|---|---|---|---|
| Toolbar acts on click; Ctrl+B/I | A1 | `web/src/components/editor/RichTextToolbar.tsx`, `web/src/screens/EditorScreen.tsx` | Mobile Safari focus behaviour on cancelled `mousedown` is not device-tested; it is the same cancel the old code relied on, plus `mousedown`. |
| Compose toolbar focusable; focus returned to body; shortcuts | A2 | `composeApp/.../editor/components/RichTextToolbar.kt`, `.../editor/FormattingShortcuts.kt` (new), `.../editor/EditorScreen.kt` | Not seen on an Android device: a tap in touch mode does not focus a button, so `returnFocusTo` should be a no-op there — verify the IME does not flicker. |
| Toasts | A3 | `web/src/components/feedback/ToastHost.tsx` | Ctrl+Z is ignored while any dialog/sheet/overlay editor holds focus (by design, so text fields keep their own undo). |
| Compose undo duration | A4 | `composeApp/.../ui/main/MainScreen.kt` | None known. |
| Focus after a card leaves the list | A12 | `web/src/components/notes/NoteStaggeredGrid.tsx`, `NoteCard.tsx` (`data-note-open`) | Swipe actions on phones are not covered (touch, no keyboard focus to keep). |
| Sync banner live region | A14 | `web/src/components/layout/SyncStatusBanner.tsx` | None known. |
| Dialog consolidation and forms | A5, A6, A9, A10 | `LinkDialog.tsx`, `EditorOptionsSheet.tsx`, `ModalDialog.tsx`, `focusStyles.ts`, `ChecklistEditor.tsx`, `AttachmentImageStrip.tsx` | `ConfirmDialog` sits at `z-[60]` like the copy it replaces. |
| Editor trash Undo | A7 | `EditorScreen.tsx` | Undo restores the stored note with `isTrashed: false`, the same `saveNote` path card Undo uses. |
| Keyboard reorder | A8 | `NoteStaggeredGrid.tsx`, `NoteCard.tsx` | Only reachable in legacy manual sort. |
| Quick-action size | A11 | `NoteCard.tsx` | The hover pill is ~32 px wider. |

No schema, sync protocol, storage format or dependency changed.

## Deliverable E — verification

| Command / check | Result |
|---|---|
| `npm --prefix web run typecheck` | pass |
| `npm --prefix web run lint` | exit 0 (pre-existing warnings only) |
| `npm --prefix web test` | 136 files, 1000 tests, all pass (new: `ToastHost.test.tsx` ×8, `RichTextToolbar.test.tsx` ×4; `SyncStatusBanner.test.tsx` extended), rerun after every edit |
| `gradlew :composeApp:desktopTest :composeApp:testDebugUnitTest` | pass (new: `RichTextToolbarKeyboardTest` ×3) |
| Playwright, chromium, e2e build: `rich-text`, `toast-undo` (new), `focus-after-removal` (new), `dialog-a11y`, `card-actions`, `journey-f-actions`, `labels-journey`, `keyboard-scope`, `reflow`, `touch-targets`, `sync-status` | 45 passed, 1 failed. The failure is `sync-status`, at its `signUp()` step: it needs local Supabase, which was not running here. It never reached the banner. |
| `accessibility.spec.ts` | Guest-only test passed; the 7 tests that `signUp()` failed at sign-up for the same reason (no local Supabase). **Not** a verification of this change — rerun with `npm run supabase:start` then `npm run test:e2e`. |
| Browser probe after the fix | Bold focused + Enter → `hello **world**`, focus back on the body, selection kept; axe still 0 on every screen probed; reflow unchanged; no console errors |

Not executed: Android instrumented tests, an Android device, the Windows app by hand, a screen
reader, Safari/Firefox, the `mobile-chrome` Playwright project.

Screenshot: [`design/screenshots/a11y-2026-10-21/undo-toast-focus-light-1280.png`](design/screenshots/a11y-2026-10-21/undo-toast-focus-light-1280.png).

## Deliverable F — remaining roadmap (impact ÷ effort, highest first)

1. **Screen-reader pass** (NVDA + Chrome, TalkBack): the live-region changes in A3 are built to the
   pattern that works, but no one has listened to them yet.
2. **Android device check of A2**: Tab through the toolbar with a hardware keyboard; tap Bold with
   the IME open and confirm it stays open.
3. **A13**, profile the notes grid with a few hundred notes before changing the memo (P3).
4. **A15**, toolbar roving tabindex (P3, judgement call).
