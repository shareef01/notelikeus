//! Offline-first, revision-checked sync over a [`Transport`], operating on a [`LocalState`].
//!
//! The rules are the other clients': every local edit is saved first and queued against the revision
//! it was made on; the server refuses a stale base; a refused or deleted-elsewhere edit is kept as a
//! "(conflicted copy)"; a delete is sent against a known revision and loses to a newer cloud edit;
//! pushes never move the pull cursor (F-04); a wipe on another device blocks uploads.
//!
//! The engine is synchronous and holds no I/O of its own, so every scenario is a plain unit test
//! against an in-memory server (`tests/sync_scenarios.rs`). The caller saves the state after each call.

use crate::conflict::{self, InboundAction};
use crate::model::{ApplyNoteChangeArgs, CloudChange, CloudNoteRow, Note, allocate_note_id};
use crate::store::{LocalState, PendingPush, Tombstone, TombstoneOrigin};

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ApplyOutcome {
    Applied {
        revision: i64,
        server_updated_at: Option<i64>,
    },
    /// The base revision is not the row's current one; `current` is what the cloud holds.
    Conflict {
        current: Box<CloudNoteRow>,
    },
    /// Another device deleted the note.
    Deleted,
    /// A base revision was sent for a note the cloud has neither as a row nor as a tombstone.
    NotFound,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum DeleteOutcome {
    Deleted,
    /// Already deleted, or never in the cloud: either way the delete has converged.
    AlreadyGone,
    /// The cloud moved past the revision the delete was made against.
    Conflict {
        current: Box<CloudNoteRow>,
    },
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PullPage {
    pub changes: Vec<CloudChange>,
    pub has_more: bool,
    pub wipe_generation: i64,
}

#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum TransportError {
    #[error("network unavailable: {0}")]
    Network(String),
    #[error("not signed in or the session expired")]
    Unauthorized,
    /// NL001 / NL002: the account was wiped on another device after this device last synced.
    #[error("the cloud data was deleted on another device")]
    WipeGenerationStale,
    /// NL003.
    #[error("this account was deleted")]
    AccountDeleted,
    /// NL004.
    #[error("a recent sign-in is required to do that")]
    ReauthenticationRequired,
    /// NL005.
    #[error("account note limit reached")]
    AccountNoteLimit,
    /// 22023.
    #[error("note rejected by server validation: {0}")]
    PayloadInvalid(String),
    #[error("server error: {0}")]
    Server(String),
}

pub trait Transport {
    fn apply_note_change(&mut self, args: &ApplyNoteChangeArgs) -> Result<ApplyOutcome, TransportError>;
    fn apply_note_delete(
        &mut self,
        note_id: &str,
        base_revision: Option<i64>,
        wipe_generation: i64,
    ) -> Result<DeleteOutcome, TransportError>;
    fn pull_changes(&mut self, after_revision: i64, limit: u32) -> Result<PullPage, TransportError>;
}

/// What one [`sync`] run did, for the UI and the tests.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct SyncReport {
    pub pushed: Vec<String>,
    pub conflicted_copies: Vec<String>,
    /// Pairs of `(original_note_id, copy_note_id)` created during this run so attachments can be carried over.
    pub forked_sources: Vec<(String, String)>,
    pub deleted_remotely: Vec<String>,
    pub restored_by_cloud: Vec<String>,
    pub skipped_invalid: Vec<String>,
    pub pulled: usize,
}

#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum SyncError {
    #[error(transparent)]
    Transport(#[from] TransportError),
    #[error("no such note: {0}")]
    NoSuchNote(String),
}

/// A source of ids for notes this device creates: millis plus a random suffix.
pub trait IdSource {
    fn now_millis(&mut self) -> i64;
    fn random_below_1000(&mut self) -> u32;
}

fn id_taken(state: &LocalState, id: i64) -> bool {
    let key = id.to_string();
    state.notes.contains_key(&key) || state.tombstones.contains_key(&key) || state.revisions.contains_key(&key)
}

fn new_note_id(state: &LocalState, ids: &mut dyn IdSource) -> i64 {
    let (now, random) = (ids.now_millis(), ids.random_below_1000());
    allocate_note_id(now, random, |id| id_taken(state, id))
}

fn mark_pending(state: &mut LocalState, id: &str) {
    state.push_seq += 1;
    let seq = state.push_seq;
    let base = state.revisions.get(id).copied();
    state
        .pending
        .entry(id.to_string())
        // The base is the revision the *first* unconfirmed edit was made on; later edits build on it.
        .and_modify(|p| p.seq = seq)
        .or_insert(PendingPush { base_revision: base, seq });
}

// ---- Local mutations: saved first, queued for the next sync ------------------------------------

pub fn create_note(state: &mut LocalState, ids: &mut dyn IdSource, edit: impl FnOnce(&mut Note)) -> String {
    let id = new_note_id(state, ids);
    let mut note = Note::new(id, ids.now_millis());
    edit(&mut note);
    let key = note.id.clone();
    state.notes.insert(key.clone(), note);
    mark_pending(state, &key);
    key
}

pub fn edit_note(state: &mut LocalState, id: &str, now: i64, edit: impl FnOnce(&mut Note)) -> Result<(), SyncError> {
    let note = state.notes.get_mut(id).ok_or_else(|| SyncError::NoSuchNote(id.into()))?;
    edit(note);
    // Strictly increasing, so an edit within the same millisecond still reads as an edit.
    note.timestamp = now.max(note.timestamp.saturating_add(1));
    mark_pending(state, id);
    Ok(())
}

/// A permanent delete. The row goes now; the tombstone remembers the revision it was made against, or
/// its authored content digest when the revision was never known (AU-07).
pub fn delete_note(state: &mut LocalState, id: &str, now: i64) -> Result<(), SyncError> {
    let removed = state.notes.remove(id).ok_or_else(|| SyncError::NoSuchNote(id.into()))?;
    state.pending.remove(id);
    let base = state.revisions.get(id).copied();
    let content_digest = if base.is_none() { Some(conflict::authored_content_digest(&removed)) } else { None };
    state.tombstones.insert(
        id.to_string(),
        Tombstone {
            deleted_at: now,
            origin: TombstoneOrigin::Local { confirmed: base.is_none() },
            base_revision: base,
            content_digest,
        },
    );
    Ok(())
}

// ---- Sync ---------------------------------------------------------------------------------------

/// Keeps `local`'s words as a new, never-synced note queued for upload; returns its id.
fn fork_copy(state: &mut LocalState, local: &Note, ids: &mut dyn IdSource, report: &mut SyncReport) -> String {
    let copy_id = new_note_id(state, ids);
    let copy = conflict::conflicted_copy_of(local, copy_id);
    let key = copy.id.clone();
    state.notes.insert(key.clone(), copy);
    state.pending.remove(&key);
    mark_pending(state, &key);
    // A brand-new note: no base revision whatever mark_pending found.
    if let Some(p) = state.pending.get_mut(&key) {
        p.base_revision = None;
    }
    report.conflicted_copies.push(key.clone());
    report.forked_sources.push((local.id.clone(), key.clone()));
    key
}

fn accept_cloud(state: &mut LocalState, row: &CloudNoteRow) {
    let note = row.to_note();
    state.synced_timestamps.insert(note.id.clone(), note.timestamp);
    state.known_cloud_ids.insert(note.id.clone());
    if let Some(revision) = row.revision {
        state.revisions.insert(note.id.clone(), revision);
    }
    state.pending.remove(&note.id);
    state.notes.insert(note.id.clone(), note);
}

fn push_one(
    state: &mut LocalState,
    transport: &mut dyn Transport,
    ids: &mut dyn IdSource,
    id: &str,
    report: &mut SyncReport,
) -> Result<(), TransportError> {
    let Some(pending) = state.pending.get(id).cloned() else { return Ok(()) };
    let Some(local) = state.notes.get(id).cloned() else {
        state.pending.remove(id);
        return Ok(());
    };
    match state.tombstones.get(id).map(|t| t.origin) {
        // Deleted here: the delete owns the cloud now (unreachable while the row exists, kept for safety).
        Some(TombstoneOrigin::Local { .. }) => {
            state.pending.remove(id);
            return Ok(());
        }
        // Deleted elsewhere while this edit was unconfirmed: the words become their own note (AUD-I01).
        Some(TombstoneOrigin::Cloud) => {
            fork_copy(state, &local, ids, report);
            state.notes.remove(id);
            state.pending.remove(id);
            return Ok(());
        }
        None => {}
    }

    // R19.2: exactly the uploaded images this note version references, so the commit promotes those
    // provisional uploads and no others. A pending image has no server row to name.
    let attachment_ids: Vec<String> = state
        .attachments
        .get(&local.id)
        .map(|images| images.iter().filter(|a| !a.is_pending()).map(|a| a.id.clone()).collect())
        .unwrap_or_default();
    let args = ApplyNoteChangeArgs::new(&local, pending.base_revision, attachment_ids, state.wipe_generation);
    let mut outcome = transport.apply_note_change(&args)?;
    if outcome == ApplyOutcome::NotFound {
        // Nothing in the cloud to conflict with — no row, no tombstone — so this is a create.
        let create = ApplyNoteChangeArgs { p_base_revision: None, ..args };
        outcome = transport.apply_note_change(&create)?;
    }
    match outcome {
        ApplyOutcome::Applied { revision, server_updated_at } => {
            state.revisions.insert(id.into(), revision);
            state.known_cloud_ids.insert(id.into());
            if let Some(note) = state.notes.get_mut(id) {
                note.server_updated_at = server_updated_at.or(note.server_updated_at);
                state.synced_timestamps.insert(id.into(), note.timestamp);
            }
            state.pending.remove(id);
            report.pushed.push(id.into());
        }
        ApplyOutcome::Conflict { current } => {
            if !conflict::authored_content_equal(&local, &current.to_note()) {
                fork_copy(state, &local, ids, report);
            }
            // Same words: usually this device's own push whose reply was lost. Either way the cloud's
            // version is the note now, at the cloud's revision.
            accept_cloud(state, &current);
        }
        ApplyOutcome::Deleted => {
            fork_copy(state, &local, ids, report);
            state.notes.remove(id);
            state.pending.remove(id);
            state.revisions.remove(id);
            state.known_cloud_ids.remove(id);
            state.tombstones.insert(
                id.into(),
                Tombstone {
                    deleted_at: local.timestamp,
                    origin: TombstoneOrigin::Cloud,
                    base_revision: None,
                    content_digest: None,
                },
            );
        }
        ApplyOutcome::NotFound => {
            return Err(TransportError::Server(format!("create of note {id} answered note_not_found")));
        }
    }
    Ok(())
}

fn push_pending(
    state: &mut LocalState,
    transport: &mut dyn Transport,
    ids: &mut dyn IdSource,
    report: &mut SyncReport,
) -> Result<(), TransportError> {
    // Copies made while pushing are queued too, so loop until the queue stops shrinking (bounded:
    // a copy is a fresh note that can only be applied or fail). Skipped invalid payloads stay queued
    // without blocking other notes in the same sync pass.
    let mut skipped = std::collections::BTreeSet::new();
    for _ in 0..4 {
        let mut queue: Vec<(u64, String)> =
            state.pending.iter().filter(|(id, _)| !skipped.contains(*id)).map(|(id, p)| (p.seq, id.clone())).collect();
        if queue.is_empty() {
            break;
        }
        queue.sort_by(|a, b| a.0.cmp(&b.0).then_with(|| a.1.cmp(&b.1)));
        for (_, id) in queue {
            match push_one(state, transport, ids, &id, report) {
                Ok(()) => {}
                Err(TransportError::PayloadInvalid(_)) => {
                    skipped.insert(id.clone());
                    if !report.skipped_invalid.contains(&id) {
                        report.skipped_invalid.push(id);
                    }
                }
                Err(TransportError::WipeGenerationStale) => {
                    state.uploads_blocked_after_wipe = true;
                    return Err(TransportError::WipeGenerationStale);
                }
                Err(err) => return Err(err),
            }
        }
    }
    Ok(())
}

fn push_deletes(
    state: &mut LocalState,
    transport: &mut dyn Transport,
    report: &mut SyncReport,
) -> Result<(), TransportError> {
    let unconfirmed: Vec<(String, Option<i64>)> = state
        .tombstones
        .iter()
        .filter(|(_, t)| t.origin == TombstoneOrigin::Local { confirmed: false })
        .map(|(id, t)| (id.clone(), t.base_revision))
        .collect();
    for (id, base) in unconfirmed {
        let outcome = match transport.apply_note_delete(&id, base, state.wipe_generation) {
            Ok(outcome) => outcome,
            Err(TransportError::WipeGenerationStale) => {
                state.uploads_blocked_after_wipe = true;
                return Err(TransportError::WipeGenerationStale);
            }
            Err(err) => return Err(err),
        };
        match outcome {
            DeleteOutcome::Deleted | DeleteOutcome::AlreadyGone => {
                state.revisions.remove(&id);
                state.known_cloud_ids.remove(&id);
                if let Some(t) = state.tombstones.get_mut(&id) {
                    t.origin = TombstoneOrigin::Local { confirmed: true };
                    t.content_digest = None;
                }
            }
            DeleteOutcome::Conflict { current } => {
                // Another device edited the note after the revision this delete saw: its words win
                // and the note comes back, as on the other clients.
                state.tombstones.remove(&id);
                accept_cloud(state, &current);
                report.restored_by_cloud.push(id);
            }
        }
    }
    Ok(())
}

fn apply_inbound_note(state: &mut LocalState, row: &CloudNoteRow, ids: &mut dyn IdSource, report: &mut SyncReport) {
    let id = row.note_id.clone();
    let cloud = row.to_note();
    if let Some(tombstone) = state.tombstones.get_mut(&id) {
        // If this device deleted an unconfirmed note whose create reply was lost, check whether the
        // cloud row holds the exact deleted words: if so, confirm the delete rather than resurrecting it.
        if tombstone.base_revision.is_none()
            && let Some(digest) = tombstone.content_digest.as_deref()
            && conflict::authored_content_digest(&cloud) == digest
        {
            tombstone.base_revision = row.revision;
            tombstone.origin = TombstoneOrigin::Local { confirmed: false };
            return;
        }
        let newer = match (row.revision, tombstone.base_revision) {
            (Some(remote), Some(base)) => remote > base,
            _ => true,
        };
        if !newer {
            return; // The version this device deleted (or older); the delete stands.
        }
        // Edited elsewhere after the delete (or restored there): the cloud's words win.
        state.tombstones.remove(&id);
        report.restored_by_cloud.push(id.clone());
    }
    let local = state.notes.get(&id).cloned();
    let synced = state.synced_timestamps.get(&id).copied();
    match conflict::decide(local.as_ref(), synced, &cloud) {
        InboundAction::Accept => accept_cloud(state, row),
        InboundAction::KeepLocal => {
            state.known_cloud_ids.insert(id);
        }
        InboundAction::Fork => {
            if let Some(local) = local {
                fork_copy(state, &local, ids, report);
            }
            accept_cloud(state, row);
        }
    }
}

fn apply_inbound_tombstone(
    state: &mut LocalState,
    id: &str,
    deleted_at: i64,
    ids: &mut dyn IdSource,
    report: &mut SyncReport,
) {
    if let Some(local) = state.notes.get(id).cloned() {
        let edited = state.pending.contains_key(id)
            || conflict::is_locally_edited(&local, state.synced_timestamps.get(id).copied());
        if edited {
            fork_copy(state, &local, ids, report);
        }
        state.notes.remove(id);
        report.deleted_remotely.push(id.into());
    }
    state.pending.remove(id);
    state.revisions.remove(id);
    state.known_cloud_ids.remove(id);
    state.synced_timestamps.remove(id);
    state.tombstones.insert(
        id.into(),
        Tombstone { deleted_at, origin: TombstoneOrigin::Cloud, base_revision: None, content_digest: None },
    );
}

const MAX_PULL_PAGES: usize = 500;

fn pull(
    state: &mut LocalState,
    transport: &mut dyn Transport,
    ids: &mut dyn IdSource,
    report: &mut SyncReport,
) -> Result<(), TransportError> {
    let fresh = never_synced(state);
    for _ in 0..MAX_PULL_PAGES {
        let page = transport.pull_changes(state.last_remote_revision, 100)?;
        if page.wipe_generation > state.wipe_generation {
            if !fresh {
                // Uploading now would put back what the user deleted on another device; that is their call.
                state.uploads_blocked_after_wipe = true;
                return Err(TransportError::WipeGenerationStale);
            }
            // A device that never synced holds nothing from before the wipe: it joins the current generation.
            state.wipe_generation = page.wipe_generation;
        }
        let prev_cursor = state.last_remote_revision;
        let mut cursor = prev_cursor;
        for change in &page.changes {
            match change {
                CloudChange::Note(row) => {
                    cursor = cursor.max(row.revision.unwrap_or(cursor));
                    apply_inbound_note(state, row, ids, report);
                }
                CloudChange::Tombstone(row) => {
                    cursor = cursor.max(row.revision.unwrap_or(cursor));
                    apply_inbound_tombstone(state, &row.note_id, row.deleted_at.unwrap_or(0), ids, report);
                }
            }
            report.pulled += 1;
        }
        state.last_remote_revision = cursor;
        if !page.has_more || page.changes.is_empty() {
            return Ok(());
        }
        if cursor <= prev_cursor {
            return Err(TransportError::Server("pull pagination did not advance revision cursor".into()));
        }
    }
    Err(TransportError::Server("pull exceeded maximum page count".into()))
}

/// Only the download half: applies every change after the cursor, keeping unsynced words as copies.
pub fn pull_only(
    state: &mut LocalState,
    transport: &mut dyn Transport,
    ids: &mut dyn IdSource,
) -> Result<SyncReport, SyncError> {
    let mut report = SyncReport::default();
    pull(state, transport, ids, &mut report)?;
    Ok(report)
}

/// Only the upload half: queued edits, then queued deletes.
pub fn push_only(
    state: &mut LocalState,
    transport: &mut dyn Transport,
    ids: &mut dyn IdSource,
) -> Result<SyncReport, SyncError> {
    if state.uploads_blocked_after_wipe {
        return Err(SyncError::Transport(TransportError::WipeGenerationStale));
    }
    let mut report = SyncReport::default();
    push_pending(state, transport, ids, &mut report)?;
    push_deletes(state, transport, &mut report)?;
    Ok(report)
}

/// One full sync: queued edits up, queued deletes up, everything new down, then the conflicted copies
/// the pull made. A transport error stops the run with every local change still queued.
pub fn sync(
    state: &mut LocalState,
    transport: &mut dyn Transport,
    ids: &mut dyn IdSource,
) -> Result<SyncReport, SyncError> {
    if state.uploads_blocked_after_wipe {
        return Err(SyncError::Transport(TransportError::WipeGenerationStale));
    }
    let mut report = SyncReport::default();
    // A device's first sync downloads first, to learn the account's wipe generation before an upload
    // presents one (a write with an old generation is refused, NL002).
    if never_synced(state) {
        pull(state, transport, ids, &mut report)?;
    }
    push_pending(state, transport, ids, &mut report)?;
    push_deletes(state, transport, &mut report)?;
    pull(state, transport, ids, &mut report)?;
    push_pending(state, transport, ids, &mut report)?;
    push_deletes(state, transport, &mut report)?;
    Ok(report)
}

/// Nothing this device holds has ever been confirmed by the cloud.
fn never_synced(state: &LocalState) -> bool {
    state.last_remote_revision == 0 && state.revisions.is_empty()
}
