# Cross-client contracts

Canonical JSON fixtures that both the Kotlin clients (Android / Windows) and the TypeScript
web client parse and produce. They exist because the two implementations are separate code
bases talking to one Supabase schema and one backup file format, so a field renamed,
defaulted, coerced or serialized differently on one side is invisible until it corrupts the
other side's data.

There is no code generation here on purpose. A directory of canonical JSON plus a test on each
side that reads it is enough to make drift a failing test rather than a support ticket.

## Layout

| Path | What it pins |
|---|---|
| `backup/v3-kotlin-export.json` | A v3 backup exactly as `NoteBackupExporter` writes it |
| `backup/v3-web-export.json` | A v3 backup exactly as `exportNotesBackup` writes it |
| `backup/v3-expected-notes.json` | The notes both importers must produce from **either** file |
| `backup/v4-bundle-manifest.json` | The `manifest.json` inside a `.nlkbak` bundle (see below) |
| `backup/import-limits.json` | Soft caps both importers must enforce (depth, notes, labels, field sizes) |
| `cloud/note-rpc-args.json` | The `apply_note_change` argument object the web client sends (Kotlin adds `p_attachment_ids`) |
| `cloud/note-row.json` | A note row as `pull_changes` / `fetch_full_snapshot` return it |
| `cloud/note-row-expected.json` | The note both cloud mappers must produce from that row |
| `cloud/tombstone-row.json` | A tombstone row and the deleted-at map it must produce |
| `sync/inbound-resolution.json` | Kotlin download rules: what an inbound cloud version may do to the local row — accept, keep the local row, or keep the local edit as a conflicted copy. Web reaches the same end states through pending-push markers (`offlineEditDurability.test.ts`) |

## The normal form

`*-expected*.json` files are written in a **client-neutral normal form** so one file can be
asserted against a Kotlin `Note` and a TypeScript `Note` without either side's local-only
fields leaking in:

- Local row ids (`Note.id` on Kotlin, `Note.id`/`localId` on web, checklist and label ids) are
  **excluded**. They are assigned locally and would never match across clients.
- `labels` is a list of names, sorted.
- `checklist` is a list of `{ text, isChecked, position }`, ordered by `position`.
- Absent optional values are written as JSON `null`, never omitted.

## Consumers

| Side | Test |
|---|---|
| Kotlin | `composeApp/src/commonTest/kotlin/com/aus/notelikeus/contract/CrossClientContractTest.kt` |
| TypeScript | `web/src/lib/contract/crossClientContract.test.ts` |
| Kotlin | `composeApp/src/commonTest/kotlin/com/aus/notelikeus/contract/InboundResolutionContractTest.kt` (sync rules) |

Both resolve this directory by walking up from the working directory, so they run from the
repository root or from inside a module.

The fixtures pin what each client *sends*; they cannot say whether the database accepts it.
PostgREST matches an RPC by its set of argument names, so that is checked separately, against
`supabase/migrations`, by `RpcSignatureContractTest.kt` (Kotlin, `composeApp/src/desktopTest`) and
`web/src/lib/contract/rpcSignatureContract.test.ts`.

## Changing a fixture

Changing a fixture is changing the wire format. Both tests must be updated in the same change,
and a field that older data may not carry needs a default that agrees on both sides — see
`docs/DECISIONS.md` D21.
