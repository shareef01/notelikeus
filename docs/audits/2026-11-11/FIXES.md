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

## Still unverified or not covered

- **NL-01 was checked on Windows 11 on 2026-11-12**, using the 2.6.2 MSI built from `main`. With no
  reminder pending, closing the main window quits. A second launch hands over to the running copy
  and exits. With a reminder pending, closing the main window hides it and the app keeps running in
  the tray. Exit in the tray menu quits. **Not confirmed:** that the reminder notification fires
  while the window is hidden, and that the one-time "still running" notice appears.
- **NL-03:** a sealed copy for an account that was deleted from another device while this one was
  signed out stays on this device, encrypted, because nobody can sign in to claim it. Web behaves
  the same way.
- **NL-03:** there is no end-to-end sign-out test on Android or Windows. The order of the steps is
  only tested on the parts that store and clear the copy.
- The checks this audit left for later are still to do: a visual review of every screen, a
  screen-reader pass, and Windows display-scaling and multi-monitor checks (audit §G and §I).
