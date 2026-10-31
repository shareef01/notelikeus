# Notelikeus — Premium UI/UX & Performance Audit

**Audit Date:** 2026-10-24  
**Auditor:** Principal Product Designer & Staff Web Performance Engineer  
**Baseline Commit:** `29bedd7d` (`review/perf-ux-validation`)  
**Target Application:** Notelikeus Web (Production: https://notelikeus.pages.dev/)  
**Environment:** React 19.2.8, Vite 8.3.3, Tailwind CSS 4.3.3, TypeScript 7.0.2, Zustand 5.0.6, IndexedDB WebCrypto  

---

## 1. Executive Summary

Notelikeus has a rock-solid security and offline-first data model: IndexedDB row encryption with AES-GCM, optimistic concurrency control via sequence revisions, conflict branch preservation, and R2 attachment claims. A previous stability pass (documented in `AUDIT_REPORT.md` and `docs/UI_UX_AUDIT_2026-10-21.md`) hardened data loss vectors, introduced truthful sync states, and fixed click/keyboard operation for dialogs and toasts.

However, the application’s visual presentation and interactive finish still exhibit rough edges:
1. **Primary Text Coupled to Accent Color:** The light theme couples body text to `--primary-rgb`. Selecting an accent color like `accent-blue` or `accent-green` tints all text throughout the app into blue or green rather than maintaining a quiet dark neutral (`#151515`).
2. **Missing Unified Component Primitives:** Core controls (Button, IconButton, Input, Badge, Card, Tooltip) are created via ad-hoc Tailwind strings across screens, causing minor discrepancies in hover states, focus rings, and sizing.
3. **Card Visuals & Floating Shadows:** Note cards use heavy, generic `hover:shadow-lg` and glassmorphic quick-action pills (`backdrop-blur-sm bg-true-surface/90`) that clash with colored notes and create scroll compositing overhead.
4. **Search Focus Outline Glitch:** The search bar renders a double focus ring (an outer border on the form container and an inner rounded outline on the `<input>` element).
5. **Native Browser Tooltips:** Browser `title` tooltips lag by 1.5–2s, fail WCAG 1.4.13, and cannot be operated on touch or keyboard focus.
6. **Editor Toolbar ARIA Pattern:** The editor rich-text toolbar presents 7 separate Tab stops rather than a composite toolbar with roving tabindex.

This audit establishes empirical baselines, classifies findings with evidence, and guides the incremental redesign into a quiet, sophisticated, Linear/Craft-grade experience.

---

## 2. Production Audit (Oct 12, 2026) Verification Status

We verified which fixes documented in the October 12, 2026 audit (`AUDIT_REPORT.md`) actually exist in the current branch `review/perf-ux-validation`:

| Finding ID | Description | Status on Current Branch | Verified Evidence |
|---|---|---|---|
| **F-01** | Kotlin `restore_note` argument mismatch | **Merged & Present** | Verified in `composeApp/src/commonMain/.../SupabaseNoteTransport.kt:330` |
| **F-02 (NLU-01)** | Web legacy-plaintext sealing migration race | **Merged & Present** | `web/src/lib/local/legacySealingRace.test.ts` (passing) |
| **F-03 (NLU-04)** | Non-atomic revision metadata multi-transaction | **Merged & Present** | `web/src/lib/supabase/revisionStateIntegrity.test.ts` (passing) |
| **F-04** | Web pull cursor skips concurrent writes | **Merged & Present** | `web/src/lib/supabase/revisionStore.ts` cursor decoupling (passing) |
| **F-05** | Null baseline remote delete silent no-op | **Maintained as Specified** | Tested under DEL-6 rules in `supabaseDeleteRevisionAuthority.test.ts` |
| **F-06** | Web offline edit discarded on remote update | **Merged & Present** | Conflict-copy creation in `web/src/lib/notes/offlineEditDurability.test.ts` |
| **F-07 (NLU-05)** | Android backup import memory OOM | **Merged & Present** | Streamed ZIP extraction in `BackupBundleTransfer.kt` |
| **F-08 (NLU-02)** | Worker compensation on finalize timeout | **Merged & Present** | Safe timeout handling in `workers/attachments/src/index.ts` |
| **F-09** | Provisional attachment quota leak sweep | **Merged & Present** | SQL migration `20261012000000` & worker sweep |
| **F-10** | Android SQLCipher export transient quarantine | **Merged & Present** | Retain plaintext fallback in `PlaintextDatabaseMigrator.kt` |
| **F-11** | Web transient CryptoKey failure memoization | **Merged & Present** | `web/src/lib/crypto/cryptoKeyRetry.test.ts` (passing) |
| **F-12** | Cross-client RPC contract signature gap | **Merged & Present** | `web/src/lib/contract/rpcSignatureContract.test.ts` (passing) |

*Conclusion:* All critical data-safety and synchronization fixes from October 12–21, 2026 are already merged and active on this branch. **No regressions to persistence, encryption, or synchronization may be introduced.**

---

## 3. Empirical Performance Baseline

Measurements taken on local execution environment (Windows 11, Node v24, Vitest v5.0.3, Vite v8.3.3, Happy-DOM):

### 3.1 Cold vs Warm Search Latency (`performanceBenchmark.test.ts`)
| Note Collection Size | Cold Search (Initial Query) | Warm Keystroke (Cached Token Match) |
|---|---|---|
| **100 notes** | 2.46 ms | 0.49 ms |
| **1,000 notes** | 11.64 ms | 2.07 ms |
| **5,000 notes** | 52.75 ms | 10.79 ms |
| **10,000 notes** | 102.37 ms | 20.01 ms |

### 3.2 Storage Decryption Throughput
- **`listStoredNotes` Decryption:** 17.46 ms for 200 notes (AES-GCM WebCrypto with streaming key validation).

### 3.3 Production Bundle Composition (`npm run build -- --mode staging`)
- `dist/assets/main-[hash].js`: 146.38 kB (gzip: 44.35 kB)
- `dist/assets/main-[hash].css`: 88.58 kB (gzip: 14.46 kB)
- `dist/assets/react-vendor-[hash].js`: 218.75 kB (gzip: 68.27 kB)
- `dist/assets/supabase-[hash].js`: 214.04 kB (gzip: 55.06 kB)
- `dist/assets/EditorScreen-[hash].js`: 47.25 kB (gzip: 14.05 kB)
- `dist/assets/AuthScreen-[hash].js`: 13.16 kB (gzip: 4.55 kB)
- `dist/assets/LabelsScreen-[hash].js`: 4.93 kB (gzip: 1.89 kB)
- `dist/sw.js` (Service Worker): 19.41 kB (gzip: 6.63 kB)
- Total Precached Assets: 60 entries (1,086.9 KiB)

### 3.4 Verification Health
- **Typecheck (`tsc -b --noEmit`):** 0 errors.
- **Linter (`oxlint`):** 0 errors, 118 non-blocking warnings.
- **Unit Suite (`vitest run`):** 138 test files, 1,022 tests passed in 19.85s.

---

## 4. UI/UX Findings Register

Findings are classified as:
- **Verified:** Confirmed by direct source reading, markup inspection, or runtime behavior.
- **Measured:** Backed by concrete timing or sizing benchmarks.
- **Hypothesis:** Plausible UX improvement backed by heuristic evaluation.

| ID | Category | Component & File Path | Issue Description | User Impact | Sev | Class | Recommended Solution |
|---|---|---|---|---|---|---|---|
| **F-UI-01** | Visual | `web/src/styles/globals.css:17, 105, 142` | Primary text (`body { color: rgb(var(--primary-rgb)) }`) is coupled to brand accent color in light mode. Choosing `accent-blue` turns all note titles, dialog text, and headings blue (`#0B57D0`). | Loud, distracting, unreadable on light theme; breaks neutral chrome principle. | **P1** | Verified | Decouple `--text-primary-rgb` (true neutral `#151515` on light) from `--primary-rgb` (interactive brand accent). |
| **F-UI-02** | Visual | `web/src/components/notes/NoteCard.tsx:224` | `hover:shadow-lg` applies a dramatic 15px blurred drop-shadow on card hover. | Cards feel like heavy floating panels rather than quiet paper cards on a refined board. | **P2** | Verified | Replace with tailored subtle 3dp elevation `--shadow-card-hover` and border highlight. |
| **F-UI-03** | Visual | `web/src/components/notes/NoteCard.tsx:425` | Card quick actions pill uses `backdrop-blur-sm bg-true-surface/90`. | Clashes on colored cards and image attachments; causes scroll layer rasterization. | **P2** | Verified | Use solid `bg-true-surface border border-brand-outline/40 shadow-sm`. |
| **F-UI-04** | UX / A11y | `web/src/components/layout/TopBar.tsx:148, 158` | Search input has `${CHROME_FOCUS}` inside a rounded form with `focus-within:border-brand-outline/50`. | Confusing double outline appears on search focus. | **P2** | Verified | Focus ring should be unified on the container pill, eliminating the inner outline. |
| **F-UI-05** | A11y | `web/src/components/editor/RichTextToolbar.tsx` | All 7 formatting buttons are separate Tab stops in `role="toolbar"`. | Keyboard navigation between note title and body is cluttered by 7 tabs. | **P2** | Verified | Implement roving tabindex with ArrowLeft/ArrowRight/Home/End navigation. |
| **F-UI-06** | UX | System-wide (`NoteCard`, `TopBar`, `EditorScreen`) | Over 25 icon buttons rely on native browser `title="..."` tooltips. | Tooltips lag by 1.5–2s, look inconsistent across OS, and are unavailable on touch or keyboard focus. | **P2** | Verified | Implement lightweight accessible Tooltip component / CSS tooltip for quick feedback. |
| **F-UI-07** | Architecture | `web/src/components/ui/` | Missing standard UI primitives (Button, IconButton, Input, Badge, Tooltip). | Ad-hoc class strings spread across screens cause visual drift. | **P2** | Verified | Build cohesive primitives in `web/src/components/ui/` matching the token specification. |
| **F-UI-08** | Visual | `web/src/screens/LabelsScreen.tsx:67-87` | Inline creation input has no border or focus container (`bg-transparent text-lg outline-none`). | Hard to locate input boundaries; inconsistent with rest of app. | **P3** | Verified | Wrap in refined card container with `INPUT_FOCUS` token. |
| **F-UI-09** | UX | `web/src/screens/LabelsScreen.tsx:90-98` | Empty state is an inline unstyled paragraph rather than using `NotesEmptyState`. | Visual disconnect between main screen and secondary screens. | **P3** | Verified | Unify into reusable `EmptyState` component. |
| **F-UI-10** | Visual | `web/src/components/notes/NoteCard.tsx:304-370` | In grid mode, status cluster and timestamp float with variable alignment when titles wrap. | Masonry grid lines look uneven across note cards. | **P3** | Verified | Refine header flex alignment and card padding hierarchy. |
| **F-UI-11** | Motion | `web/src/styles/globals.css:243` | `body { transition: background-color 0.3s ease, color 0.3s ease; }` triggers paint of entire page on theme toggle. | Can cause slight layout hitch on low-power devices. | **P3** | Measured | Constrain transition to 150ms `cubic-bezier(0.2, 0, 0, 1)`. |
| **F-UI-12** | Visual | `web/src/screens/MainScreen.tsx:613` | Mobile FAB uses `shadow-lg` and lacks subtle border. | Dominates viewport bottom on mobile; casts heavy shadow over cards. | **P3** | Verified | Refine to `shadow-card-lg border border-brand-outline/20`. |

---

## 5. Prioritized Implementation Roadmap

1. **Phase B — Design Foundation:**
   - Define semantic tokens in `globals.css`: `--color-text-primary`, `--color-text-secondary`, `--color-text-muted`, `--color-brand-accent`.
   - Decouple light theme text from interactive accent colors while maintaining `contracts/design/theme-colors.json` test compliance.
   - Refine typography scales, subtle card shadows (`--shadow-card-hover`), and focus styles.
   - Build core UI primitives in `web/src/components/ui/` (`Button`, `IconButton`, `Input`, `Badge`, `Tooltip`, `Card`).

2. **Phase C — Application Shell:**
   - Polish `TopBar` search bar focus, clear button, and recent search popover.
   - Refine `SideDrawer` navigation item typography, active indicator, and badge styling.
   - Polish `FilterRow` chips, scroll indicators, and color swatch row.

3. **Phase D — Notes Experience:**
   - Redesign `NoteCard` for quiet elegance: refined 3dp hover shadow, clean quick actions pill, crisp typography hierarchy.
   - Standardize `EmptyState` across MainScreen and LabelsScreen.
   - Optimize grid and list view spacing.

4. **Phase E — Note Editor:**
   - Implement ARIA roving tabindex in `RichTextToolbar`.
   - Refine typography and padding in floating and docked editor layouts.
   - Polish `EditorBottomBar` status and reminder display.

5. **Phase F & G — Performance, Accessibility & QA:**
   - Benchmark search and rendering before/after.
   - Verify WCAG 2.2 AA compliance, keyboard navigation, and contrast.
   - Run typecheck, lint, and full vitest suite.
