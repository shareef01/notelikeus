# Architecture review — October 2026

Baseline `3baed663`. Diagrams describe the code as read in this pass; where a step was taken from
migration or function names rather than traced line by line, it says so.

## 1. Overall architecture

```mermaid
flowchart LR
  subgraph Native["Android / Windows (Kotlin Multiplatform)"]
    CUI[Compose UI] --> VM[ViewModels<br/>Editor, Main]
    VM --> REPO[NoteRepositoryImpl]
    REPO --> ROOM[(Room + SQLCipher<br/>key: Keystore / DPAPI)]
    REPO --> COORD[SyncCoordinator<br/>Android SyncWorker · DesktopSyncCoordinator]
    COORD --> ENG[NoteSyncEngine]
    ENG --> GATE[LocalCommitGate · DatasetEpoch<br/>account isolation]
    ENG --> STATE[(NoteSyncStateStore<br/>prefs / DataStore)]
    ENG --> KT[SupabaseNoteTransport]
    ENG --> KATT[AttachmentSyncService]
  end
  subgraph Web["Web PWA (React + Zustand)"]
    RUI[React screens] --> STORES[Zustand stores<br/>notes · tombstones · auth · settings]
    RUI --> ACT[noteActions]
    ACT --> IDB[(IndexedDB<br/>bodies sealed, WebCrypto)]
    SYNC[notesSyncService<br/>realtime + reconcile] --> DS[supabaseRemoteNotesDataSource]
    DS --> SE[supabaseSyncEngine]
    SE --> IDB
    ACT --> DS
    ACT --> WATT[attachmentSyncService]
  end
  subgraph Cloud
    PG[(Postgres + RLS<br/>notes · note_tombstones · note_attachments · sync_meta)]
    RT[Realtime<br/>wake-up only]
    W[Attachments Worker<br/>JWT verify, key derivation]
    R2[(R2)]
  end
  KT -- RPC --> PG
  SE -- RPC --> PG
  PG --> RT --> SE
  KATT --> W
  WATT --> W
  W --> R2
  W -- RPC --> PG
```

**Assessment.** The split is sound: the two runtimes share a *protocol* (RPC signatures, payload
shape, cloud semantics) and the `contracts/` fixtures pin it, rather than forcing one codebase. The
cost is that every behavioural rule — and every defect — exists twice. This pass found the same
conflict defect (AU-01/02, AU-04/05) independently in both, which is the strongest argument for the
recommendation in §8.

## 2. Note creation and editing

```mermaid
sequenceDiagram
  participant U as User
  participant E as Editor
  participant L as Local store<br/>(Room / IndexedDB)
  participant S as Sync
  participant P as Postgres
  U->>E: types
  E->>E: debounce (autosave)
  E->>L: write row (timestamp = now)
  Note over E,L: "Saved locally" only after this resolves
  E->>S: schedule upload
  S->>P: apply_note_change(base_revision)
  alt base matches
    P-->>S: applied(revision, server_updated_at)
    S->>L: record revision + synced baseline
  else stale base
    P-->>S: conflict(current)
    S->>S: adopt only if same words (AU-03)
  end
```

## 3. Inbound synchronisation and conflict handling (after this pass)

The chart is the Kotlin download (`ConflictResolution.decide`). Web, after #316, gets the same end
states differently: an edited row carries a pending-push marker, every inbound apply skips marked
rows, and the queued push is sent against the marker's base revision — a conflict there keeps the
cloud version on the note and the local one as a conflicted copy (`pendingPushes.ts`).

```mermaid
flowchart TD
  IN[Incoming cloud version<br/>pull page · snapshot · reconcile] --> HAS{Local row?}
  HAS -- no --> ACC[Accept; record baseline]
  HAS -- yes --> ED{Edited here?<br/>row.timestamp ≠ baseline}
  ED -- no --> ACC
  ED -- yes --> SAME{Same words?}
  SAME -- yes --> ACC
  SAME -- no --> STAMP{Cloud stamp == row stamp?}
  STAMP -- yes --> KEEP[Keep local: pending edit<br/>on current revision → upload]
  STAMP -- no --> FORK[Insert local words as<br/>'… (conflicted copy)'<br/>then accept cloud version]
  FORK --> UP[Upload copy as a new note]
  ACC --> TX[Commit in one transaction<br/>web: abort + re-plan if a row changed meanwhile]
  FORK --> TX
```

Deletion arrives on the same path: a tombstone (or an id missing from a complete snapshot) for a row
that is *edited here* forks the row before the delete.

## 4. Deletion and restore

```mermaid
sequenceDiagram
  participant U as User
  participant L as Local
  participant T as Tombstones (local)
  participant P as Postgres
  U->>L: delete permanently
  L->>L: remove row
  L->>T: mark deleted (+ baseline revision if known)
  alt baseline known
    L->>P: apply_note_delete(base_revision)
    P-->>L: applied / idempotent / conflict
  else no baseline
    Note over L,P: nothing sent; next snapshot lets the cloud win<br/>→ note reappears silently (AU-07)
  end
  U->>L: Undo (restore)
  L->>T: mark restored, clear tombstone
  L->>P: restore_note
```

## 5. Attachment lifecycle

Taken from the Worker, client services and migration names
(`attachment_preflight_and_finalize`, `attachment_put_idempotency`, `attachment_delete_claim`,
`attachment_commitment`, `orphan_attachment_sweep`); the per-step SQL was audited in prior passes
(F46–F52) and not re-traced here.

```mermaid
sequenceDiagram
  participant C as Client
  participant B as Local blob store
  participant W as Worker
  participant P as Postgres
  participant R as R2
  C->>B: stage bytes (pending:<id>)
  C->>C: reference in note, save locally
  C->>W: PUT (JWT)
  W->>P: preflight (ownership, quota)
  W->>R: stream ≤ limit
  W->>P: finalize (provisional row)
  C->>P: apply_note_change(…, attachment_ids) → commit rows
  Note over C,P: every client: v2 upload, 15-arg commit naming its r2 ids (AU-17)
  C->>W: DELETE → P: claim → R: delete → P: terminal
  P->>P: orphan sweep for uncommitted rows
```

## 6. Backup import / export

```mermaid
flowchart LR
  EXP[Export] --> J[JSON v3 document<br/>notes only]
  EXP --> Z[.nlkbak ZIP<br/>v3 JSON + attachment bytes this device holds]
  J --> IMP
  Z --> IMP[Import: validate caps → new ids → commit locally]
  IMP --> UPL[Upload as new notes]
```

Imports always add notes under new ids (never overwrite), so an import cannot lose existing content;
the cost is duplicates on a repeated import (documented behaviour).

## 7. Local state that conflict preservation relies on

| Store | Web | Kotlin |
|---|---|---|
| Revision base per note | `LocalOwnerMeta.noteRevisions` | `knownServerRevisionById` |
| Unsynced-edit marker | `LocalOwnerMeta.pendingPushes` (#316: base revision + edit counter) | **Synced baseline (new)** `syncedTimestampById` (prefs `synced_timestamps` / DataStore `sync_synced_timestamps`) |
| Pull cursor | `lastRemoteRevision` | — (Kotlin reads full snapshots) |
| Tombstones + delete baselines | `tombstoneStore` | `deleted_at_by_id`, `baseline_delete_revisions` |

The Kotlin baseline is cleared with the rest of sync state on sign-out/account switch. Web markers
are namespaced by owner id and deliberately survive a switch (#316 keeps a previous account's
unsynced edits for that account), so neither can leak into another account.

## 8. Recommendations

1. **A shared conflict-rule contract — implemented.** The table in §3 is pinned by
   `contracts/sync/inbound-resolution.json` (13 cases) for Kotlin. The web client, after #316, detects
   unsynced edits with pending-push markers and resolves conflicts at push time, so it reaches the same
   end states by a different route; extending the fixture to web needs an adapter that drives #316's
   pull + flush path per case.
2. **Server-side conflict hint.** `apply_note_change` already returns `current`; returning the
   revision the client's base corresponds to would let clients detect "edited on top of a stale
   base" without a local baseline. Optional; the client-side baseline is sufficient.
3. **Do not merge the runtimes.** Moving the web onto Kotlin/Wasm would trade a well-tested React
   client for a large migration with no data-safety gain. Contract fixtures give most of the benefit.
