# Supabase cutover audit

**Date:** 2026-09-05  
**Branch:** `cutover/supabase-canonical`  
**Mandate:** Supabase is a new canonical backend. Legacy Firebase users and Firestore data are abandoned. Dual-backend flags and UID bridges are removed.

## Phase matrix (original BACKEND_MIGRATION.md claims)

| Phase | Claimed status | Source exists | Tests exist | Classification | Action |
|---|---|---|---|---|---|
| 0 — inventory | implemented | yes | n/a | VERIFIED | Historical |
| 1 — remote abstraction | implemented | yes (`RemoteNotesDataSource`, `CloudNoteTransport`) | yes | VERIFIED | Keep generic interfaces |
| 2 — IndexedDB local-first | implemented | yes | yes | VERIFIED | Keep |
| 3 — startup hydration | implemented | yes | yes | VERIFIED | Keep; empty-cloud guard retained |
| 4 — Supabase notes adapter | implemented | yes | yes | VERIFIED | Made unconditional |
| 5 — Supabase Auth | implemented | yes | yes | VERIFIED | Made unconditional |
| 6 — Firebase UID mapping / import | implemented | removed | pgTAP removal tests | OBSOLETE / REMOVE | Dropped table + RPCs via `20260905000000_remove_firebase_compatibility.sql` |
| 7 — Realtime | implemented | yes | publication pgTAP | VERIFIED | Keep as pull wake-up |
| 8 — R2 attachments | implemented | yes (Worker + metadata RPCs) | Worker + pgTAP | VERIFIED | Keep R2; do not add Supabase Storage |
| 9 — Kotlin attachment UI | implemented | yes | compile/tests | PARTIAL | Kept existing paths |
| 10 — Cloudflare Pages | implemented | yes | `pages:verify` | VERIFIED | Canonical hosting |
| 11 — Firebase retirement readiness | dual-backend flags | removed | n/a | OBSOLETE | Flags and fallbacks deleted |
| 12 — staging bootstrap | implemented | Pages/staging scripts | n/a | PARTIAL | Owner still deploys production |

## Firebase compatibility removed

1. `firebase_uid_mappings` and `link_firebase_uid` / verified-link Worker route
2. Web Firestore repository, Firebase Auth listener, emulator e2e
3. Kotlin `FirebaseSessionManager`, Firestore transports, account linker
4. Feature flags `VITE_REMOTE_BACKEND`, `VITE_ALLOW_SUPABASE_*`, `NOTELIKEUS_REMOTE_BACKEND`
5. Firebase Hosting (`firebase.json`, `firebase deploy`, rules tests)
6. Ops tools `export-firestore-user` / Firestore→backup conversion

## Preserved correctness

- Server-controlled revision sequence
- Tombstones + anti-resurrection (`apply_note_change` refuses silent restore)
- Explicit undo via `clear_note_tombstone`
- Empty-cloud / failed-read guards
- Guest namespace `__guest__`
- Account isolation by Supabase UUID
- IndexedDB / Room local-first

## Field parity (persisted note)

Client-generated string note ids (`localId` as text). Columns in `notes` map to Kotlin `Note` / Web `Note` / backup JSON: title, content, timestamps, color (ARGB int), pin/archive/trash, order, labels, checklist, reminder, attachments JSON, revision, owner.

## Security parity (former firestore.rules)

| Firestore rule guarantee | Supabase equivalent |
|---|---|
| Owner isolation | RLS on notes, tombstones, sync_meta, attachments |
| Server timestamp protection | RPC-assigned revision / server `updated_at` |
| Schema limits | RPC validation + constraints |
| Cross-user writes denied | RLS + `auth.uid()` inside SECURITY DEFINER RPCs |
| Direct mutation of protected fields | mutation-guard triggers |

## Remaining owner actions

Create/select a Supabase project, Google OAuth, `supabase db push`, R2 bucket, Worker deploy, Pages project + env, custom domain. Do not migrate Firebase users.

## Blocked locally without Docker

`npm run supabase:start` / `supabase db reset` / `supabase test db` require Docker. CI workflow `.github/workflows/supabase.yml` still runs them on Ubuntu.
