# UI/UX redesign specification

Evidence: guest-mode captures of the running web app at 1440 / 1280 / 768 / 390 / 360 px in light
and dark, before (`screenshots/baseline/`) and after (`screenshots/after/`) this pass, produced by
`web/scripts/captureUiScreens.mjs`. Native screens were assessed from source and the existing
`screenshots/android-*.png`; no device or emulator was available.

**Position.** Notelikeus does not need a new visual identity. The shipped design is already calm,
note-first and consistent across clients. What it lacked was *trust UX* — the interface could not
show that a concurrent edit had been preserved, because it wasn't — plus a handful of readability
and consistency defects. This specification therefore redesigns where the evidence points (sync
trust, conflicts, previews, theme defaults, token drift) and keeps the rest.

## 1. Experience principles

1. **Nothing disappears silently.** Every path that removes or replaces user words either asks,
   offers undo, or leaves a visible copy.
2. **Capture first.** New note is one keystroke (`N`) or one tap, search is `/` or `Ctrl K`; nothing
   animates between the tap and a focused caret.
3. **Status is calm until it matters.** Saved/synced is a quiet line; a failure or conflict is
   visible and actionable.
4. **The note is the hero.** Chrome recedes; notes carry the colour.
5. **One product, three platforms.** Same vocabulary, same states, same marks; native components.

## 2. UX audit — journeys

| Journey | Steps today | Friction found | Status |
|---|---|---|---|
| First launch, no account | Auth screen → "Continue without an account" → empty state with "New note" | Copy implied sync was Android-only (AU-16); theme ignored a light OS (AU-10) | Fixed |
| Rapid capture | `+` / FAB → editor focused → type → Back | None blocking; autosave + "Saved locally" line | Keep |
| Edit & autosave | Debounced local save, status line | Concurrent remote edit could be overwritten while typing (AU-04/05) | Fixed: refresh or copy + notice |
| Checklist | `- [ ]` converts to checklist while typing; "Convert to checklist" | Pasted task syntax stayed literal on cards (AU-11) | Fixed (☐/☑ on cards) |
| Organise (labels, colours) | Colour filter row, label chips, Edit labels | Colour swatches are unlabeled visually (named for AT) | Proposal §6.3 |
| Search large library | Search box (`Ctrl K`), relevance + fuzzy fallback | 0.5 s per keystroke at 10k notes (AU-06) | Fixed (≈18 ms) |
| Pin / archive / delete | Card hover actions, swipe, multi-select, undo | Fine | Keep |
| Recover a deletion | Trash → restore; undo toast after permanent delete | A delete without a known revision silently comes back (AU-07) | Designed §7.3 |
| Sign in & sync | Google; first snapshot merges local notes | Offline edits could be lost on merge (AU-01/02) | Fixed |
| Work offline | Writes local; reconcile on `online` | Offline + pending had no explicit main-screen indicator | Proposal §7.1 |
| Resolve a conflict | — (did not exist) | Silent loss | Copy + chip now; review panel designed §7.2 |
| Backup export/import | Settings → Export with images / notes only / Import | Fine; import adds copies by design | Keep |
| Diagnose sync | Settings → Sync diagnostics (no note content) | Fine | Keep |
| Keyboard only | Focus rings everywhere (F43), roving radio groups, focus traps | Fine in audited screens | Keep |
| Narrow phone (360 px) | Single column, horizontal filter row with scroll affordance, FAB | Fine; FAB overlaps last card's text until scrolled (normal FAB behaviour, list has bottom padding) | Keep |

Heuristic summary (Nielsen): visibility of system status was the weakest heuristic (no conflict
state, no offline-pending state); error prevention and recovery now hold for concurrent edits;
consistency across platforms was broken by the theme default and token drift.

## 3. Information architecture

```
Notes (default)        ── pinned group, then by date (or manual order)
  ├─ search (Ctrl K)    ── relevance; fuzzy "did you mean" fallback is labelled
  ├─ colour / label / sort filters
Reminders (smart view, native) · Archive · Trash
Labels (manage)
Settings
  ├─ Account & sync     ── status, sign in/out, sync diagnostics
  ├─ Appearance         ── theme (System default), accent, pure black, layout, sort
  ├─ Data & backup      ── export (.nlkbak / JSON), import
  └─ About & privacy
```

No new top-level destinations are proposed. "Conflicts" is deliberately *not* a destination: copies
live with the notes they belong to, marked, so nothing hides in a separate inbox.

## 4. Screen inventory

| Screen | Desktop | Phone | State coverage |
|---|---|---|---|
| Notes dashboard | Sidebar + grid/list/compact | Top bar + single column + FAB | empty, loading, populated, search results, fuzzy results, offline banner, sync error banner |
| Editor | Float / dock / full screen | Full screen | new, existing, saving, saved, save failed, conflict copy notice |
| Search results | Inline in dashboard, highlight | Same | no results, fuzzy |
| Labels | Overlay with focus trap (F37) | Sheet | empty, rename, delete confirm |
| Archive / Trash | Same grid | Same | empty with explanation, restore, empty trash confirm |
| Settings | Two-column cards | Single column | guest, signed in |
| Sync diagnostics | Panel | Full screen | copied |
| Conflict review (new) | Side-by-side | Stacked with tabs | §7.2 |
| Auth / onboarding | Centred card | Full screen | — |

## 5. Layout strategy

- **≥ 1200 px:** sidebar (collapsible) + up to 4 grid columns; editor floats by default, dock option
  gives a split view for reading one note while editing another.
- **840–1199:** sidebar collapses to rail; 3 columns.
- **600–839:** drawer; 2 columns; editor full height.
- **< 600:** drawer; 1 column; editor full screen; FAB for new note; filter row scrolls with a
  visible affordance.

Editor modality is a *hybrid*, already shipped on web: floating keeps the grid as context for quick
edits, dock supports reference-while-writing, full screen supports long writing. Keep it; make the
choice sticky per device (it is).

## 6. Component specifications (delta from today)

### 6.1 Note card
- Preserve line breaks; render task syntax as ☐/☑ — **implemented**.
- Conflict chip (caution) with description — **implemented** on web and Compose.
- Time label: today → time; this year → "12 Oct"; older → "12 Oct 2025". Use the locale's 12/24 h.

### 6.2 Status cluster (top bar / avatar)
A single small indicator next to the avatar: none when synced and idle; dot + tooltip/label for
waiting/syncing; caution icon for offline-with-pending or sync error, opening a popover with the
plain-language state and *Retry* / *Sync diagnostics*.

### 6.3 Colour filter row
Keep swatches; on hover/focus show the colour name; selected swatch gets a check *and* a ring.
Long-press on phone shows the name.

## 7. Save & sync trust (special requirement)

The vocabulary is in [DESIGN_SYSTEM.md §5.1](DESIGN_SYSTEM.md). Behaviour:

### 7.1 Offline
- Banner on the notes screen: "You're offline. Changes are saved on this device and will sync when
  you reconnect." Dismissible; reappears only if new changes are made while still offline.
- Editor status line: "Saved on this device · waiting to sync".

### 7.2 Conflict review (designed, not implemented — AU-08)
Entry points: the conflict chip on a card, the editor notice, and a "Review" action in the
notice. The panel:

```
┌ This note changed on two devices ───────────────────────────────┐
│ Nothing was lost. Pick what to keep, or keep both.              │
│                                                                 │
│  On this device · edited 14:05          On another device · 14:02│
│  ┌───────────────────────────┐          ┌───────────────────────┐│
│  │ Groceries                 │          │ Groceries             ││
│  │ milk, bread               │          │ milk, eggs            ││
│  └───────────────────────────┘          └───────────────────────┘│
│  differences highlighted (word-level)                            │
│                                                                 │
│ [Keep both]  [Keep this device's]  [Keep the other]  [Merge…]    │
└─────────────────────────────────────────────────────────────────┘
```
- *Keep both* (default, no data change): removes the chip by renaming the copy's suffix away.
- *Keep one*: moves the other version to **Trash** (recoverable), never deletes it.
- *Merge…*: opens the editor on the original with the copy's text appended under a divider.
- Timestamps are client edit times, labelled as such; no revision numbers unless diagnostics open.
- Phone: two tabs ("This device" / "Other device") instead of side by side.

### 7.3 Deleted note came back (AU-07)
When a snapshot restores a note this device had deleted, show once: "A note you deleted was changed
on another device, so it's back." with *Delete again* and *Keep*. The protocol side (content digest
at delete time) is in the roadmap.

## 8. Mobile adaptations
- One-handed: FAB bottom-right, primary editor actions in the bottom bar, destructive actions in
  the overflow, never in the thumb's resting spot.
- Back gesture closes the editor after flushing the save; a failed save keeps the editor open with
  the notice (already implemented on web and Compose).
- Swipe actions keep undo; discoverability via a one-time hint after the third note.
- Insets: content respects system bars and the IME (`useVisualViewportBottomInset` on web).

## 9. Accessibility requirements (WCAG 2.2 AA)
- Contrast enforced by tests on both clients (AU-19 fixed).
- Target size ≥ 24 × 24 CSS px minimum (2.5.8), ≥ 48 dp on touch (D26 approach).
- Status messages via `role="status"` (conflict notice) / `role="alert"` (save failed).
- Focus not obscured (2.4.11): sticky top bar must not cover the focused card — scroll padding.
- Dragging alternatives (2.5.7): manual reorder already has keyboard/screen-reader alternative (F10).
- Text scaling to 200 %: cards reflow; editor toolbar wraps.
- RTL: layouts use logical properties where Tailwind allows; full RTL audit not performed.

## 10. Motion
Durations and easing in tokens. Editor open: 150 ms fade/scale from the card on desktop, none on
phone (full-screen push), none under reduced motion. Checklist tick: 100 ms. Never animate text
reflow while typing.

## 11. Error-state design
Plain language, what happened → what is safe → what to do. Example (sync): "Couldn't reach the
server. Your notes are saved on this device. We'll keep trying — or Retry now." Raw error text,
revision numbers and ids belong in Sync diagnostics only (the mis-decoded dashes in raw errors,
AU-15, showed why).

## 12. Rationale for what is *not* changed
- No new palette or type family: the shipped ones are coherent, tested for contrast and recognised
  by existing users; changing them is cost without user value.
- No AI features: no proposal met the bar of a clear benefit with an explicit privacy model; local
  search with fuzzy fallback already covers retrieval.
- No new navigation destinations.
