# Web UI/UX audit

Audited revision: `cce1b922108e6b4e7cae3951d8ed1b47c127a8ba` (PR #441 merged), 6 November 2026, Europe/Berlin.

## Assessment

**12 reproducible findings: one P1, nine P2, and two P3.** The most urgent is a persisted-content loss caused by Undo after checklist conversion. The recent editor redesign and scrolling fixes work in their tested paths, but that does not establish that the rest of the product is finished.

P1 means ordinary use can lose content and should be fixed first. P2 means a material interaction, accessibility, navigation, or recovery problem. P3 means a smaller visual or feedback defect. Product opportunities are listed separately and are not counted as confirmed defects.

This was an audit, not a remediation release. Production UI code was not changed. New files are audit evidence, repeatable browser probes, and isolated component fixtures.

## Coverage and evidence

- The existing accessibility/reflow/contrast/touch matrix passed **136/136 cases** across Berlin/de-DE, Los Angeles/en-US, Kathmandu/ne-NP, and Cairo/ar-EG configurations. The application content remains English; this is not a translation or RTL certification.
- **40 additional whole-app surface states** were inspected and scanned at 1440×900 and 320×568: empty notes, populated notes, search/no results, Archive, Trash, labels, Settings, editor, note options, reminders, sort, selection, import preview, local-load failure, sync failure, unreadable-note warning, offline banner, diagnostics, privacy, and conflict review.
- **12 isolated account/settings/modal renders**, **four cloud-recovery banner renders**, and **two actual production sign-in validation renders** extend coverage beyond guest-only tests. Signed-in account/destructive states use the real components with synthetic data and no-op actions. Cloud recovery actions are intercepted in the fixture.
- **Two additional OTP-entry/error renders** used intercepted local auth responses: code-field autofocus, expired-code feedback, and the visible resend cooldown were inspected without sending any email.
- Targeted interaction probes cover links, checklist creation/conversion/Undo, persisted saves, label creation, browser Back, keyboard viewport occlusion, and card hover.
- **70 screenshots**, raw geometry, and accessibility results are in [evidence](evidence/), [validation.json](validation.json), and the `*-results.json` files.
- Production authentication was inspected without sending a code or creating an account: both invalid-email probes recorded zero `/auth/v1` POST requests.
- No real account deletion, cloud wipe, reupload, or email delivery was executed. All notes created for interaction tests belonged to disposable browser guest sessions.

### What the passing baseline does establish

The previously covered main screens reflow without document-level horizontal scrolling; basic dialog focus/Escape handling, ordinary palette contrast, and minimum target sizes pass in the tested matrix. Additional production email validation, ordinary guest Settings, import preview, error banners, and privacy screens had no detected axe violations in the inspected states.

### What it misses

A page can pass axe and have `scrollWidth === innerWidth` while a fixed dialog clips its controls or a child button sits outside the viewport. Undo semantics, browser history, pending-save truthfulness, and contextual link editing require interaction checks. Findings below include those checks rather than treating automated passes as a complete UX verdict.

## Prioritized findings

| ID | Priority | Finding | Evidence |
| --- | --- | --- | --- |
| UX-01 | P1 | Undo after checklist-to-text conversion saves an empty note | [Before](evidence/undo-before-persisted-checklist.png), [after reopening](evidence/undo-after-persisted-loss.png), [raw result](recovery-results.json) |
| UX-02 | P2 | Shared dialogs clip content on short/mobile viewports | [Delete account](evidence/delete-account-320x568.png), [conflict review](evidence/surface-conflict-320.png) |
| UX-03 | P2 | Editing an existing link inserts `link` into its label | [Screenshot](evidence/link-edit-inserts-text.png), [raw result](interaction-results.json) |
| UX-04 | P2 | Mobile label creation clips the Create button | [Screenshot](evidence/labels-mobile-create-clipped.png) |
| UX-05 | P2 | Link input remains behind the software-keyboard boundary | [Screenshot](evidence/link-keyboard-occlusion.png), [geometry](interaction-results.json) |
| UX-06 | P2 | Signed-in Settings actions fail light-theme contrast | [Screenshot](evidence/profile-1440x900.png), [axe results](fixture-results.json) |
| UX-07 | P2 | Adding a checklist row can focus a completed item instead | [Screenshot](evidence/checklist-enter-appends.png), [raw result](interaction-results.json) |
| UX-08 | P2 | Browser Back leaves the app instead of closing an open note | [Navigation result](interaction-results.json) |
| UX-09 | P2 | Save indicator claims Saved while the draft is dirty | [Screenshot](evidence/saved-label-while-dirty.png), [raw result](recovery-results.json) |
| UX-10 | P3 | Duplicate-label creation silently clears the input | [Raw result](interaction-results.json) |
| UX-11 | P3 | Hover selection control overlaps the card title area | [Screenshot](evidence/card-hover-selection-overlap.png), [geometry](recovery-results.json) |
| UX-12 | P2 | Cloud-deletion recovery notice is unreadable in light theme and breaks down on narrow screens | [Light mobile](evidence/cloud-wipe-light-320.png), [dark mobile](evidence/cloud-wipe-dark-320.png), [axe/geometry](cloud-wipe-results.json) |

### UX-01 — Undo after conversion destroys saved checklist content

**Reproduction:** Create a guest note with a title and two checklist items; check one item. Close the note through Back and reopen it, establishing a real persisted checklist. Choose **Convert to text**, focus the body, press Ctrl+Z, close through Back, and reopen.

**Observed:** The reopened body is blank (a newline), and there are no structured checklist rows. The previous text and checked state are not restored. This was verified with actual UI-created/persisted guest data, not just an in-memory seeded fixture.

**Cause/location:** `web/src/hooks/useRichNoteEditor.ts:50` replaces content with `emitUpdate: false` but still permits that replacement into editor history. `web/src/hooks/useNoteEditor.ts:478` independently discards the structured checklist during conversion. Tiptap can undo the replacement to its earlier empty document, but cannot restore checklist state.

**Fix:** Give structural conversions a complete draft-state undo boundary, including item IDs/order/checked state; exclude external document hydration from ordinary typing history. Do not use the empty hidden editor document as the previous state for conversion. A visible conversion Undo action can complement, but must not contradict, Ctrl+Z.

**Acceptance:** The exact conversion → Undo → save → reload sequence preserves every original item and checked state. Ordinary text Undo/Redo, remote hydration, and route changes must not restore unrelated/empty content.

### UX-02 — Short dialogs lose their title and warning context

**Reproduction:** At 320×568 open Delete account in the isolated real-component fixture and request the incomplete backup outcome. Also open ordinary conflict review in the actual guest UI. At 720×450 inspect the same delete dialog (a reduced effective viewport useful for zoom/short-window checks).

**Observed:** Delete-account panel spans **y=-132 to 552** at 320×568; its title and beginning of the warning are above the viewport. Conflict review spans **y=-142 to 552**. At 720×450 the delete panel extends from **-47 to 497**. The page is scroll-locked and the shared panel has `overflow: visible`, so the missing area cannot be reached by normal scrolling. Axe did not flag this clipping.

**Location:** `web/src/components/ui/ModalDialog.tsx:49` and its panel. The shared scaffold lacks a viewport-constrained maximum height and scrolling content region.

**Fix:** Bound dialogs to the available viewport with safe-area spacing; make their content scroll independently and keep critical heading/action context reachable. Check both ordinary and expanded warning/error states.

**Acceptance:** All content and actions remain reachable at 320×568, 720×450, landscape sizes, and with enlarged text. Recheck conflict review, import preview, account deletion, and confirmations using the common component.

### UX-03 — Existing-link editing changes words instead of updating the URL

**Reproduction:** Open `Read [the guide](https://example.com/old).`, place the caret inside the link, choose Link, and enter `https://example.com/new`.

**Observed:** The URL field starts empty. The body becomes **`Read the glinkuide.`**. There are three anchors: `the g` pointing to the old URL, `link` to the new URL, and `uide` to the old URL.

**Location:** `web/src/components/editor/LinkDialog.tsx:24`; `web/src/screens/EditorScreen.tsx:809` treats a collapsed caret as an insertion even when it lies in an existing link.

**Fix:** Detect/extend the active link range, prefill its URL, and provide Update/Remove actions. Insertion without selected text should use an explicit label field or a clearly selected placeholder.

**Acceptance:** Changing the URL at any caret position in a link preserves its label and produces one anchor. Selected-text insertion and Cancel continue to preserve the original selection.

### UX-04 — Create label action falls outside a 320px viewport

**Reproduction:** On a 320×568 viewport open Edit labels and type `Travel` into Create new label.

**Observed:** The Create button begins at **x=291**, is **60.875px** wide, and ends around **352px**. Its label/control are clipped even though the document reports a width of 320px. The input's intrinsic width, leading icon, gaps, and newly appearing action do not fit.

**Location:** `web/src/screens/LabelsScreen.tsx:82` and the containing form. The flexible input lacks `min-w-0`/a constrained width.

**Fix:** Allow the input to shrink and keep the action within the form; optionally stack the action on very narrow screens.

**Acceptance:** Creating, editing, and submitting labels works at 320px with short/long text, with the entire Create action visible and tappable.

### UX-05 — Link dialog does not follow the keyboard-visible viewport

**Reproduction:** At 390×844 open Add link, then simulate a visual-viewport height of 420px while keeping the layout viewport unchanged, matching the class of keyboard resize handled by the editor.

**Observed:** The input remains at about **y=706**, and the dialog at **642–828**, entirely below the simulated visible boundary. This is a viewport simulation, not a claim of physical-device certification.

**Location:** `web/src/components/editor/LinkDialog.tsx:28` through the fixed shared wrapper at `web/src/components/ui/ModalDialog.tsx:49`. The editor's keyboard-aware scrollport does not constrain sibling/nested modal layouts.

**Fix:** Position and bound input dialogs within the visual viewport; include keyboard occlusion, safe areas, and focus/scroll restoration. Check reminder and label-related input overlays too.

**Acceptance:** Input, validation, Cancel, and confirmation stay visible while typing with a real Android/iOS keyboard. Repeat the visual-viewport probe and test keyboard dismissal without jumping the underlying editor.

### UX-06 — Account actions have insufficient contrast in light Settings

**Reproduction:** Render the signed-in ProfileSheet in light theme.

**Observed:** **Sign out** and **Delete account** use `text-red-300`; axe measured **1.8:1** against the light row background for 15px text. They fail the 4.5:1 normal-text threshold. Guest-only scans do not render these rows.

**Location:** `web/src/components/settings/ProfileSheet.tsx:83`.

**Fix:** Use separate light/dark destructive text tokens, as other destructive dialogs already do. Retain a readable normal state, not only a hover state.

**Acceptance:** Both labels clear 4.5:1 in light, dark, AMOLED, and supported accent themes, with signed-in fixture coverage in the audit matrix.

### UX-07 — New checklist item receives the wrong focus when completed rows exist

**Reproduction:** Open a checklist with `First task` checked and `Second task`/`Third task` unchecked. Press Enter in the first displayed unchecked row.

**Observed:** Display order becomes `Second task`, `Third task`, a new blank row, `First task`; focus goes to index **3**, the completed `First task`, rather than the new blank row at index **2**.

**Location:** `web/src/components/editor/ChecklistEditor.tsx:29` selects `sorted[sorted.length - 1]` for the generic `new` focus request. `web/src/hooks/useNoteEditor.ts:450` creates the new unchecked item, which is sorted before completed items.

**Fix:** Focus the exact created item ID, not the last sorted row. Consider insertion after the current row for Enter rather than unconditional append.

**Acceptance:** Enter/Add always focuses the new blank item with mixed completed/uncompleted rows, and subsequent typing cannot modify an unrelated completed task.

### UX-08 — Browser Back has no in-app editor navigation state

**Reproduction:** Open the app in a new tab, enter guest mode, open a note, and invoke browser Back.

**Observed:** Navigation returns to **about:blank** in the test history instead of closing the note to the list. In a normal browsing history this can take the user to the preceding site. The in-app Back control works; the browser/mobile navigation affordance is not integrated.

**Location:** `web/src/store/uiStore.ts:83` changes editor state without a corresponding history entry/popstate handler. Existing history calls are auth-callback cleanup, not editor navigation.

**Fix:** Define history behavior for note/editor and transient overlays; integrate popstate with the same save/failure protections as in-app Back.

**Acceptance:** Browser/Android Back first dismisses the active appropriate overlay/editor, preserves drafts on failed save, and does not create loops on Forward, deep links, or auth return.

### UX-09 — Footer still claims Saved while current edits await autosave

**Reproduction:** Save and reopen a normal note. Hold the browser timer clock, change its body, and inspect the footer before the pending autosave timer runs.

**Observed:** The body contains changed text while the footer still says **Saved on this device** with the prior save time. In ordinary operation this occurs during the **1,000ms** debounce and can continue while typing repeatedly resets the timer. The timer-controlled probe establishes the exact pending-save state; it is not an artificial I/O-failure claim.

**Location:** `web/src/hooks/useNoteEditor.ts:374` updates the draft and schedules save without clearing the prior saved indication; `web/src/screens/EditorScreen.tsx:772` relies on `lastSavedAt != null` for current durability.

**Fix:** Track current draft dirty/revision state separately from last successful save. Show an accurate pending state immediately, then Saved/Synced only for the revision actually persisted/confirmed.

**Acceptance:** Slow/continuous typing, delayed writes, retries, and failed saves never label a newer unsaved revision Saved or Synced. Merely focusing/formatting a caret still must not update note dates.

### UX-10 — Duplicate label attempts provide no feedback

**Reproduction:** Create `Travel`, then attempt to create `Travel` again.

**Observed:** One label remains, the field is cleared, and no alert/status explains that the label already exists. The user cannot distinguish success, deduplication, and failure.

**Location:** `web/src/screens/LabelsScreen.tsx:31`; `web/src/store/labelRegistryStore.ts:22` returns the unchanged registry for an existing ID.

**Fix:** Return/display a duplicate result, retain or focus the existing label, and use consistent name normalization for create and rename.

**Acceptance:** Exact and normalized duplicates have clear feedback; renaming to an existing name follows an explicit policy and does not leave indistinguishable duplicate entries.

### UX-11 — Hover checkbox encroaches on the first title characters

**Reproduction:** On desktop hover a plain-text card without a thumbnail or reorder handle.

**Observed:** The selection checkbox rectangle overlaps the title rectangle by **196px²** in the captured fixture, covering the first-character region. The clearance indentation only applies to coarse pointers, while hover makes the same control visible on fine pointers.

**Location:** `web/src/components/notes/NoteCard.tsx:133` and its absolutely positioned selection control.

**Fix:** Reserve a selection-control slot for every state where it becomes visible, or position it outside the title's painted area. Avoid shifting text on hover.

**Acceptance:** Hover/focus/selection never obscure title/body text across densities, pointer types, thumbnails, and reorder modes.

### UX-12 — Cloud-deletion recovery warning is hard to read and collapses on mobile

**Reproduction:** Render CloudWipeNotice with three retained local notes, in light/dark themes at 320px and 1440px. Do not click the upload/delete-related actions.

**Observed:** In light theme axe measured approximately **1.34–1.50:1** for warning text/buttons. At 320px, the non-shrinking horizontal action group leaves the message column only **18.39px** wide; its heading and explanation become a long, narrow stack even in dark theme. This warning communicates a consequential cloud-recovery decision, so legibility matters.

**Location:** `web/src/components/notes/CloudWipeNotice.tsx:44` uses dark-oriented amber text/background without light variants; line 62 keeps both actions in a fixed horizontal group.

**Fix:** Use the shared light/dark warning tokens and stack/wrap actions under the explanation at narrow widths. Preserve the explicit choice about reuploading.

**Acceptance:** All warning/action text clears 4.5:1, the explanation has a readable width, and both choices remain reachable at 320px and enlarged text. Validate real cross-device recovery separately before declaring the cloud workflow verified.

## Additional design/product opportunities

These are recommendations, not additional confirmed defects:

1. **Make Undo/Redo visible on mobile.** A keyboard-only recovery affordance is hard to discover on a touch editor. Resolve UX-01's semantics before exposing it more prominently.
2. **Replace the Settings default-view cycling row with a choice.** Sort already offers explicit choices; view mode still requires cycling through hidden destinations despite ample Settings space.
3. **Revisit guest startup friction.** Every reload returns a guest to the auth gate even though their local notes remain. This is intentional current behavior, not observed data loss; consider remembering the local-only choice with a clear privacy explanation.
4. **Improve navigation context and shortcut discovery.** Consider returning to Settings after Privacy policy, provide a shortcut reference, and show platform-appropriate Cmd/Ctrl keycaps instead of fixed `Ctrl K`.

The neutral editor redesign is visually more coherent than the earlier saturated pane. The next design work should focus on the broader product's contextual controls, recovery feedback, and small-screen states rather than another color-only refresh.

## Recommended implementation order

1. Fix **UX-01** and add persisted-state conversion/Undo regressions before other editor enhancements.
2. Fix the shared dialog viewport constraints (**UX-02/05**) and the narrow label form (**UX-04**).
3. Fix signed-in recovery/readability (**UX-06/12**), then contextual link behavior (**UX-03**).
4. Correct checklist focus and durability feedback (**UX-07/09**), then browser history (**UX-08**).
5. Finish duplicate feedback/card positioning (**UX-10/11**) and evaluate the product opportunities.

Each batch should get focused acceptance tests plus the relevant current matrix. A blanket axe pass is insufficient evidence for these interaction defects.

## Verification limits

- Chromium desktop/mobile emulation and visual-viewport keyboard simulation were used. Real Android/iOS keyboards, address-bar dynamics, installed-PWA back gestures, Safari/WebKit, and Firefox still require device/browser checks.
- No TalkBack, VoiceOver, NVDA, switch-control, or participant usability study was performed. Focus/ARIA/axe checks are not a screen-reader certification.
- Signed-in account/deletion and cloud-wipe warning visuals use isolated real-component fixtures. Email delivery, CAPTCHA completion, successful real OTP verification/resend (only mocked entry/error states were rendered), real account switching, real cross-device sync/conflicts/wipe recovery, and hosted service failures were not end-to-end exercised.
- Attachments and backup transfer code/current automated journeys were reviewed; this pass inspected import preview and relevant UI but did not perform real cloud-image transfer or a production restore. Large device datasets, OS notification delivery, storage eviction/pressure, and install prompts retain their separate verification needs.
- Short effective viewports represent important zoom/reflow constraints, but this is not a full browser-zoom/text-only-resize certification.
- English UI was inspected under different locale/time-zone environments; translations and RTL content were not tested.
- Findings apply to the pinned revision. This is a broad current audit, not a guarantee that every possible state is defect-free.

## Reproducing the evidence

From repository root, install the existing web dependencies if needed. Run the baseline with `npm run test:audit-ui` in `web/`. Then start the isolated local audit server:

```powershell
cd web
npx vite --mode e2e --host 127.0.0.1 --port 4174
```

In another shell at repository root:

```powershell
node docs/audits/2026-11-06/web-ui-ux/probes/fixture.cjs
node docs/audits/2026-11-06/web-ui-ux/probes/interaction.cjs
node docs/audits/2026-11-06/web-ui-ux/probes/recovery.cjs
node docs/audits/2026-11-06/web-ui-ux/probes/cloud.cjs
node docs/audits/2026-11-06/web-ui-ux/probes/surface.cjs
node docs/audits/2026-11-06/web-ui-ux/probes/auth.cjs
node docs/audits/2026-11-06/web-ui-ux/probes/auth-code.cjs
```

The first five use disposable local sessions/component fixtures. `auth-code.cjs` intercepts every auth request and renders OTP/error states without contacting the mail service. `auth.cjs` reads the production entry UI and submits only an invalid email, which is rejected locally; it must not be repurposed to send real codes without authorization. Fixture entry: `web/e2e/fixtures/ui-ux-audit/index.html`. Account actions are no-op handlers; the cloud-warning fixture blocks clicks before they reach recovery handlers. The probes collect findings rather than passing/failing the application as a normal test suite. JSON and screenshots are overwritten on rerun, so keep the pinned baseline when comparing fixes.

## Accessibility references

Normal text contrast is assessed against [WCAG 2.2 SC 1.4.3](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html). Clipped narrow layouts are assessed using [SC 1.4.10 Reflow](https://www.w3.org/WAI/WCAG22/Understanding/reflow.html). Silent rejected input relates to [SC 3.3.1 Error Identification](https://www.w3.org/WAI/WCAG22/Understanding/error-identification.html). These references support the criteria; the screenshots/probes support the product-specific findings.
