//! The Rust core against the same `contracts/` fixtures the Kotlin and web suites read, so the three
//! clients cannot drift apart on the wire format or the sync rules without a failing test.

use std::path::PathBuf;

use notelikeus_core::conflict::{self, InboundAction};
use notelikeus_core::model::{ApplyNoteChangeArgs, ChecklistItem, CloudChange, CloudNoteRow, Note};
use serde_json::Value;

fn contracts_dir() -> PathBuf {
    let mut dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    loop {
        let candidate = dir.join("contracts");
        if candidate.join("README.md").exists() {
            return candidate;
        }
        assert!(dir.pop(), "contracts/ not found above {}", env!("CARGO_MANIFEST_DIR"));
    }
}

fn fixture(path: &str) -> Value {
    let text = std::fs::read_to_string(contracts_dir().join(path)).unwrap_or_else(|e| panic!("{path}: {e}"));
    serde_json::from_str(&text).unwrap()
}

/// The client-neutral normal form of `contracts/README.md`.
fn normal_form(note: &Note) -> Value {
    serde_json::json!({
        "localId": note.local_id,
        "title": note.title,
        "content": note.content,
        "timestamp": note.timestamp,
        "color": note.color,
        "isPinned": note.is_pinned,
        "isArchived": note.is_archived,
        "isTrashed": note.is_trashed,
        "position": note.position,
        "reminderTimestamp": note.reminder_timestamp,
        "serverUpdatedAt": note.server_updated_at,
        "labels": note.labels,
        "checklist": note.checklist,
    })
}

#[test]
fn cloud_rows_map_to_the_expected_notes() {
    for path in ["cloud/note-row.json", "cloud/note-row-sparse.json"] {
        let fixture = fixture(path);
        let row: CloudNoteRow = serde_json::from_value(fixture["row"].clone()).unwrap();
        assert_eq!(normal_form(&row.to_note()), fixture["expected"], "{path}");
    }
}

#[test]
fn a_row_parses_as_a_typed_change() {
    let fixture = fixture("cloud/note-row.json");
    let change: CloudChange = serde_json::from_value(fixture["row"].clone()).unwrap();
    assert!(matches!(change, CloudChange::Note(row) if row.revision == Some(41)));
}

#[test]
fn apply_note_change_arguments_match_the_contract() {
    let row: CloudNoteRow = serde_json::from_value(fixture("cloud/note-row.json")["row"].clone()).unwrap();
    let args = ApplyNoteChangeArgs::new(&row.to_note(), None, Vec::new(), 0);
    assert_eq!(serde_json::to_value(args).unwrap(), fixture("cloud/note-rpc-args.json")["args"]);
}

#[test]
fn tombstone_rows_give_the_expected_deleted_at_map() {
    let fixture = fixture("cloud/tombstone-row.json");
    let mut map = serde_json::Map::new();
    for row in fixture["rows"].as_array().unwrap() {
        match serde_json::from_value::<CloudChange>(row.clone()).unwrap() {
            CloudChange::Tombstone(t) => {
                map.insert(t.note_id, t.deleted_at.unwrap().into());
            }
            CloudChange::Note(_) => panic!("a tombstone row parsed as a note"),
        }
    }
    assert_eq!(Value::Object(map), fixture["expected"]);
}

fn side(value: &Value) -> Option<Note> {
    if value.is_null() {
        return None;
    }
    let mut note = Note::new(1, value["timestamp"].as_i64().unwrap());
    note.title = value["title"].as_str().unwrap().into();
    note.content = value["content"].as_str().unwrap().into();
    note.server_updated_at = value["serverUpdatedAt"].as_i64();
    Some(note)
}

#[test]
fn every_inbound_resolution_case_resolves_as_the_contract_says() {
    let fixture = fixture("sync/inbound-resolution.json");
    let mut mismatches = Vec::new();
    for case in fixture["cases"].as_array().unwrap() {
        let local = side(&case["local"]);
        let synced = case["synced"].as_i64();
        let actual = match side(&case["cloud"]) {
            None => {
                let edited = local.as_ref().is_some_and(|l| conflict::is_locally_edited(l, synced));
                if edited { "fork-then-delete" } else { "delete" }
            }
            Some(cloud) => match conflict::decide(local.as_ref(), synced, &cloud) {
                InboundAction::Accept => "accept",
                InboundAction::KeepLocal => "keep-local",
                InboundAction::Fork => "fork",
            },
        };
        if actual != case["expected"].as_str().unwrap() {
            mismatches.push(format!("{}: expected {}, got {actual}", case["name"], case["expected"]));
        }
    }
    assert!(mismatches.is_empty(), "{mismatches:#?}");
}

#[test]
fn owner_tags_match_every_client() {
    for vector in fixture("diagnostics/owner-tag-vectors.json")["vectors"].as_array().unwrap() {
        let owner = vector["ownerId"].as_str().unwrap();
        assert_eq!(conflict::owner_tag(Some(owner)), vector["tag"].as_str().unwrap(), "{owner}");
    }
    assert_eq!(conflict::owner_tag(None), "none");
}

#[test]
fn content_digests_match_the_kotlin_and_web_vectors() {
    // Vectors from VerifiedDeleteTest.kt / verifiedDelete.test.ts.
    let mut groceries = Note::new(1, 0);
    groceries.title = "Groceries".into();
    groceries.content = "milk\nbread".into();
    assert_eq!(conflict::authored_content_digest(&groceries), "v2:1ue18w28pk2");

    let mut unicode = Note::new(2, 0);
    unicode.title = "Café ✓ 日本".into();
    unicode.checklist = vec![
        ChecklistItem { text: "eggs".into(), is_checked: true, position: 1 },
        ChecklistItem { text: "oat milk".into(), is_checked: false, position: 0 },
    ];
    unicode.labels = vec!["home".into(), "Errands".into()];
    assert_eq!(conflict::authored_content_digest(&unicode), "v2:18lg26bb6hd");
}

#[test]
fn a_conflicted_copy_keeps_the_words_under_a_new_id() {
    let mut note = Note::new(5, 10);
    note.title = "Plan".into();
    note.server_updated_at = Some(99);
    let copy = conflict::conflicted_copy_of(&note, 6);
    assert_eq!((copy.id.as_str(), copy.title.as_str(), copy.server_updated_at), ("6", "Plan (conflicted copy)", None));
    assert!(conflict::is_conflicted_copy_title(&copy.title));
    assert_eq!(conflict::conflicted_copy_of(&Note::new(5, 1), 7).title, "Conflicted copy");
    // A title at the server's limit still fits once the suffix is added.
    note.title = "x".repeat(2000);
    assert_eq!(conflict::conflicted_copy_of(&note, 8).title.encode_utf16().count(), 2000);
}
