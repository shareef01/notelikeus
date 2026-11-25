//! The Rust client against a real Supabase stack: Auth, PostgREST, row-level security and the
//! revision-checked RPCs from `supabase/migrations`.
//!
//! Only for a LOCAL stack (`npx supabase start`). It creates throwaway accounts, so it refuses any URL
//! that is not this machine. Without the variables below every test returns early and says so.
//!
//!   NLK_TEST_SUPABASE_URL=http://127.0.0.1:54326
//!   NLK_TEST_SUPABASE_KEY=<local anon or publishable key>
//!   NLK_TEST_MAILPIT_URL=http://127.0.0.1:54324      (for the emailed-code test)
//!
//!   cargo test -p notelikeus-core --all-features --test live_supabase -- --test-threads=1
#![cfg(feature = "http")]

use notelikeus_core::auth::{AuthClient, AuthError};
use notelikeus_core::http::SupabaseTransport;
use notelikeus_core::model::Note;
use notelikeus_core::store::{LocalState, Session};
use notelikeus_core::sync::{self, IdSource, Transport, TransportError};
use serde_json::Value;

struct Live {
    url: String,
    key: String,
}

fn live() -> Option<Live> {
    let url = std::env::var("NLK_TEST_SUPABASE_URL").ok()?;
    let key = std::env::var("NLK_TEST_SUPABASE_KEY").ok()?;
    let host_ok = url.starts_with("http://127.0.0.1:") || url.starts_with("http://localhost:");
    assert!(host_ok, "live tests create accounts and only run against a local stack, not {url}");
    Some(Live { url, key })
}

macro_rules! live_or_skip {
    () => {
        match live() {
            Some(live) => live,
            None => {
                eprintln!("skipped: set NLK_TEST_SUPABASE_URL and NLK_TEST_SUPABASE_KEY to run against a local stack");
                return;
            }
        }
    };
}

struct Clock(i64);
impl IdSource for Clock {
    fn now_millis(&mut self) -> i64 {
        self.0 += 1;
        self.0
    }
    fn random_below_1000(&mut self) -> u32 {
        (self.0 % 997) as u32
    }
}

fn unique(prefix: &str) -> String {
    let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos();
    format!("{prefix}-{nanos}@example.test")
}

fn account(live: &Live, prefix: &str) -> Session {
    AuthClient::new(&live.url, &live.key).unwrap().sign_up(&unique(prefix), "a-long-test-password-1").unwrap()
}

/// One device signed in to an account: its local state and a transport with its token.
struct Device {
    state: LocalState,
    transport: SupabaseTransport,
    clock: Clock,
}

impl Device {
    fn new(live: &Live, session: &Session, clock_start: i64) -> Device {
        Device {
            state: LocalState { owner_id: session.user_id.clone(), ..Default::default() },
            transport: SupabaseTransport::new(&live.url, &live.key, &session.access_token).unwrap(),
            clock: Clock(clock_start),
        }
    }
    fn sync(&mut self) -> sync::SyncReport {
        sync::sync(&mut self.state, &mut self.transport, &mut self.clock).unwrap()
    }
    fn create(&mut self, title: &str, content: &str) -> String {
        sync::create_note(&mut self.state, &mut self.clock, |n| {
            n.title = title.into();
            n.content = content.into();
        })
    }
    fn edit(&mut self, id: &str, content: &str) {
        let now = self.clock.now_millis() + 1_000;
        sync::edit_note(&mut self.state, id, now, |n| n.content = content.into()).unwrap();
    }
    fn contents(&self) -> Vec<String> {
        let mut all: Vec<String> =
            self.state.notes.values().map(|n: &Note| format!("{}: {}", n.title, n.content)).collect();
        all.sort();
        all
    }
}

#[test]
fn two_devices_on_one_account_converge_and_keep_both_versions() {
    let live = live_or_skip!();
    let session = account(&live, "nlk-rust-a");
    let mut laptop = Device::new(&live, &session, 1_000_000);
    let mut desktop = Device::new(&live, &session, 9_000_000);

    let groceries = laptop.create("Groceries", "milk");
    let trip = laptop.create("Trip", "pack");
    laptop.sync();
    desktop.sync();
    assert_eq!(desktop.contents(), ["Groceries: milk", "Trip: pack"], "a fresh device downloads everything");

    // Concurrent edits: the second to reach the server keeps its words as a copy.
    laptop.edit(&groceries, "milk, bread");
    desktop.edit(&groceries, "milk, eggs");
    laptop.sync();
    let report = desktop.sync();
    assert_eq!(report.conflicted_copies.len(), 1);
    laptop.sync();
    let both = ["Groceries (conflicted copy): milk, eggs", "Groceries: milk, bread", "Trip: pack"];
    assert_eq!(laptop.contents(), both);
    assert_eq!(desktop.contents(), both);

    // Deleted on one device while edited on the other: the edit survives as a copy.
    sync::delete_note(&mut laptop.state, &trip, laptop.clock.now_millis()).unwrap();
    laptop.sync();
    desktop.edit(&trip, "pack, passport");
    desktop.sync();
    laptop.sync();
    let after =
        ["Groceries (conflicted copy): milk, eggs", "Groceries: milk, bread", "Trip (conflicted copy): pack, passport"];
    assert_eq!(desktop.contents(), after);
    assert_eq!(laptop.contents(), after);

    // Repeating a sync changes nothing.
    let before = laptop.state.clone();
    let again = laptop.sync();
    assert!(again.pushed.is_empty() && again.conflicted_copies.is_empty());
    assert_eq!(laptop.state.notes, before.notes);
}

#[test]
fn what_the_rust_client_writes_is_what_the_other_clients_read() {
    let live = live_or_skip!();
    let session = account(&live, "nlk-rust-interop");
    let mut device = Device::new(&live, &session, 2_000_000);
    device.create("From Rust", "written by the Rust client");
    device.sync();

    // `fetch_full_snapshot` is the read the web and Kotlin clients start from.
    let snapshot: Value = ureq::post(&format!("{}/rest/v1/rpc/fetch_full_snapshot", live.url))
        .set("apikey", &live.key)
        .set("Authorization", &format!("Bearer {}", session.access_token))
        .send_json(serde_json::json!({}))
        .unwrap()
        .into_json()
        .unwrap();
    let notes = snapshot["notes"].as_array().unwrap();
    assert_eq!(snapshot["note_count"], 1);
    assert_eq!(notes[0]["title"], "From Rust");
    assert_eq!(notes[0]["content"], "written by the Rust client");
    assert!(notes[0]["server_updated_at"].as_i64().is_some(), "the server stamped it");
}

#[test]
fn accounts_cannot_see_or_touch_each_others_notes() {
    let live = live_or_skip!();
    let alice = account(&live, "nlk-rust-alice");
    let bob = account(&live, "nlk-rust-bob");
    let mut alices = Device::new(&live, &alice, 3_000_000);
    let id = alices.create("Private", "alice's words");
    alices.sync();

    let mut bobs = Device::new(&live, &bob, 3_000_000);
    bobs.sync();
    assert!(bobs.contents().is_empty(), "row-level security hides another account's notes");

    // Bob writes a note under the very same id: it becomes Bob's own note, not a change to Alice's.
    sync::create_note(&mut bobs.state, &mut Clock(0), |_| {});
    let mut forged = bobs.state.notes.values().next().unwrap().clone();
    bobs.state.notes.clear();
    bobs.state.pending.clear();
    forged.id = id.clone();
    forged.local_id = id.parse().unwrap();
    forged.title = "Private".into();
    forged.content = "bob's words".into();
    bobs.state.notes.insert(id.clone(), forged);
    bobs.state.pending.insert(id.clone(), notelikeus_core::store::PendingPush { base_revision: None, seq: 1 });
    bobs.sync();

    alices.sync();
    assert_eq!(alices.contents(), ["Private: alice's words"]);
}

#[test]
fn a_bad_token_is_unauthorized_and_a_refresh_gives_a_working_one() {
    let live = live_or_skip!();
    let mut bogus = SupabaseTransport::new(&live.url, &live.key, "not-a-token").unwrap();
    assert_eq!(bogus.pull_changes(0, 1).unwrap_err(), TransportError::Unauthorized);

    let auth = AuthClient::new(&live.url, &live.key).unwrap();
    let session = account(&live, "nlk-rust-refresh");
    let renewed = auth.refresh(session.refresh_token.as_deref().unwrap()).unwrap();
    assert_eq!(renewed.user_id, session.user_id);
    let mut transport = SupabaseTransport::new(&live.url, &live.key, &renewed.access_token).unwrap();
    assert!(transport.pull_changes(0, 1).is_ok());
}

/// The product's sign-in: an emailed code, read here from the local mail catcher. The local stack
/// sends at most two emails an hour, so a run that hits the limit says so instead of failing.
#[test]
fn signing_in_with_an_emailed_code() {
    let live = live_or_skip!();
    let Ok(mailpit) = std::env::var("NLK_TEST_MAILPIT_URL") else {
        eprintln!("skipped: set NLK_TEST_MAILPIT_URL to read the emailed code");
        return;
    };
    let auth = AuthClient::new(&live.url, &live.key).unwrap();
    let email = unique("nlk-rust-otp");
    match auth.request_email_code(&email, None) {
        Err(AuthError::RateLimited) => {
            eprintln!("skipped: the local stack's email limit (2 an hour) is used up");
            return;
        }
        other => other.unwrap(),
    }

    let code = (0..40)
        .find_map(|_| {
            std::thread::sleep(std::time::Duration::from_millis(250));
            let found: Value = ureq::get(&format!("{mailpit}/api/v1/search"))
                .query("query", &format!("to:{email}"))
                .call()
                .ok()?
                .into_json()
                .ok()?;
            let id = found["messages"].as_array()?.first()?["ID"].as_str()?.to_string();
            let message: Value = ureq::get(&format!("{mailpit}/api/v1/message/{id}")).call().ok()?.into_json().ok()?;
            let text =
                message["Text"].as_str().unwrap_or_default().to_string() + message["HTML"].as_str().unwrap_or_default();
            text.split(|c: char| !c.is_ascii_digit()).find(|run| run.len() == 6).map(str::to_string)
        })
        .expect("the code email arrived");

    assert!(
        matches!(auth.verify_email_code(&email, "000000"), Err(AuthError::InvalidCode | AuthError::Refused { .. }))
            || code == "000000"
    );
    let session = auth.verify_email_code(&email, &code).unwrap();
    assert_eq!(session.email.as_deref(), Some(email.as_str()));
    let mut device = Device::new(&live, &session, 4_000_000);
    device.create("Signed in by code", "it works");
    device.sync();
    assert!(device.state.pending.is_empty());
}

#[test]
fn positioned_image_syncs_across_devices_and_survives_legacy_client_edits() {
    use std::collections::{BTreeMap, BTreeSet};

    use notelikeus_core::attachments::{Attachment, Location, PlacementBody, merge_listing};
    use notelikeus_core::model::ApplyNoteChangeArgs;
    use serde_json::json;

    let live = live_or_skip!();
    let session = account(&live, "nlk-rust-place");
    let mut device_a = Device::new(&live, &session, 5_000_000);
    let mut device_b = Device::new(&live, &session, 6_000_000);

    let body_text = "Before 🧭\n\nAfter";
    let note_id = device_a.create("Positioned note", body_text);
    device_a.sync();

    // Register a provisional upload row for this note as the attachments Worker does.
    let att_id = "att-live-place-1";
    let object_key = format!("owners/{}/notes/{note_id}/{att_id}", session.user_id);
    let agent = ureq::AgentBuilder::new().timeout(std::time::Duration::from_secs(15)).build();
    let finalize = agent
        .post(&format!("{}/rest/v1/rpc/finalize_note_attachment_put", live.url))
        .set("apikey", &live.key)
        .set("Authorization", &format!("Bearer {}", session.access_token))
        .set("Content-Type", "application/json")
        .send_json(json!({
            "p_note_id": note_id,
            "p_attachment_id": att_id,
            "p_object_key": object_key,
            "p_mime_type": "image/png",
            "p_size_bytes": 3,
            "p_provisional": true,
            "p_attachment_type": "image"
        }))
        .unwrap();
    assert_eq!(finalize.status(), 200);

    let placement = PlacementBody::new(body_text).at(10, 0).unwrap();
    device_a.state.attachments.insert(
        note_id.clone(),
        vec![Attachment {
            id: att_id.into(),
            mime_type: "image/png".into(),
            size_bytes: 3,
            location: Location::Uploaded { object_key: object_key.clone() },
            placement: Some(placement.clone()),
        }],
    );
    let now = device_a.clock.now_millis() + 1_000;
    sync::edit_note(&mut device_a.state, &note_id, now, |_| {}).unwrap();
    device_a.sync();

    // Device B syncs and hydrates the committed attachment + placement.
    device_b.sync();
    let remote = device_b.transport.list_user_attachments().unwrap();
    assert_eq!(remote.len(), 1);
    assert_eq!(remote[0].placement, Some(placement.clone()));
    let live_ids: BTreeSet<String> = device_b.state.notes.keys().cloned().collect();
    device_b.state.attachments =
        merge_listing(&device_b.state.attachments, &remote, &BTreeMap::new(), &BTreeSet::new(), &live_ids);
    assert_eq!(device_b.state.attachments[&note_id][0].placement, Some(placement.clone()));

    // Legacy client (16-arg apply_note_change without placements) edits only the title: placement stays valid.
    let mut legacy_note = device_b.state.notes[&note_id].clone();
    legacy_note.title = "Retitled by legacy client".into();
    legacy_note.timestamp += 1_000;
    let base_rev = device_b.state.revisions.get(&note_id).copied();
    let legacy_args = ApplyNoteChangeArgs::new(&legacy_note, base_rev, vec![att_id.into()], 0);
    let outcome = device_b.transport.apply_note_change(&legacy_args).unwrap();
    let next_rev = match outcome {
        sync::ApplyOutcome::Applied { revision, .. } => revision,
        other => panic!("expected Applied from legacy metadata edit, got {other:?}"),
    };
    let after_retitle = device_b.transport.list_user_attachments().unwrap();
    assert_eq!(after_retitle[0].placement, Some(placement.clone()));
    assert!(PlacementBody::new(&legacy_note.content).matches(after_retitle[0].placement.as_ref().unwrap()));

    // Legacy client edits the body: image stays visible, and stale digest falls back to the strip.
    legacy_note.content = "Body changed by legacy client".into();
    legacy_note.timestamp += 1_000;
    let legacy_body_args = ApplyNoteChangeArgs::new(&legacy_note, Some(next_rev), vec![att_id.into()], 0);
    device_b.transport.apply_note_change(&legacy_body_args).unwrap();
    let after_body = device_b.transport.list_user_attachments().unwrap();
    assert_eq!(after_body.len(), 1);
    assert!(!PlacementBody::new(&legacy_note.content).matches(after_body[0].placement.as_ref().unwrap()));

    // Device A pulls, clears the placement, and pushes `[]`: Device B sees placement cleared.
    device_a.sync();
    device_a.state.attachments.get_mut(&note_id).unwrap()[0].placement = None;
    let now2 = device_a.clock.now_millis() + 2_000;
    sync::edit_note(&mut device_a.state, &note_id, now2, |_| {}).unwrap();
    device_a.sync();
    let after_clear = device_b.transport.list_user_attachments().unwrap();
    assert_eq!(after_clear[0].placement, None);
    device_b.sync();
    let live_ids_b: BTreeSet<String> = device_b.state.notes.keys().cloned().collect();
    device_b.state.attachments =
        merge_listing(&device_b.state.attachments, &after_clear, &BTreeMap::new(), &BTreeSet::new(), &live_ids_b);
    assert_eq!(device_b.state.attachments[&note_id][0].placement, None);
}
