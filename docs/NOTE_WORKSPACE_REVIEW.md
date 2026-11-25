# Notelikeus note workspace review

This follow-up to the [November UI/UX audit](UI_UX_AUDIT_2026.md) starts from `24d8f7a7`.
It covers Rust/Slint on Windows, React/TipTap on the web, and Jetpack Compose on Android.

## Verified findings

| Priority | Platform and entry point | Root cause | Correction |
| --- | --- | --- | --- |
| P0 | Windows back, note switching, archive/trash, conversion, attachment add and quit | The controller continued after a failed local write. Closing the editor cleared the dirty draft even though the store had rolled back the write. | Controller transitions that replace a draft check the save result. The failure stays visible next to the draft. The regression test blocks the guest store's save destination, retries after it is writable, and reopens the saved note. |
| P0 | Web docked editor and note switching | The dock and overlay each mounted an editor. A layout change could dispose of a live draft, and library navigation relied on the unmount callback to finish saving. | The editor instance survives presentation and viewport changes. Switching notes waits for local persistence and leaves the editor in place on a failed write. |
| P1 | Windows opening a note | A centered sheet consumed window space, placed cropped thumbnails above the title, and showed raw Markdown during reading. | A full-window workspace with a 720px reading measure opens existing text notes in reading mode. Edit uses the same Markdown buffer and selection. Attachments sit below the body, fit within their frames, and open a larger keyboard-operable viewer. |
| P1 | Android note images | `ContentScale.Crop` cropped the image to a fixed-height area. Decode sampling used only width, so a narrow portrait could still produce an unnecessarily large bitmap. JPEG previews displayed the stored pixel orientation. | Fit the whole image, place it near the end of the text, keep removal in the viewer, bound the preview's longest edge to 1280px and apply JPEG orientation metadata. Decoder regressions cover an 800×4000 portrait and a rotated JPEG. |
| P1 | Web note images | The editor exposed removal on every image and offered no larger view. | Open attachments in a contained viewer with Escape, focus return and previous/next controls. Removal requires deliberate confirmation. |
| P1 | Settings on all three clients | Windows used a narrow dialog. Android used a bottom sheet. Web used a full-viewport dialog with a focus trap and no route. | Windows opens an application workspace. Android uses an in-app page with a top app bar and system Back. Web exposes `/settings` with browser history and a named main landmark. |
| P2 | Coloured note reading | Windows and Android tinted the whole writing surface while the web used the theme surface. | All three active editors use neutral theme surfaces and a note-colour accent. Library cards keep the tint used to identify notes while scanning. |
| P2 | Android system bars in Settings and the editor | Edge-to-edge icon appearance followed system theme even after the in-app theme changed. The emulator showed dark status icons on the dark page. | The activity applies icon appearance from the selected theme, including System. The dark Settings screenshot confirms light icons. |

## User journeys and design

| Journey | Windows | Web | Android |
| --- | --- | --- | --- |
| Start, search and organise | Existing sidebar, smart views and search. Back from a note returns to the same library. | Search, labels and filters stay underneath the editor or Settings. | Existing adaptive library, filters and list position. Phone Back returns from the editor or Settings. |
| Create, read and edit | New notes open ready for text entry. Existing text opens in Read with an Edit button. Checklists use the native item editor. | Full-screen is the default for fresh preferences. Dock and float remain available. TipTap owns live formatting and the app retains the draft while changing layouts. | Single scroll column with existing title/body type roles, formatted text, IME insets and the existing adaptive detail pane on larger screens. |
| Add and view images | File picker, fitted gallery and full-window viewer. Larger renditions use the bounded Windows decoder and the existing encrypted cache. | Picker, paste and drop feed the same local staging path. The viewer reuses the preview URL and shows a matching aspect ratio. | Android picker, fitted previews after the body, and a full-screen viewer that closes on system Back. |
| Note colours and themes | The editor accent follows the selected note colour. Settings use the existing theme and contrast roles. | Note colour stays in the editor accent. Settings expose the existing device preferences. | The title divider reflects the note colour. Settings use the existing DataStore-backed preferences. |
| Offline work, sync and backups | Controller save checks precede draft replacement. The sealed store, sync revisions and backup bundle handlers remain the persistence boundary. | The route and viewer share the existing IndexedDB, staged-blob and local-save paths. Backup and account actions keep their existing confirmation dialogs. | Editor Back awaits local save. Room, DataStore and the existing backup and attachment flows continue to supply the page. |

The reading surface uses the existing semantic editor title, body, metadata and theme roles.
The width cap keeps desktop text readable and the phone canvas follows the available width.
Settings groups expose existing account/sync, appearance, layout, backup and information
actions. Scoped confirmations remain dialogs.

Images are ordered note attachments displayed below the body. Their IDs, MIME types, storage
references and backup bundle entries carry the same meaning on every client. True inline
images would need a document-position representation, migration rules for old clients, and
round-trip tests for editing, sync, export and restore.

## Persistence and security review

The review traced Android's Room/SQLCipher and Keystore boundaries, the web's encrypted
IndexedDB rows and pending-save paths, and Windows' sealed state files, platform key
protection and rollback on failed commits. It also followed the attachment staging and
commit order, owner checks at the Worker and SQL boundary, the sync scenario fixtures, and
the v3/v4 backup readers. The implementation changes presentation and when navigation
waits for a local write. It reuses the existing note and attachment contracts.

## Verification

| Platform | Checks run | Result |
| --- | --- | --- |
| Windows | `cargo fmt --check`, native library, UI and rendered-window suites, Rust core contract and sync scenario suites | 24 native tests and 25 core tests passed. Inspected rendered notes and Settings in light and dark at 640×480 and 1280×720. The library render matrix also exercises the Windows contrast palette. |
| Web | TypeScript, Oxlint, production build with placeholder backend configuration, Vitest and tooling, guest Playwright suites | 1,537 unit tests, 10 tooling tests, and 52 browser tests passed across focused runs and the grid/sort checks. Four desktop-only cases skipped their mobile project. The 1,000-note test initially seeded its fixture during startup hydration, then passed after waiting for the local read. Lint reported 28 existing warnings. |
| Android | Debug APK, Detekt, editor/save/image/Markdown tests, sync scenario and backup round-trip tests | 36 focused tests passed across two runs. On the API 36 emulator, checked Settings in light and dark, system Back, theme persistence, system bar contrast, keyboard editing, portrait image picking and viewing, and the saved note in the library after reopening the app. |

The first pass used guest or fixture data. Release packages, Windows screen-reader testing, and
physical Android device testing require separate verification.

## Follow-up

- Grid, Compact, and List boards above 1,500 entries keep measured section heights as cards scroll
  out of view. CSS columns balance within each 80-item section in Grid and Compact. List keeps a
  single column, and the board retains pointer capture when dragging a card across sections.
  Chromium verified keyboard focus, mouse drag, and mobile touch drag across a section boundary,
  reached the last of 2,000 List notes, and mounted 80–160 cards after returning to the top. The 10,000-note Grid
  traversal reached the last note with 80 live cards and returned to 160 cards and about 4,900 DOM
  nodes, down from 10,000 cards and about 288,000 DOM nodes in the original run.
- Web backup recovery returns to the library after the local restore succeeds. Browser tests cover
  the notes-only JSON round trip and confirming or cancelling restoration from an image bundle.
- The public and in-app privacy descriptions distinguish Rust Windows storage and sign-out
  behavior from the legacy Kotlin app. The backup wording also describes complete exports that
  fetch cloud-only images.
- An isolated local Supabase project and Worker passed 498 database checks, six signed-in web
  browser tests and nine Rust integration checks. This covered an offline web edit syncing on
  reconnect, restoring its notes-only backup into a fresh account, Windows email-code sign-in
  and conflict review, and Windows attachment upload, download and deletion. Twelve additional
  Windows storage tests covered DPAPI, account separation and corrupted-file recovery.
- At 200% Slint scale, the Windows startup window extended past the monitor work area and hid
  New note. The native app now fits the initial window within the work area. A live guest run at
  200% verified the library, editor and Settings, including UI Automation names and focus.
- A signed-in web account uploaded an 8.36 MiB image to an isolated local Supabase project and
  Worker. The browser lost the accepted upload's reply and then went offline. On reconnect, the
  retry committed one attachment. Interrupting image downloads produced an explicitly incomplete
  backup. A second export restored the original bytes in a fresh web session. A clean Windows
  store fetched identical bytes through sync, and a separate Windows store restored them from
  the web backup and reopened the saved image.
- Windows Read mode displays top-level code blocks, quotes, and dividers. Code retains its
  indentation, and unsupported blocks can show their source without stripping formatting from
  neighboring prose. Switching back to Edit exposes the original Markdown.
- Windows Read mode also renders nested quotes with their own indentation. Formatting, task markers,
  and code remain visible inside quoted blocks. The renderer caps the displayed nesting depth at eight.
- Windows Read mode displays fenced and indented code inside common list layouts. Android's editor
  keeps Markdown formatting and cursor mapping in long multiline notes by processing line-aligned
  chunks. Individual lines beyond the parsing budget remain editable as source.
- At 150% app scale, Windows Narrator's speech recap showed the editor title field, Settings Back
  button, and selected System theme radio button. Escape returned focus to the library search field.
- The web and Windows image viewers open fitted and offer 150%, 200%, 300%, and 400% of fit.
  Use `+`, `-`, and `0` to change zoom or return to fit. Arrow keys pan from the focused zoomed
  image canvas. Changing the selected image restores fit. Desktop and mobile Chromium tests
  cover zoom, panning, gallery navigation, and focus return.
- Android's attachment viewer has fit and zoom controls, pinch zoom, and bounded panning. The shared
  Compose code compiles, and three JVM tests cover the zoom levels, pinch point, and pan bounds.
- Web guest notes can position images between paragraphs and move them back to the attachment strip.
  Desktop and mobile Chromium cover editing around an image, reload, removing its text boundary,
  undo, and restoring a positioned image from the shared v4 backup fixture. Position metadata is
  encrypted with the note on device. The web v2.1.0 backup reader recovers the fixture's note and
  image for display in the strip. The Kotlin and Rust clients preserve its position on import and
  re-export, and keep the image if a text edit invalidates the position. The
  [placement design](DECISIONS.md#proposed--place-attachments-between-paragraphs-without-changing-the-note-body-format)
  records the signed-in sync strategy.
- Windows guest notes can place pictures at the caret's text block from Edit mode and show them
  inline in Read mode. Pictures can return to the strip without changing the body or deleting the
  image. The native window test checks placement order, gallery indices, a text edit, and reopening
  the saved note.
- Android guest notes can place images from Edit mode and display them inline in Read mode.
  The shared Compose tests cover where the image controls appear and exercise the editor's local
  save path. On a Pixel 7, the debug app restored a positioned image, moved it to the strip,
  placed it again, and kept it inline after an edit. A cold launch exposed a dependency mismatch
  in the boot reminder receiver. The corrected build scheduled the reminder and displayed the
  image between the edited paragraphs after a cold launch.

- Signed-in image placement sync is wired across Supabase (`20261124000000_attachment_placements.sql`),
  Web, Rust, and Kotlin/Android. `apply_note_change_with_placements` validates and commits placements
  atomically alongside `apply_note_change`, `list_user_attachments` hydrates committed placements,
  and all three clients fall back to `apply_note_change` on `PGRST202` against older servers while
  preserving unpushed local placement edits during hydration. Verified with 14 pgTAP database checks
  (`notelikeus_attachment_placements.test.sql`), Web Vitest + TypeScript, Rust core/contract/HTTP
  tests, and Kotlin RPC signature + placement sync tests.

## Remaining work

1. Exercise signed-in recovery on Android against an HTTPS staging backend, including large
   attachments and interrupted connections. The staging endpoint was unreachable.
2. Test moving Windows between monitors with different scale factors. The review machine had one
   active monitor. The Narrator check sampled the library, editor, and Settings.
3. Verify signed-in placement sync end-to-end against a deployed staging backend and confirm
   fallback on an older released client build before broader rollout.
