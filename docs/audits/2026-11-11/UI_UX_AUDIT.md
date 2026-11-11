# Cross-platform UI/UX audit — 2026-11-11

Revision audited: `8d52068c` (`main`, after #461). Platforms: Android and Windows (shared Compose
Multiplatform code in `composeApp/`) and Web/PWA (a separate React app in `web/`).

> **All twelve findings have since been fixed.** [`FIXES.md`](FIXES.md) lists the PR for each one and
> what is still unverified. The text below is unchanged and describes `8d52068c`.

## How this audit was done, and what it does not cover

- **Static source audit only.** No app was built or run for this pass: no screenshots, no device, no
  browser session. Every finding below is **code-inferred** unless it says otherwise. Each one cites
  the lines that establish it.
- It builds on three earlier audits that did collect runtime evidence:
  [`2026-11-05/REPORT.md`](../2026-11-05/REPORT.md), [`2026-11-06/PIXEL.md`](../2026-11-06/PIXEL.md),
  [`2026-11-06/REMAINING_SURFACES.md`](../2026-11-06/REMAINING_SURFACES.md) and
  [`2026-11-06/web-ui-ux/REPORT.md`](../2026-11-06/web-ui-ux/REPORT.md). Their web findings UX-01 to
  UX-12 are recorded as fixed in [`FIXES.md`](../2026-11-06/web-ui-ux/FIXES.md). They are **not**
  reported again here, and this pass did not re-check them at runtime.
- This pass concentrated on behaviour that differs across the platforms, on whether the copy
  (dialogs, toasts, the privacy policy) matches what the code does, and on the hypotheses in the
  brief. It was **not** a pixel-by-pixel visual review of every screen. That review, a screen-reader
  pass (TalkBack, Narrator, NVDA/VoiceOver) and Windows display-scaling and multi-monitor checks are
  still to do (§G, §I).

---

## A. Executive assessment

**Maturity: high for a project of this size.** The data-safety work is unusually thorough: sealed
local storage on every platform, conflict review, tombstones, wipe generations, undo for destructive
actions, and guarded sign-out. Earlier audits already covered web dialogs, contrast, reflow and
history. The code documents its intent well, and many UX decisions are written down where they are
made (for example, why `SortSheet.tsx` leaves out Manual order).

**Strengths to keep**
- One shared Compose UI for Android and Windows, with platform-specific adaptations where they matter
  (title bar, editor windows, tray, predictive Back).
- A consistent destructive-action model with Undo on trash, archive and permanent delete, and a
  confirmation before data that cannot come back is lost.
- An accessible structure on native: colour swatches have spoken names, swipe actions are also
  exposed as accessibility actions, and drag-to-reorder has Move up / Move down alternatives.

**Main risks**
1. **Reminders on Windows stop silently once the window is closed** (NL-01, P1).
2. **What sign-out does is described differently on every surface, and inaccurately on some.**
   Native leaves search history and saved filters behind for the next account (NL-02, NL-03).
3. **The two codebases have drifted apart on features.** Native has a full query system (filters
   sheet, query syntax, smart views, saved filters, manual order); web has one colour chip, one label
   chip and text search (NL-04, NL-05).
4. **The privacy policy has three diverging copies** and does not mention Cloudflare Turnstile (NL-06).

**Highest-value opportunity:** treat the shared query model (`NoteQuery` / `NoteQueryParser`) as a
product contract and port it to web, rather than letting each client grow its own filters.

---

## B. Screen and component inventory

✓ = implemented, — = not present. Source roots: native `composeApp/src/commonMain/kotlin/com/aus/notelikeus/ui/`,
web `web/src/`.

| Surface | Android | Windows | Web | Native source | Web source |
|---|---|---|---|---|---|
| Boot / splash / lock | ✓ biometric | ✓ `DesktopBiometricPrompt` | ✓ `BootGate`, `AppSplash` | `App.kt`, desktop `main.kt` | `components/boot/` |
| Sign-in (email code) and guest | ✓ | ✓ | ✓ + Turnstile | `auth/SignInGate.kt` | `screens/AuthScreen.tsx`, `components/auth/` |
| Notes list (grid, list, compact) | ✓ | ✓ | ✓ (1–3 columns) | `main/MainScreen.kt`, `components/NoteStaggeredGrid.kt` | `screens/MainScreen.tsx`, `components/notes/` |
| Drawer / sidebar | ✓ modal | ✓ collapsible rail | ✓ | `main/MainDrawerContent.kt` | `components/layout/SideDrawer.tsx` |
| Smart views (Reminders, Unfinished, Unlabelled) | ✓ | ✓ | — | `MainDrawerContent.kt:346` | — |
| Saved filters | ✓ (up to 20) | ✓ | — | `domain/model/SavedFilter.kt` | — |
| Filters sheet (colours, labels any/all, status, date, scope) | ✓ | ✓ | — (one colour and one label chip) | `main/components/FiltersSheet.kt` | `components/layout/FilterRow.tsx` |
| Search query syntax (`label:`, `is:`, `before:` …) | ✓ | ✓ | — | `domain/query/NoteQueryParser.kt:90` | — |
| Recent searches | ✓ | ✓ | inert (NL-07) | `MainTopAppBar.kt:326` | `TopBar.tsx:112` |
| Sort | Manual / Newest / Oldest | same | Newest / Oldest | `ViewModeMenu.kt` | `layout/SortSheet.tsx` |
| Manual reorder | drag + Move up/down | drag + Move up/down | — | `NoteStaggeredGrid.kt:151` | — |
| Multi-select bar | ✓ | ✓ + Delete key | ✓ | `MainTopAppBar.kt` | `layout/SelectionBar.tsx` |
| Swipe to archive / trash | ✓ | ✓ (pointer) | ✓ | `SwipeableNoteCard.kt` | `notes/SwipeableNoteCard.tsx` |
| Editor: text, Markdown, checklist, images | ✓ | ✓ + separate windows | ✓ float / dock / fullscreen | `editor/` | `screens/EditorScreen.tsx`, `components/editor/` |
| Editor options sheet (colour, labels, reminder, delete) | ✓ | ✓ | ✓ | `EditorBottomSheet.kt` | `EditorOptionsSheet.tsx` |
| Reminder picker | ✓ | ✓ | ✓ | `EditorScreen.kt` | `ReminderPickerDialog.tsx` |
| Link dialog | — (Markdown) | — | ✓ | — | `LinkDialog.tsx` |
| Labels manager | ✓ | ✓ | ✓ | `labels/LabelsScreen.kt` | `screens/LabelsScreen.tsx` |
| Archive / Trash / Empty trash | ✓ | ✓ | ✓ | `TrashBanner.kt`, `MainDialogs.kt` | `TrashBanner.tsx`, `EmptyTrashDialog.tsx` |
| Settings / profile / theme / accent | ✓ | ✓ | ✓ | `ProfileSheet.kt`, `ThemePicker.kt` | `settings/ProfileSheet.tsx` |
| Backup export / import | ✓ | ✓ | ✓ with preview | `BackupTransferEvent.kt` | `screens/main/importDescription.ts` |
| Sync status / offline | snackbar and status | same | `SyncStatusBanner` | `CloudSyncStatus.kt` | `layout/SyncStatusBanner.tsx` |
| Conflict review | ✓ | ✓ | ✓ | `ConflictReviewDialog.kt` | `notes/ConflictReviewDialog.tsx` |
| Cloud-wipe notice | ✓ | ✓ | ✓ | `MainDialogs.kt` | `notes/CloudWipeNotice.tsx` |
| Sign-out / delete cloud data / delete account / confirm identity | ✓ | ✓ | ✓ | `MainDialogs.kt`, `DeleteAccountDialog.kt`, `ConfirmIdentityDialog.kt` | `settings/SignOutDialog.tsx`, `DeleteAccountDialog.tsx`, `auth/ConfirmIdentityDialog.tsx` |
| Diagnostics, privacy policy | ✓ | ✓ + About | ✓ | `DiagnosticsDialog.kt`, `PrivacyPolicyDialog.kt` | `settings/` |
| Install prompt, error boundary, toasts | — | tray notifications | ✓ | — | `InstallPrompt.tsx`, `ErrorBoundary.tsx`, `ToastHost.tsx` |
| Home-screen widget, share target | ✓ | — | — | `androidMain` | — |

---

## C. Detailed findings

Every finding below is code-inferred unless marked otherwise.

### NL-01 — P1 — Windows: reminders stop firing once the window is closed, and nothing says so

> **Status: fixed in #463, using fix (a).** While a reminder is pending, closing the window hides
> it to the tray, with a one-time notice. Exit in the tray menu quits. A second launch brings the
> running copy forward (`util/SingleInstance.kt`), hiding re-locks app lock, and the confirmation,
> web picker and privacy policy copy are corrected. Not yet tested on a real Windows session.

- **Where:** reminders, Windows.
- **Actual behaviour:** closing the main window ends the process: `onCloseRequest = ::exitApplication`
  (`composeApp/src/desktopMain/kotlin/com/aus/notelikeus/main.kt:222`). The tray icon only exists
  while the process runs (`main.kt:194-205`). Reminders are kept in an in-process `java.util.Timer`
  with no OS scheduler behind it, as the class's own comment says
  (`desktopMain/.../platform/DesktopReminderManager.kt:6-10`). A reminder that falls due while the
  app is closed is only caught up at the next launch.
- **What the user is told:** the shared confirmation reads "Reminder set"
  (`commonMain/.../ui/editor/EditorScreen.kt:162`, `strings.xml:140`), with no caveat. The web picker
  even suggests that only web is limited: "unlike the Android app, which uses system alarms"
  (`web/src/components/editor/ReminderPickerDialog.tsx:47-48`). The privacy policy calls only web
  reminders best-effort (`PRIVACY_POLICY.md`, Permissions).
- **Steps to reproduce (expected from the code):** set a reminder 5 minutes ahead, close the window
  with ×, and wait. No notification arrives. Relaunch and a late catch-up notification appears.
- **Expected behaviour:** the reminder fires on time, or the user is told before relying on it.
- **User impact:** a missed time-critical reminder. "Close" is the normal desktop gesture, so this
  will happen.
- **Fix:** (a) while any future reminder exists, close to the tray instead of exiting. Add an "Exit"
  item that really quits (already there), and show a one-time "Notelikeus keeps running in the tray
  so reminders can fire" notice. Or (b) register a Windows scheduled toast, or a Task Scheduler entry,
  per reminder. Either way, give the reminder confirmation and picker a desktop-specific caveat, and
  correct the web picker's comparison.
- **Regression tests:** a desktop UI test that close with a pending reminder keeps the process alive
  (or shows the notice); a string test for the desktop copy.
- **Effort:** medium for (a), large for (b).

### NL-02 — P2 — Android and Windows: search history and saved filters survive sign-out and account switch

- **Where:** search and saved filters; sign-out.
- **Root cause:** recent searches (`RECENT_SEARCHES_KEY`) and saved filters (`SAVED_FILTERS_KEY`)
  live in the preferences DataStore (`commonMain/.../data/local/SettingsDataStore.kt:18,20`), not in
  the SQLCipher database. Searches are recorded whenever the user presses the keyboard's Search key
  (`ui/main/components/MainTopAppBar.kt:326-329`, `data/repository/SettingsRepositoryImpl.kt:212-218`).
  `clearAllUserData()` clears notes, checklists and labels only (`data/repository/NoteRepositoryImpl.kt:428-440`),
  and sign-out does not touch either key (`ui/main/CloudSyncController.kt:216-258`).
- **Actual behaviour:** after account A signs out, account B, or a guest, sees A's recent search terms
  and A's saved filter names and queries in the drawer. Those can include label names, search text
  and filter names such as "Invoices from Dr X".
- **Expected behaviour:** what the sign-out dialog promises: "clears notes on this device so another
  account cannot inherit them" (`strings.xml:209`). The privacy policy makes the same promise
  (`PRIVACY_POLICY.md:11`).
- **Fix:** clear both keys in the sign-out and account-switch paths. Saved filters could instead be
  stored per account. Nothing else in `SettingsDataStore` holds user content; check it again whenever
  a key is added.
- **Regression tests:** a `CloudSyncController` unit test that, after a successful sign-out, both
  flows emit empty lists.
- **Effort:** small.

### NL-03 — P2 — Sign-out works differently on web and native, and its copy is inaccurate on both

| | Native (Android, Windows) | Web |
|---|---|---|
| Local notes after sign-out | Deleted (`clearAllUserData`) | **Kept**, sealed and namespaced to the account, and restored when the same account signs back in (`web/src/lib/bootstrap.ts:186-195`) |
| Edits that haven't synced | Blocks, and warns they will be deleted "for good" (`strings.xml:212`) | Kept; "uploads the next time you sign in to this account here" (`SignOutDialog.tsx:47`) |
| Dialog copy | "clears notes on this device" (accurate) | "only clears locally cached app data on this device" (`SignOutDialog.tsx:35-39`): understates what stays |
| Both dialogs | "You'll need to sign in again to keep using Notelikeus". Inaccurate: after sign-out both apps offer "Continue without an account" (`SignInGate.kt` `onSkip`; `AuthScreen.tsx:205`) | same |
| Privacy policy | "Signing out clears locally cached notes on this device" (`PRIVACY_POLICY.md:11,34`; `PrivacyPolicyDialog.tsx`) | **Not true on web** |

- **User impact:** a user who signs out on one platform after doing it on the other expects the
  wrong thing. On a shared computer, a web user believes the notes are gone when an encrypted copy
  remains in that browser profile. The policy statement is inaccurate for web.
- **Fix:** choose one model and state it everywhere. The web model loses less, so the cheapest
  consistent outcome is to keep web's behaviour and correct the web dialog and the policy: "Your
  notes stay encrypted in this browser for this account until another account signs in here or you
  clear local data". Drop "you'll need to sign in again" on both platforms. If native should keep
  unsynced work the way web does, that is a separate, larger change.
- **Effort:** small for the copy, large to align the behaviour.

### NL-04 — P2 — Filtering and search: web has a fraction of native's query system

- **Native:** a Filters sheet (several colours, labels with any/all, status flags, date presets and
  ranges, scope: `FiltersSheet.kt`; strings at `strings.xml:392-399`), typed query syntax
  (`label: color: is: has: before: after: in:`, `domain/query/NoteQueryParser.kt:90`), smart views
  (Reminders, Unfinished, Unlabelled: `MainDrawerContent.kt:346`) and named saved filters
  (`SavedFilter.kt`).
- **Web:** one colour chip, one label chip and plain text search
  (`web/src/components/layout/FilterRow.tsx:160-190`; `NoteQueryFilters` in `web/src/types/note.ts:44-50`).
  Web has no query parser: a search for `label:work` on web looks for that literal text.
- **User impact:** a user who learned `is:pinned` on Android gets no results on web and no
  explanation. Saved filters and smart views don't exist on web.
- **Is it intentional?** No design note says so. It is drift between two codebases, not a platform
  adaptation.
- **Fix:** port `NoteQueryParser` / `NoteQueryMatcher` to TypeScript (or share golden test vectors, as
  the delete fingerprints already do), then add smart views to the web drawer. Short-term mitigation:
  when a web query contains a known prefix, show a SearchNotice saying query syntax is not available
  on web yet.
- **Regression tests:** shared golden vectors (query string → expected IDs) run by both test suites.
- **Effort:** large.

### NL-05 — P2 — Manual order can be seen but not used on web, and the default order differs by platform

- **Native:** defaults to `MANUAL` (`domain/model/NoteQuery.kt:66`), with drag plus keyboard and
  accessibility Move up / Move down (`strings.xml:438-439`, `NoteStaggeredGrid.kt:151`).
- **Web:** offers only Newest / Oldest and defaults to newest (`web/src/store/notesStore.ts:70`). This
  is deliberate (`SortSheet.tsx:19-27`, "F18"): there is no way to reorder.
- **User impact:** the same account appears in a different order on phone and browser, and a user who
  arranges notes on Android cannot see or keep that arrangement on web. *Confirmed hypothesis: manual
  sorting is neither discoverable nor operable on web.*
- **Fix:** add Move up / Move down to the web card's menu and selection bar (keyboard-operable and
  easier than drag), then offer Manual in `SortSheet`. Drag can come later.
- **Effort:** medium.

### NL-06 — P2 — The privacy policy has three diverging copies and omits Turnstile

- The web build loads Cloudflare Turnstile on sign-in (`web/public/_headers:7` allows
  `challenges.cloudflare.com` in `script-src` and `frame-src`; `web/src/components/auth/TurnstileWidget.tsx`).
  The policy names Cloudflare only for hosting and R2 (`PRIVACY_POLICY.md:82-86`) and never mentions a
  bot challenge, which processes browser signals.
- The web in-app copy is a separate, hand-maintained string, "Last updated: September 2026"
  (`web/src/components/settings/PrivacyPolicyDialog.tsx:54`). It has no account-deletion section;
  `PRIVACY_POLICY.md` is dated October 2026 and has one (line 38). The native in-app copy is a third,
  shorter summary (`strings.xml:273`).
- "Contact … through the store listing" (`PRIVACY_POLICY.md:114`, `PrivacyPolicyDialog.tsx:52`): the
  app is on no store.
- **Fix:** disclose Turnstile (purpose, provider, when it loads). Generate the in-app copies from
  `PRIVACY_POLICY.md` at build time, or add a CI check that their section headings and dates match.
  Replace "store listing" with the repository's issues page or a contact address.
- **Effort:** small (copy), medium (generation or check).

### NL-07 — P3 — Web: the recent-searches feature never records anything

- `addRecentSearch` is called only when the user clicks an existing recent-search entry
  (`web/src/screens/MainScreen.tsx:437-442` and `:552-556`). Nothing records a search the user typed,
  so the list starts empty and stays empty, and the dropdown (`TopBar.tsx:112`) and the empty-state
  suggestions never appear. Native records a search when the keyboard's Search key is pressed.
  Legacy entries from older builds stay in `notelikeus-ui`, which sign-out does not clear
  (`bootstrap.ts:58-63`, `uiStore.ts:118-121`).
- **Fix:** record on Enter in the search field, as native does, and clear `recentSearches` in
  `clearLocalUserData`; or remove the feature from web.
- **Effort:** small.

### NL-08 — P3 — Web: the active search is kept in plain `localStorage` across reloads

- `partialize: (state) => ({ filters: state.filters })` stores `searchQuery`, `labelName` and
  `colorArgb` (`web/src/store/notesStore.ts:186-190`). A reload reopens a filtered list, and the
  search text sits outside the AES-GCM sealing the policy describes for note content. Sign-out resets
  it (`bootstrap.ts:196`). Native does not persist the active query.
- **Fix:** leave `searchQuery` out of `partialize`. Persisting the view and sort is fine.
- **Effort:** small.

### NL-09 — P3 — The same note colour has different names on each platform

- Web: `Default, Coral, Orange, Amber, …` (`web/src/theme/colors.ts:44-54`). Native: `No color, Red,
  Orange, Yellow, …` (`NoteColorNames.kt:31-41`, `strings.xml:284-286`). The ARGB values are identical
  (`Color.kt:82,91` against `colors.ts:15`). Web is not consistent with itself either: the fallback
  label is "No color" (`ColorSwatch.tsx:31`), while the named list says "Default".
- **Impact:** screen-reader announcements, documentation and native `color:` queries use different
  words for the same swatch. *Confirmed hypothesis, but limited to names: colours mean the same thing
  on every platform.*
- **Fix:** one canonical list of names. Native's is the better choice because `color:red` already
  parses there.
- **Effort:** small.

### NL-10 — P3 — A note with no stored colour gets different fallbacks

- When a cloud row or backup has no `color`, web uses `0xff1a1a1a` (`web/src/types/note.ts:66`,
  `lib/mappers/noteCloudMapper.ts:154`, `lib/supabase/supabaseNoteMapper.ts:67`), while native uses `0`,
  no colour (`data/remote/SupabaseNoteTransport.kt:550`). `0xff1a1a1a` is not in either palette, so web
  draws a near-black card even in the light theme.
- **Fix:** use `0` on web as well. Only affects payloads that leave the field out.
- **Effort:** trivial.

### NL-11 — P3 — Moving to Trash: native asks first, web doesn't, and native calls it "Delete"

- Native, selection → trash: opens "Delete note?" / "Selected notes will be moved to trash"
  (`MainScaffold.kt:155-158`, `MainDialogs.kt:115-127`, `strings.xml:75,77`), and then *also* offers
  Undo. Web trashes at once with an Undo toast (`web/src/screens/main/useNoteActions.ts:228`).
  Archiving is immediate with Undo on both.
- **Fix:** on native, drop the confirmation for a move to Trash and keep Undo. Use "Delete" only for
  permanent deletion.
- **Effort:** small.

### NL-12 — P3 — Strings that can't be translated in native chrome

- `CloudSyncController.kt:227` ("Couldn't check for changes that haven't synced…") and the tray menu
  (`main.kt:198,204`, "New Note" and "Exit"; the title-case "New Note" also differs from "New note"
  elsewhere) bypass `strings.xml`.
- **Effort:** trivial.

### Hypotheses from the brief (§7) — verdicts

| Hypothesis | Verdict |
|---|---|
| Manual sort discoverable and operable on web | **Confirmed gap**: not offered, by design (NL-05) |
| Smart views and saved filters have parity | **Confirmed gap**: native only (NL-04) |
| Recent or active searches stored inappropriately | **Confirmed**: native keeps them across accounts (NL-02); web keeps the active query in plain text (NL-08); web recent searches are inert (NL-07) |
| Privacy policy matches backup and network behaviour | Backups: **matches** (export is offline; `.nlkbak` is described as a plain ZIP). Network: **Turnstile missing**. Sign-out: **wrong for web** (NL-03, NL-06) |
| Note colours mean the same on every platform | Same values, **different names** (NL-09); fallback differs (NL-10) |
| Auth, guest and sign-out messages are accurate | **Partly inaccurate** (NL-03) |
| Reminder limits are communicated | Web: yes. **Windows: no** (NL-01) |
| Web touch targets are comfortable | Covered by the 2026-11-06 matrix (passed); not re-checked |
| Keyboard shortcuts follow platform conventions | **Yes.** Native uses Ctrl+N, Ctrl+F/K, Ctrl+, and Delete. Web uses `/`, plain `N` (Ctrl+N belongs to the browser), and Ctrl+F only while the editor is closed (`MainScreen.tsx:324-334`). Gap: neither has a shortcut help sheet |
| Filters and editor state persist predictably | Web persists the search across reloads, native does not (NL-08); otherwise consistent |
| Backup and destructive dialogs describe consequences accurately | Backup copy: accurate. Delete-cloud-data copy: accurate. Sign-out: no (NL-03) |

---

## D. Cross-platform parity matrix

| Capability | Android | Windows | Web | Intentional? |
|---|---|---|---|---|
| Guest / local-only use | ✓ | ✓ | ✓ | — |
| Manual order | ✓ | ✓ | ✗ | Documented (F18), but a gap |
| Default sort | Manual | Manual | Newest | No |
| Advanced filters, query syntax, smart views, saved filters | ✓ | ✓ | ✗ | No |
| Recent searches | ✓ | ✓ | inert | No |
| Reminder delivery | system alarms | only while running | best effort, disclosed | Windows not disclosed |
| Sign-out: local notes | deleted | deleted | kept, sealed | No |
| Sign-out: unsynced edits | confirm, then lost | confirm, then lost | kept | No |
| Confirm before Trash | yes, plus Undo | yes, plus Undo | Undo only | No |
| Colour names | Red / Yellow / No color | same | Coral / Amber / Default | No |
| Separate editor windows | — | ✓ | float / dock / fullscreen | Yes (native adaptation) |
| Browser Back / system Back closes the editor | ✓ predictive Back | Esc | ✓ (UX-08 fix) | Yes |
| Shortcuts | — | Ctrl-based | `/`, `N`, Ctrl+K | Yes (browser keeps Ctrl+N) |
| Widget, share target | ✓ | — | — | Yes |
| Link insertion UI | Markdown | Markdown | dialog | Acceptable |

---

## E. Design-system assessment

**Keep:** the shared colour tokens (identical ARGB pairs for light and dark in `ui/theme/Color.kt`
and `web/src/theme/colors.ts`), the "no colour = 0" sentinel with the legacy mapping, spacing, shape
and motion tokens in `ui/theme/`, web's shared `ModalDialog` / `ResponsiveSheet` scaffolds (after the
UX-02/05 fixes), and the documented premium system (`docs/PREMIUM_DESIGN_SYSTEM.md`).

**Change:**
1. **Content tokens, not just visual ones.** Colour names, sort labels, sign-out and reminder copy
   are defined separately in `strings.xml` and in TSX literals, and that is where the drift is
   (NL-03, NL-09, NL-12). A shared `contracts/` JSON of user-facing terms, checked in CI against both
   clients, would stop it. `contracts/` already exists for the sync contract.
2. **One query model.** See NL-04.
3. **A destructive-action rule:** reversible actions get Undo and no confirmation; irreversible ones
   get a confirmation and explicit wording. Native's trash confirmation breaks this rule (NL-11).

A redesign is not recommended.

---

## F. Interaction specification for core workflows (target)

| Workflow | Expected behaviour on every platform |
|---|---|
| Close the editor | Saves first. If the save fails, the editor stays open and says so. Back, Esc and window close all go through the same guard |
| An existing note emptied of all content | Saved as empty, as today; consider offering to move it to Trash |
| Trash, archive, pin (single or bulk) | Immediate, with an Undo snackbar or toast. No confirmation |
| Permanent delete, Empty trash, Delete cloud data, Delete account | Confirmation naming what is lost; recent sign-in where required |
| Search | Live filtering; Enter records a recent search; the same query syntax on every client; clearing the search leaves other filters alone |
| Sort | Manual / Newest / Oldest offered everywhere; Manual can be operated by keyboard |
| Reminder | Confirmation states the delivery guarantee for that platform |
| Sign-out | One model, described the same way in the dialog and the policy; clears every piece of per-account content, searches and saved filters included |
| Offline → online | A status banner or snackbar says what is waiting and when it uploads |

---

## G. Accessibility assessment

**Evidence that exists (from earlier passes):** web axe, keyboard, focus, contrast, touch-target and
320–1440 px reflow matrix (136 cases, 2026-11-06), and Pixel large-font smoke tests. **From this
pass's code reading:** native colour swatches have spoken names; swipe actions are also exposed as
`customActions` (`SwipeableNoteCard.kt:104-109`); reordering has Move up / Move down; web sort options
announce their state with `aria-pressed`.

**New issues:** NL-09 (inconsistent spoken colour names).

**Not tested, so no compliance claim is made:** TalkBack end to end, Narrator / NVDA on Windows (the
custom undecorated title bar is a risk area), VoiceOver on iOS Safari for the PWA, Windows at 200%
scaling and high-contrast mode, reduced motion on native, and 200% text zoom on web dialogs after the
UX-02 fix.

---

## H. Remediation roadmap

**Now (one release):** NL-01 (close to tray plus copy), NL-02, the copy part of NL-03, the copy
part of NL-06 (Turnstile, store listing), NL-10, NL-12.

**Next:** NL-05 (web Move up/down and Manual), NL-07, NL-08, NL-09, NL-11, and generating or checking
the policy copies (NL-06).

**Medium term:** NL-04 (porting the query system and smart views to web); deciding whether native
sign-out should keep unsynced work the way web does (the behaviour part of NL-03); Windows scheduled
toasts (NL-01 b); a shortcut help sheet on desktop and web.

---

## I. Regression test plan

| Area | Test |
|---|---|
| NL-01 | Desktop: with a future reminder, a close request does not exit, or shows the tray notice; desktop confirmation string |
| NL-02 | `CloudSyncController` sign-out → `recentSearches` and `savedFilters` empty; the account-switch path too |
| NL-03 | Web `SignOutDialog` copy snapshot; a policy test that the sign-out sentence matches the behaviour flag |
| NL-04 | Shared golden vectors (query → matching IDs) run by `commonTest` and Vitest |
| NL-05 | Web: Move up/down changes `position`, syncs, and the order matches native in a fixture |
| NL-06 | CI: in-app policy heading set and date equal `PRIVACY_POLICY.md` |
| NL-07/08 | Web: Enter records a recent search; sign-out clears it; `notelikeus-note-filters` never contains `searchQuery` |
| NL-09/10 | Shared colour-name table; mapper with `color` missing → `0` |
| NL-11 | Native: trash from selection shows no dialog and shows an Undo snackbar |
| Manual matrix | TalkBack, Narrator and NVDA passes; Windows at 200% and high contrast; PWA install and offline on Android Chrome and iOS Safari |

---

## J. Final recommendations

1. **Fix Windows reminders first.** It is the only finding where the app silently fails at a job it
   says it is doing.
2. **Make sign-out one documented contract** and correct the policy and dialogs to match. Clear search
   history and saved filters along with the notes.
3. **Treat the query model as shared product logic**, with golden tests across both codebases, and
   bring smart views and manual order to web.
4. **Keep user-facing terms in a single source** (colour names, policy text, destructive-action
   wording), checked in CI, so the two codebases stop drifting.
5. **Schedule the assistive-technology and Windows-scaling passes** listed in §G. Neither this audit
   nor the earlier ones can claim them.
