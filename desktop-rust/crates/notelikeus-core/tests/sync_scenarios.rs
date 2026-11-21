//! Data-integrity scenarios against an in-memory server that applies the database's rules: one
//! revision sequence, a base-revision check on every write, tombstones, a wipe generation.
//! No scenario may lose words: a conflict ends with both versions somewhere.

use std::collections::BTreeMap;

use notelikeus_core::model::{ApplyNoteChangeArgs, CloudChange, CloudNoteRow, CloudTombstoneRow, LabelName};
use notelikeus_core::store::{LocalState, TombstoneOrigin};
use notelikeus_core::sync::{
    self, ApplyOutcome, DeleteOutcome, IdSource, PullPage, SyncError, Transport, TransportError,
};

#[derive(Default)]
struct Server {
    seq: i64,
    rows: BTreeMap<String, CloudNoteRow>,
    tombstones: BTreeMap<String, (i64, i64)>, // id -> (revision, deleted_at)
    wipe_generation: i64,
    offline: bool,
    /// Commit the next write but lose its reply, as a dropped connection would.
    lose_next_reply: bool,
}

impl Server {
    fn row_from(args: &ApplyNoteChangeArgs, revision: i64) -> CloudNoteRow {
        CloudNoteRow {
            note_id: args.p_note_id.clone(),
            local_id: args.p_local_id,
            revision: Some(revision),
            title: args.p_title.clone(),
            content: args.p_content.clone(),
            client_timestamp: args.p_client_timestamp,
            color: args.p_color,
            is_pinned: args.p_is_pinned,
            is_archived: args.p_is_archived,
            is_trashed: args.p_is_trashed,
            position: args.p_position,
            reminder_timestamp: args.p_reminder_timestamp,
            server_updated_at: Some(1_000_000 + revision),
            labels: args.p_labels.iter().map(|l| LabelName { name: l.name.clone() }).collect(),
            checklist: args.p_checklist.clone(),
        }
    }

    fn check(&mut self, wipe_generation: i64) -> Result<(), TransportError> {
        if self.offline {
            return Err(TransportError::Network("offline".into()));
        }
        if wipe_generation != self.wipe_generation {
            return Err(TransportError::WipeGenerationStale);
        }
        Ok(())
    }

    fn reply<T>(&mut self, value: T) -> Result<T, TransportError> {
        if std::mem::take(&mut self.lose_next_reply) {
            return Err(TransportError::Network("reply lost".into()));
        }
        Ok(value)
    }
}

/// A device: its local state and a handle on the shared server.
struct Device<'a> {
    state: LocalState,
    server: &'a std::cell::RefCell<Server>,
    clock: i64,
}

struct Link<'a>(&'a std::cell::RefCell<Server>);

impl Transport for Link<'_> {
    fn apply_note_change(&mut self, args: &ApplyNoteChangeArgs) -> Result<ApplyOutcome, TransportError> {
        let mut s = self.0.borrow_mut();
        s.check(args.p_wipe_generation)?;
        if s.tombstones.contains_key(&args.p_note_id) {
            return Ok(ApplyOutcome::Deleted);
        }
        match s.rows.get(&args.p_note_id) {
            Some(row) if args.p_base_revision != row.revision => {
                return Ok(ApplyOutcome::Conflict { current: Box::new(row.clone()) });
            }
            None if args.p_base_revision.is_some() => return Ok(ApplyOutcome::NotFound),
            _ => {}
        }
        s.seq += 1;
        let revision = s.seq;
        let row = Server::row_from(args, revision);
        let stamp = row.server_updated_at;
        s.rows.insert(args.p_note_id.clone(), row);
        s.reply(ApplyOutcome::Applied { revision, server_updated_at: stamp })
    }

    fn apply_note_delete(
        &mut self,
        id: &str,
        base: Option<i64>,
        wipe_generation: i64,
    ) -> Result<DeleteOutcome, TransportError> {
        let mut s = self.0.borrow_mut();
        s.check(wipe_generation)?;
        let Some(row) = s.rows.get(id).cloned() else { return Ok(DeleteOutcome::AlreadyGone) };
        if base != row.revision {
            return Ok(DeleteOutcome::Conflict { current: Box::new(row) });
        }
        s.rows.remove(id);
        s.seq += 1;
        let revision = s.seq;
        s.tombstones.insert(id.into(), (revision, 9_000));
        s.reply(DeleteOutcome::Deleted)
    }

    fn pull_changes(&mut self, after: i64, limit: u32) -> Result<PullPage, TransportError> {
        let s = self.0.borrow();
        if s.offline {
            return Err(TransportError::Network("offline".into()));
        }
        let mut changes: Vec<(i64, CloudChange)> = s
            .rows
            .values()
            .filter(|r| r.revision.unwrap() > after)
            .map(|r| (r.revision.unwrap(), CloudChange::Note(r.clone())))
            .chain(s.tombstones.iter().filter(|(_, (rev, _))| *rev > after).map(|(id, (rev, at))| {
                (
                    *rev,
                    CloudChange::Tombstone(CloudTombstoneRow {
                        note_id: id.clone(),
                        revision: Some(*rev),
                        deleted_at: Some(*at),
                    }),
                )
            }))
            .collect();
        changes.sort_by_key(|(rev, _)| *rev);
        let has_more = changes.len() > limit as usize;
        changes.truncate(limit as usize);
        Ok(PullPage {
            changes: changes.into_iter().map(|(_, c)| c).collect(),
            has_more,
            wipe_generation: s.wipe_generation,
        })
    }
}

struct Ids<'a>(&'a mut i64);

impl IdSource for Ids<'_> {
    fn now_millis(&mut self) -> i64 {
        *self.0 += 1;
        *self.0
    }
    fn random_below_1000(&mut self) -> u32 {
        7
    }
}

impl<'a> Device<'a> {
    fn new(server: &'a std::cell::RefCell<Server>, clock_start: i64) -> Self {
        Device { state: LocalState { owner_id: "owner".into(), ..Default::default() }, server, clock: clock_start }
    }
    fn sync(&mut self) -> Result<sync::SyncReport, SyncError> {
        sync::sync(&mut self.state, &mut Link(self.server), &mut Ids(&mut self.clock))
    }
    fn create(&mut self, title: &str, content: &str) -> String {
        sync::create_note(&mut self.state, &mut Ids(&mut self.clock), |n| {
            n.title = title.into();
            n.content = content.into();
        })
    }
    fn edit(&mut self, id: &str, content: &str) {
        self.clock += 10;
        sync::edit_note(&mut self.state, id, self.clock, |n| n.content = content.into()).unwrap();
    }
    fn delete(&mut self, id: &str) {
        self.clock += 10;
        sync::delete_note(&mut self.state, id, self.clock).unwrap();
    }
    fn contents(&self) -> Vec<String> {
        let mut all: Vec<String> = self.state.notes.values().map(|n| format!("{}: {}", n.title, n.content)).collect();
        all.sort();
        all
    }
}

fn server() -> std::cell::RefCell<Server> {
    std::cell::RefCell::new(Server::default())
}

fn cloud_contents(server: &std::cell::RefCell<Server>) -> Vec<String> {
    let mut all: Vec<String> = server.borrow().rows.values().map(|r| format!("{}: {}", r.title, r.content)).collect();
    all.sort();
    all
}

#[test]
fn a_note_created_offline_uploads_on_reconnect() {
    let s = server();
    let mut a = Device::new(&s, 1_000);
    s.borrow_mut().offline = true;
    a.create("Trip", "pack");
    assert!(matches!(a.sync(), Err(SyncError::Transport(TransportError::Network(_)))));
    assert_eq!(a.state.pending.len(), 1, "the edit stays queued while offline");
    s.borrow_mut().offline = false;
    a.sync().unwrap();
    assert!(a.state.pending.is_empty());
    assert_eq!(cloud_contents(&s), ["Trip: pack"]);
}

#[test]
fn concurrent_edits_keep_both_versions_on_both_devices() {
    let s = server();
    let mut a = Device::new(&s, 1_000);
    let mut b = Device::new(&s, 50_000);
    let id = a.create("Groceries", "milk");
    a.sync().unwrap();
    b.sync().unwrap();
    a.edit(&id, "milk, bread");
    b.edit(&id, "milk, eggs");
    a.sync().unwrap();
    let report = b.sync().unwrap();
    assert_eq!(report.conflicted_copies.len(), 1);
    a.sync().unwrap();
    let expected = ["Groceries (conflicted copy): milk, eggs", "Groceries: milk, bread"];
    assert_eq!(cloud_contents(&s), expected);
    assert_eq!(a.contents(), expected);
    assert_eq!(b.contents(), expected);
}

#[test]
fn an_offline_edit_of_a_note_deleted_elsewhere_survives_as_a_copy() {
    let s = server();
    let mut a = Device::new(&s, 1_000);
    let mut b = Device::new(&s, 50_000);
    let id = a.create("Plan", "draft");
    a.sync().unwrap();
    b.sync().unwrap();
    b.delete(&id);
    b.sync().unwrap();
    a.edit(&id, "draft, with more");
    a.sync().unwrap();
    assert_eq!(a.contents(), ["Plan (conflicted copy): draft, with more"]);
    assert_eq!(cloud_contents(&s), ["Plan (conflicted copy): draft, with more"]);
}

#[test]
fn a_pulled_tombstone_does_not_drop_a_pending_edit() {
    // AUD-I01 on the Rust side: the tombstone arrives by pull while the edit is still queued.
    let s = server();
    let mut a = Device::new(&s, 1_000);
    let mut b = Device::new(&s, 50_000);
    let id = a.create("Plan", "draft");
    a.sync().unwrap();
    b.sync().unwrap();
    b.delete(&id);
    b.sync().unwrap();
    a.edit(&id, "unsynced words");
    // The download runs before the queued edit is pushed (a realtime wake-up, say).
    let mut clock = a.clock;
    let pulled = sync::pull_only(&mut a.state, &mut Link(&s), &mut Ids(&mut clock)).unwrap();
    assert_eq!(pulled.conflicted_copies.len(), 1);
    assert_eq!(pulled.deleted_remotely, std::slice::from_ref(&id));
    assert_eq!(a.contents(), ["Plan (conflicted copy): unsynced words"]);
    sync::push_only(&mut a.state, &mut Link(&s), &mut Ids(&mut clock)).unwrap();
    assert_eq!(cloud_contents(&s), ["Plan (conflicted copy): unsynced words"]);
    assert_eq!(a.state.tombstones[&id].origin, TombstoneOrigin::Cloud);
}

#[test]
fn a_delete_loses_to_a_newer_edit_made_elsewhere() {
    let s = server();
    let mut a = Device::new(&s, 1_000);
    let mut b = Device::new(&s, 50_000);
    let id = a.create("Doc", "v1");
    a.sync().unwrap();
    b.sync().unwrap();
    b.edit(&id, "v2 from b");
    b.sync().unwrap();
    a.delete(&id);
    let report = a.sync().unwrap();
    assert_eq!(report.restored_by_cloud, std::slice::from_ref(&id));
    assert_eq!(a.contents(), ["Doc: v2 from b"]);
    assert_eq!(cloud_contents(&s), ["Doc: v2 from b"]);
}

#[test]
fn a_lost_reply_is_retried_without_a_duplicate() {
    let s = server();
    let mut a = Device::new(&s, 1_000);
    let id = a.create("Note", "one");
    a.sync().unwrap();
    a.edit(&id, "two");
    s.borrow_mut().lose_next_reply = true;
    assert!(a.sync().is_err(), "the reply was lost, so the push is not known to have landed");
    let report = a.sync().unwrap();
    assert!(report.conflicted_copies.is_empty(), "same words in the cloud: no copy");
    assert_eq!(a.contents(), ["Note: two"]);
    assert_eq!(cloud_contents(&s), ["Note: two"]);
    assert!(a.state.pending.is_empty());
}

#[test]
fn repeating_a_sync_changes_nothing() {
    let s = server();
    let mut a = Device::new(&s, 1_000);
    a.create("A", "1");
    a.create("B", "2");
    a.sync().unwrap();
    let before = (a.state.clone(), s.borrow().seq);
    for _ in 0..3 {
        let report = a.sync().unwrap();
        assert!(report.pushed.is_empty() && report.conflicted_copies.is_empty());
    }
    assert_eq!((a.state.clone(), s.borrow().seq), before);
}

#[test]
fn a_wipe_elsewhere_blocks_uploads_and_keeps_local_notes() {
    let s = server();
    let mut a = Device::new(&s, 1_000);
    let id = a.create("Mine", "keep me");
    a.sync().unwrap();
    {
        let mut server = s.borrow_mut();
        server.rows.clear();
        server.wipe_generation = 1;
    }
    a.edit(&id, "still here");
    let result = a.sync();
    assert!(matches!(result, Err(SyncError::Transport(TransportError::WipeGenerationStale))));
    assert_eq!(a.contents(), ["Mine: still here"], "nothing is deleted or uploaded behind the user's back");
    assert!(cloud_contents(&s).is_empty());
}

#[test]
fn paging_reaches_every_change() {
    let s = server();
    let mut a = Device::new(&s, 1_000);
    for i in 0..250 {
        a.create(&format!("n{i:03}"), "x");
    }
    a.sync().unwrap();
    let mut b = Device::new(&s, 900_000);
    let report = b.sync().unwrap();
    assert_eq!(report.pulled, 250);
    assert_eq!(b.state.notes.len(), 250);
    assert_eq!(b.state.last_remote_revision, s.borrow().seq);
}

#[test]
fn a_push_never_moves_the_pull_cursor() {
    // F-04: a device that pushes revision 5 must still pull revisions 1-4 written by others.
    let s = server();
    let mut a = Device::new(&s, 1_000);
    let mut b = Device::new(&s, 50_000);
    a.create("from a", "1");
    a.sync().unwrap();
    b.create("from b", "2");
    // b pushes first: its write takes revision 2, above a's revision 1 that b has not pulled yet.
    let mut clock = b.clock;
    sync::push_only(&mut b.state, &mut Link(&s), &mut Ids(&mut clock)).unwrap();
    assert_eq!(b.state.last_remote_revision, 0, "the push left the cursor alone");
    sync::pull_only(&mut b.state, &mut Link(&s), &mut Ids(&mut clock)).unwrap();
    assert!(b.contents().contains(&"from a: 1".to_string()));
}
