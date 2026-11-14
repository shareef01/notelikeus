# UI/UX audit follow-up — 2026-11-11

All twelve findings in the [audit](UI_UX_AUDIT.md) are fixed on `main`. The audit text itself is kept
as written, so it still describes revision `8d52068c`; this file records what changed since and
what is still unverified.

| Finding | PR | What changed |
| --- | --- | --- |
| NL-01 | [#463](https://github.com/shareef01/notelikeus/pull/463) | Windows: while a reminder is pending, closing the window hides the app to the tray, with a one-time notice. Exit in the tray menu quits. A second launch brings the running copy forward (`util/SingleInstance.kt`), and hiding the window re-locks app lock. |
| NL-02 | [#464](https://github.com/shareef01/notelikeus/pull/464) | Native: sign-out and account switch clear recent searches and saved filters (`SettingsRepository.clearAccountSearchHistory`). Covered by `AccountSearchHistoryClearTest`. |
| NL-03 | [#465](https://github.com/shareef01/notelikeus/pull/465), [#466](https://github.com/shareef01/notelikeus/pull/466) | #465 corrects the sign-out copy on each platform and in both privacy policy copies: web keeps an encrypted copy for the account, and nobody is told they must sign in again. #466 changes native to match web: sign-out seals the account's whole library and sync state in the encrypted database (`SignedOutAccountVault`, schema 12) instead of deleting unsynced edits, and signing back in restores it. |
| NL-04 | [#467](https://github.com/shareef01/notelikeus/pull/467), [#473](https://github.com/shareef01/notelikeus/pull/473) | Web: #467 ports `NoteQueryParser` (search operators such as `label:`, `is:` and `before:`) and adds the smart views. Unrecognised operators are listed under the search box. Both parsers read `contracts/query/parser-vectors.json`. #473 adds the Filters sheet and saved filters, with web filter state modelled on native's `NoteQuery`. |
| NL-05 | [#471](https://github.com/shareef01/notelikeus/pull/471) | Web: Manual sort is offered and is the default on a first run, as on native. Move up and Move down are on the card toolbar, the selection bar and the list's reorder handle, and each move is announced. Reordering is blocked under the same conditions as native's `allowsManualReorder`. |
| NL-06 | [#470](https://github.com/shareef01/notelikeus/pull/470) | `PRIVACY_POLICY.md`, the web dialog and the native summary disclose Cloudflare Turnstile, and none of them mentions a store listing any more. The web dialog has every section of the policy, with the same date. `PrivacyPolicyDialog.test.tsx` fails if the two copies drift apart again. |
| NL-07, NL-08 | [#468](https://github.com/shareef01/notelikeus/pull/468) | Web: pressing Enter already recorded the search; that now has a test. Sign-out, account switch and guest mode clear recent searches. The active search, label and colour are no longer saved to `localStorage`; only the view and sort are. |
| NL-09, NL-10 | [#469](https://github.com/shareef01/notelikeus/pull/469) | One set of colour names (native's) on every platform, checked against `contracts/design/note-colors.json` by both clients. A missing colour now means no colour (`0`) on web, and a native backup missing a colour still imports. |
| NL-11, NL-12 | [#472](https://github.com/shareef01/notelikeus/pull/472) | Native: moving to Trash no longer asks first and offers Undo, as on web. Deleting from Trash is permanent and still asks. The remaining hard-coded interface text in native chrome now comes from `strings.xml`. |

## Unfinished work

- [ ] **Windows notifications (NL-01).** Observe the reminder banner while the main window is
  hidden. Verify the “still running” notice appears on the first close to the tray and does not
  repeat on the second close in the same session.
- [ ] **Native Windows scaling.** Test the main window and floating editor at the OS's exact 200%
  scale. This PC requires custom scaling and a Windows sign-out to apply it.
- [ ] **Multiple monitors.** With a second active display, verify that the main window and editor
  remain reachable when moved between displays and when the app restores a saved position.
- [ ] **Full visual review and Windows contrast.** Finish the screen-by-screen review. Extend
  native contrast-theme coverage beyond the sign-in and empty Notes screens, and review the
  observed gap in how the app follows the Windows contrast palette.
- [ ] **Screen readers.** Verify spoken labels and focus navigation with Android TalkBack and
  Windows Narrator or NVDA. Both checks are deferred at the owner's request.
- [ ] **Remote account deletion (NL-03).** Resolve what happens to encrypted local copies on
  devices that were signed out when their account was deleted elsewhere.

## Verification notes

- **NL-01 was checked on Windows 11 on 2026-11-12**, using the 2.6.2 MSI built from `main`. With no
  reminder pending, closing the main window quits. A second launch hands over to the running copy
  and exits. With a reminder pending, closing the main window hides it and the app keeps running in
  the tray. Exit in the tray menu quits. **Not confirmed:** that the reminder notification fires
  while the window is hidden, and that the one-time "still running" notice appears.
  On 2026-11-13, the Windows build from `41e99f03` stayed running with its main window hidden
  and an offline reminder pending. After the reminder came due, a second launch restored the same
  window, and closing it exited the app. `DesktopReminderManagerTrayTest` verifies that the timer
  requests a tray notification and clears the pending reminder. `MainWindowControllerTest`
  verifies that the controller requests the tray notice once.
- **NL-03:** a sealed copy for an account that was deleted from another device while this one was
  signed out stays on this device, encrypted, because nobody can sign in to claim it. Web behaves
  the same way.
- **NL-03:** Android and desktop now have sync-manager integration tests
  ([#479](https://github.com/shareef01/notelikeus/pull/479) and
  [#475](https://github.com/shareef01/notelikeus/pull/475)). Both exercise sealing and restoring an
  unsynced note, aborting sign-out when sealing fails, and keeping the active library when the
  session fails to end. The desktop test uses a real database. The Windows hosted UI check passed
  in an isolated profile. With HTTP and HTTPS blocked for the test process, the editor saved an
  edit locally and sign-out warned that changes were pending. After relaunching with network
  access and signing back into the same account, the exact edit reappeared and synced to the Pixel.
  The hosted Android UI check also passed on a Pixel 7 using a separate build from `41e99f03`.
  With networking blocked for the test package, sign-out reported one unsynced note. After
  networking was restored and the same account signed back in, the note reappeared and the editor
  showed it as synced.
- The checks this audit left for later are still to do: a visual review of every screen, a
  screen-reader pass, and Windows display-scaling and multi-monitor checks (audit §G and §I).
  The web guest accessibility matrix (`npm run test:audit-ui`) passed all 136 cases on
  `41e99f03` on 2026-11-13, covering dialog accessibility, reflow, contrast and touch targets.
  A follow-up run passed 20 narrow-layout fixture checks and 35 guest checks for entry, notes,
  editor, labels and sorting. The editor layout keyboard check ran on desktop only because the
  layout controls are hidden on phones.
  Visual inspection at 320 px exposed clipped theme and accent controls in web Settings. The
  settings columns now shrink to fit, and the narrow-layout fixture checks that the last theme
  control stays within the viewport. All 20 fixture checks passed after the fix.
  With Chromium forced colors enabled, the theme options now show a keyboard focus outline.
  The conflict dialog's “Keep both” action remained reachable at 200% root text size in a
  720 × 450 viewport. With these checks added, all 24 fixture cases passed.
  At 2× JVM scaling on Windows, the sign-in window originally opened with its title bar outside
  the visible area. The rebuilt main and floating editor windows fit the display work area and
  their title bars were visible. New notes in short floating windows now open without body
  autofocus. At 2× JVM scaling the title, formatting toolbar and body were visible on opening.
  Windows offered up to 175% in its standard scale menu. Custom 200% requires a Windows
  sign-out and still needs coverage. Only one display was active. With Windows Night sky applied,
  the native sign-in and empty Notes screens remained usable in the app's light palette but did
  not adopt the Windows contrast colors. The original Windows theme was restored.
  All 19 Chromium board and editor layout checks passed, including image previews and widths
  from 320 to 2560 px.
  The rebuilt Windows runtime contains Java Access Bridge. The Windows UI Automation inspection
  still exposed only the window panes, so a screen-reader pass remains open. The Pixel editor
  exposed its text fields and action labels through Android accessibility inspection. After adding
  persistent Title and Note labels, the Pixel 7 exposed both as child nodes of their editable
  fields when both fields contained text. The field nodes had no description of their own.
  Spoken output and focus navigation with TalkBack and Windows screen readers were deferred
  at the owner's request.
