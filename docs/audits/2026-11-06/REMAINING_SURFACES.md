# Remaining-surface audit — 2026-11-06

Baseline: `fffad5f5179ac656a6b6b3ef3789a151ffcf4b25` (main after PR #437).
This pass extends the earlier source audit and Pixel verification into locale/DST
handling, image-picker failure paths, sync comparisons, browser accessibility and
scalability, live hosting protections, and published artifact inspection.

## Findings and repairs

### R01 — P2: native civil dates depend on the user's calendar system

Both platform `DateUtils` implementations used `Calendar.getInstance()`. On the
desktop JVM, Thai locale selects a Buddhist calendar. Passing the ISO year 2026
to `startOfDay` then produces Gregorian 1483; extracting local fields from a 2026
instant returns Buddhist year 2569. Search dates and reminder date-picker values
therefore disagree with their ISO/Gregorian contracts.

The real production-code regression failed under `th-TH`: expected epoch
`1783443600000`, actual `-15351231600000`. Both platform implementations now use
`GregorianCalendar` for arithmetic and fields. Formatting stays localized. The
desktop clock also uses locale-sensitive SHORT time formatting instead of a
hardcoded 12-hour pattern. Regressions cover Buddhist/Japanese calendar locale
preferences, reminder round trips, and German 24-hour formatting.

### R02 — P2: native relative-day filters use fixed-duration days across DST

`DatePreset.range`, `MainViewModel.startOfDayOffset`, yesterday labels and date
range summaries used multiples or quotients of 86,400,000 ms. Across a DST
transition, seven local dates do not span exactly seven such periods. A preset
can include/exclude an extra hour, `after:yesterday` near midnight can resolve to
the wrong date, and an eight-date interval can be labelled Last 7 days.

Added a platform calendar-day shift operation and used it for those boundaries
and labels. Regressions compare both DST transitions with independent
`java.time` civil dates, check the spring-forward summary boundary, and verify
that shifting a fall-back day's 00:30 by one calendar day reaches the next date.
Combined after:/before: ranges also count civil dates instead of elapsed
24-hour periods, with reversed ranges reported as an empty span.

### R03 — P2: desktop image selection allocates the entire file before rejecting its size

The desktop picker called `File.readBytes()` before `EditorViewModel` checked
the 10 MiB attachment limit. Selecting a huge file with an image extension could
exhaust heap before the guard ran. Source trace confirmed this allocation order;
the audit did not deliberately exhaust the user's machine.

The desktop picker now streams through a shared bounded reader, consuming at
most the configured limit plus one byte. Android external/picked image reads use
the same reader. Tests cover exact-limit/empty/oversized inputs, an effectively
unlimited stream that must stop after limit+1, and unusual zero-length reads.

### R04 — P2: Android photo-provider failures escape the picker callback

The photo picker called provider metadata, open, and read methods without an
exception boundary. A revoked grant, disappeared provider, or broken stream
could throw on the activity callback rather than refuse the image. The external
share ingestor already handled these failures, but the picker did not.

Extracted a guarded picker reader. Provider metadata/open/read failures return no
image; opened streams are closed. Robolectric provider mocks verify revoked
grants, missing files, I/O failure with closure, and successful image delivery.

### R05 — P2: delimiter collisions can acknowledge different edits or delete changed words

Web note equality joined label names and checklist fields with commas/colons.
One label named `a,b` therefore compared equal to separate labels `a` and `b`;
checklist text could impersonate another item. `notesUserContentEqual` can use
this answer to acknowledge a cloud version instead of retaining an edit.

The web conflict comparison and both clients' delete fingerprints had analogous
record/field-separator ambiguity. In particular, title `a\u001fb`, body `c` and
title `a`, body `b\u001fc` produced the same delete digest. A pending delete
without a known revision could then accept and delete a changed cloud copy.

Three web regressions failed against the original implementation. Label/checklist
equality and ephemeral note keys now use structured JSON. Both clients' delete fingerprints use
the same UTF-16 length framing and a `v2:` version prefix; shared golden vectors
pin parity. The underlying hash remains non-cryptographic. Legacy digests
conservatively mismatch, restoring/preserving the cloud note instead of claiming
it is verified; this can reintroduce a previously deleted note, with the existing
notice, rather than risk deleting an unverified edit. Web and native sync-engine
regressions cover collision protection and legacy-state behavior.

## Completed runtime and configuration inspection

- **136 browser checks passed** across German/Berlin and US/Los Angeles desktop
  contexts and Nepali/Kathmandu and Arabic/Cairo mobile contexts. Checks covered
  dialog naming, axe WCAG scans, keyboard focus/Escape/Tab behavior, note-color
  contrast, touch targets, and 320–1440 px reflow. The retained command is
  `cd web; npm run test:audit-ui`. Browser locale does not imply translated or RTL
  application content; these are checks of the current English UI under those
  browser settings.
- **Four browser scalability checks passed**, including 1,000-note structural
  updates and image-thumbnail retention, and traversal to note 9,999 in a
  10,000-note library. Traversal took about 158,777 ms and retained 228,186 DOM
  nodes; focus measurement was 1 ms. Thumbnail cache stabilized at 128 URLs with
  21 active references. These are observations, not a claim that the grid is
  virtualized or that browser memory includes only the reported JS heap.
- **Live GitHub protection** requires nine named build/test checks on main,
  enforces protection for administrators, and forbids force pushes/deletion.
  Production environment deployment is restricted to main and requires review.
  No settings were changed.
- **Live public hosting** returned CSP pinned to the actual backend origins,
  HSTS and frame denial. HTML revalidates without storage; the worker response
  includes `no-cache` (alongside inherited static-file cache directives).
  Thirty-eight public JavaScript assets were fetched and scanned with the
  repository's backend-secret scanner: no findings. This checks scanner patterns,
  not every possible secret format. GitHub records the production deployment of
  `5d6897043f9e5aea46d38e7925261ebc992e735c` as successful.
- **Published Android v2.6.2 APK** verifies with APK Signature Scheme v2 and
  matches the repository's pinned SHA-256 signing certificate
  `b1fd1a24ce9f4bf2ee8308f585d322fe0cc4dce468778ff063af30ceb9b697de`.
  Its manifest has backup disabled, no debuggable flag, target SDK 37, and
  permission-protected exported framework services. Its package is
  `com.aus.notelikeus`, versionCode 20. No published APK/MSI was installed in this
  pass.

## Release items requiring follow-up

1. **Windows publisher signing is absent.** Authenticode inspection of published
   `Notelikeus-2.6.2.msi` returned `NotSigned`; the release workflow has no signing
   step. A trusted signing identity and a sign/verify publication gate are needed.
   A self-signed certificate would not establish public publisher trust. The
   deployment runbook now records this confirmed gap.
2. **Published native artifacts predate the audit fixes.** Release v2.6.2 points
   to `708f611b38356096411bc7732927b60a963c99fc`, before the audit repair PRs.
   Merging code is not equivalent to shipping a native update. Prepare and
   authorize a new release separately; this audit does not publish artifacts or
   change production accounts/data.

Artifact SHA-256 values inspected:

- Android APK: `bd038735e30980dd45ad3b9bcda2aa0eb74015a18db65be35ebe03f68b9afae8`
- Windows MSI: `683a1aa01ae4bb9a8e246cba32d045c4aa32c1d9ad62213dc4ff7d7d146d39df`

## Validation and limits

The final complete native unit runs produced **1,001 desktop, 776 shared Android
and 27 Android-app passing tests**, with no failures or skips. Android debug lint
and a minified release APK build passed; the local APK is unsigned and was not
published. All **1,314 web tests in 172 files** and configured coverage gates
passed, plus ten dependency security checks. TypeScript, lint (existing warnings),
Pages artifact/header checks and 20 release-guard tests passed. Raw local logs
and downloaded public artifacts are under ignored `scratch/`.

An initial web coverage attempt competed with the native compiler/release builds
and failed the wall-clock search benchmark. It was stopped and rerun in
isolation; no benchmark thresholds were relaxed.

This extends coverage; it does not prove every component, possible input,
accessibility technology or platform behavior defect-free. Screen-reader/manual
assistive-technology testing, live multi-device/account switching, storage
pressure, reboot/reminder delivery and cloud failure injection remain outside
this pass. No production outage was injected. Docker was not running, so no
fresh local database integration run was claimed; the existing CI database gate
and prior source audit remain the evidence for that surface.
