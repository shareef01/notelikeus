//! The note model and its two wire shapes: the row `pull_changes` / `fetch_full_snapshot` return,
//! and the argument object `apply_note_change` takes. Both are pinned by `contracts/cloud/`, which
//! the Kotlin and web clients read too, so a field renamed or defaulted differently here fails a test.

use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChecklistItem {
    pub text: String,
    pub is_checked: bool,
    pub position: i64,
}

/// One note, as this client stores it. `id` is the cloud id (text); it is also the note's local id,
/// allocated by [`allocate_note_id`] with the same rule as Kotlin `NoteIdAllocator` and the web's
/// `nextLocalNoteIdAfter`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Note {
    pub id: String,
    pub local_id: i64,
    pub title: String,
    pub content: String,
    /// Client timestamp of the last edit (epoch millis). Re-stamped by every local edit.
    pub timestamp: i64,
    /// ARGB colour as a signed 32-bit int, as every client stores it.
    pub color: i32,
    pub is_pinned: bool,
    pub is_archived: bool,
    pub is_trashed: bool,
    pub position: i64,
    pub reminder_timestamp: Option<i64>,
    /// Set only by the server. `None` means the cloud has never confirmed this version.
    pub server_updated_at: Option<i64>,
    pub labels: Vec<String>,
    pub checklist: Vec<ChecklistItem>,
}

impl Note {
    pub fn new(id: i64, now: i64) -> Self {
        Note {
            id: id.to_string(),
            local_id: id,
            title: String::new(),
            content: String::new(),
            timestamp: now,
            color: 0,
            is_pinned: false,
            is_archived: false,
            is_trashed: false,
            position: 0,
            reminder_timestamp: None,
            server_updated_at: None,
            labels: Vec::new(),
            checklist: Vec::new(),
        }
    }
}

/// Picks the id of a note this device creates: epoch millis × 1000 plus a random suffix below 1000,
/// skipping ids still taken. The id is the note's id in the cloud too, so it must never reuse one the
/// account had (a reused id meets the old note's tombstone and is read as deleted).
pub fn allocate_note_id(now_millis: i64, random_below_1000: u32, is_taken: impl Fn(i64) -> bool) -> i64 {
    let mut id = now_millis * 1000 + i64::from(random_below_1000 % 1000);
    while is_taken(id) {
        id += 1;
    }
    id
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct LabelName {
    pub name: String,
}

/// A note row from `pull_changes` / `fetch_full_snapshot` (`contracts/cloud/note-row.json`).
#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
pub struct CloudNoteRow {
    pub note_id: String,
    pub local_id: i64,
    pub revision: Option<i64>,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub content: String,
    pub client_timestamp: i64,
    #[serde(default)]
    pub color: i32,
    #[serde(default)]
    pub is_pinned: bool,
    #[serde(default)]
    pub is_archived: bool,
    #[serde(default)]
    pub is_trashed: bool,
    #[serde(default)]
    pub position: i64,
    pub reminder_timestamp: Option<i64>,
    pub server_updated_at: Option<i64>,
    #[serde(default)]
    pub labels: Vec<LabelName>,
    #[serde(default)]
    pub checklist: Vec<ChecklistItem>,
}

impl CloudNoteRow {
    pub fn to_note(&self) -> Note {
        let mut labels: Vec<String> = self.labels.iter().map(|l| l.name.clone()).collect();
        labels.sort();
        let mut checklist = self.checklist.clone();
        checklist.sort_by_key(|item| item.position);
        Note {
            id: self.note_id.clone(),
            local_id: self.local_id,
            title: self.title.clone(),
            content: self.content.clone(),
            timestamp: self.client_timestamp,
            color: self.color,
            is_pinned: self.is_pinned,
            is_archived: self.is_archived,
            is_trashed: self.is_trashed,
            position: self.position,
            reminder_timestamp: self.reminder_timestamp,
            server_updated_at: self.server_updated_at,
            labels,
            checklist,
        }
    }
}

/// A tombstone row (`contracts/cloud/tombstone-row.json`).
#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
pub struct CloudTombstoneRow {
    pub note_id: String,
    pub revision: Option<i64>,
    pub deleted_at: Option<i64>,
}

/// One entry of a `pull_changes` page: the `type` field tells a tombstone from a note.
#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum CloudChange {
    Note(CloudNoteRow),
    Tombstone(CloudTombstoneRow),
}

/// The `apply_note_change` argument object (`contracts/cloud/note-rpc-args.json`).
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct ApplyNoteChangeArgs {
    pub p_note_id: String,
    pub p_local_id: i64,
    pub p_base_revision: Option<i64>,
    pub p_title: String,
    pub p_content: String,
    pub p_client_timestamp: i64,
    pub p_color: i32,
    pub p_is_pinned: bool,
    pub p_is_archived: bool,
    pub p_is_trashed: bool,
    pub p_position: i64,
    pub p_reminder_timestamp: Option<i64>,
    pub p_labels: Vec<LabelName>,
    pub p_checklist: Vec<ChecklistItem>,
    pub p_attachment_ids: Vec<String>,
    pub p_wipe_generation: i64,
}

impl ApplyNoteChangeArgs {
    pub fn new(note: &Note, base_revision: Option<i64>, attachment_ids: Vec<String>, wipe_generation: i64) -> Self {
        ApplyNoteChangeArgs {
            p_note_id: note.id.clone(),
            p_local_id: note.local_id,
            p_base_revision: base_revision,
            p_title: note.title.clone(),
            p_content: note.content.clone(),
            p_client_timestamp: note.timestamp,
            p_color: note.color,
            p_is_pinned: note.is_pinned,
            p_is_archived: note.is_archived,
            p_is_trashed: note.is_trashed,
            p_position: note.position,
            p_reminder_timestamp: note.reminder_timestamp,
            p_labels: note.labels.iter().map(|name| LabelName { name: name.clone() }).collect(),
            p_checklist: note.checklist.clone(),
            p_attachment_ids: attachment_ids,
            p_wipe_generation: wipe_generation,
        }
    }
}
