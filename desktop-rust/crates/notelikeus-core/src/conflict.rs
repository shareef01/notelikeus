//! Concurrent-edit rules, ported from Kotlin `ConflictResolution` and `NoteSyncEngine.cloudWinsConflict`
//! and pinned by `contracts/sync/inbound-resolution.json`. Nothing is merged and nothing is discarded:
//! when the cloud moved on and this device holds an unsynced edit with different words, the cloud
//! keeps the note and the local words become a "(conflicted copy)".

use crate::model::Note;

pub const COPY_SUFFIX: &str = " (conflicted copy)";
pub const BLANK_COPY_TITLE: &str = "Conflicted copy";
/// `validate_note_payload` rejects longer titles (UTF-16 units); a copy that can never upload is not preserved.
const MAX_TITLE_LENGTH: usize = 2000;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum InboundAction {
    /// Write the cloud version.
    Accept,
    /// Keep the local row; it is newer, or a pending edit on the revision the cloud still holds.
    KeepLocal,
    /// Re-home the local words as a conflicted copy, then write the cloud version.
    Fork,
}

/// Exact against the synced baseline (equality, so a skewed clock cannot fake an edit). Without one,
/// "edited after the last server stamp the row saw".
pub fn is_locally_edited(local: &Note, synced_timestamp: Option<i64>) -> bool {
    match synced_timestamp {
        Some(synced) => local.timestamp != synced,
        None => local.server_updated_at.is_some_and(|stamp| local.timestamp > stamp),
    }
}

/// Same words: title, body, labels and checklist. Pin, colour, order and reminders do not count.
pub fn authored_content_equal(a: &Note, b: &Note) -> bool {
    let labels = |n: &Note| {
        let mut l = n.labels.clone();
        l.sort();
        l
    };
    let checklist = |n: &Note| {
        let mut c: Vec<_> = n.checklist.iter().map(|i| (i.position, i.is_checked, i.text.clone())).collect();
        c.sort_by_key(|(p, _, _)| *p);
        c.into_iter().map(|(_, checked, text)| (checked, text)).collect::<Vec<_>>()
    };
    a.title == b.title && a.content == b.content && labels(a) == labels(b) && checklist(a) == checklist(b)
}

/// Whether the server stamps (and, on a tie, the client clocks) favour the cloud copy.
pub fn cloud_wins_by_stamp(
    remote_server_updated_at: Option<i64>,
    local_server_updated_at: Option<i64>,
    remote_client_timestamp: Option<i64>,
    local_client_timestamp: i64,
) -> bool {
    match (remote_server_updated_at, local_server_updated_at) {
        (Some(remote), Some(local)) if remote != local => remote > local,
        (Some(_), Some(_)) => remote_client_timestamp.is_some_and(|r| r >= local_client_timestamp),
        // Exactly one side confirmed by the server wins outright: a client clock is spoofable.
        (Some(_), None) => true,
        (None, Some(_)) => false,
        (None, None) => remote_client_timestamp.is_some_and(|r| r > local_client_timestamp),
    }
}

/// The decision for one inbound cloud record.
pub fn decide(local: Option<&Note>, synced_timestamp: Option<i64>, cloud: &Note) -> InboundAction {
    let Some(local) = local else { return InboundAction::Accept };
    let edited = is_locally_edited(local, synced_timestamp);
    if edited && cloud.server_updated_at.is_some() && cloud.server_updated_at == local.server_updated_at {
        return InboundAction::KeepLocal;
    }
    if !cloud_wins_by_stamp(cloud.server_updated_at, local.server_updated_at, Some(cloud.timestamp), local.timestamp) {
        return InboundAction::KeepLocal;
    }
    if edited && !authored_content_equal(local, cloud) { InboundAction::Fork } else { InboundAction::Accept }
}

fn utf16_len(s: &str) -> usize {
    s.encode_utf16().count()
}

fn append_framed(key: &mut String, value: &str) {
    key.push_str(&utf16_len(value).to_string());
    key.push(':');
    key.push_str(value);
}

/// Fingerprint of a note's words, identical to Kotlin and web `authoredContentDigest` (cyrb53 over
/// UTF-16 code units, "v2:" + base 36). Recorded with a delete made before the revision was known.
pub fn authored_content_digest(note: &Note) -> String {
    let mut key = String::from("v2|");
    append_framed(&mut key, &note.title);
    append_framed(&mut key, &note.content);
    let mut items: Vec<_> = note.checklist.iter().collect();
    items.sort_by_key(|i| i.position);
    key.push_str(&items.len().to_string());
    key.push(':');
    for item in items {
        key.push(if item.is_checked { '1' } else { '0' });
        append_framed(&mut key, &item.text);
    }
    let mut labels = note.labels.clone();
    labels.sort();
    key.push_str(&labels.len().to_string());
    key.push(':');
    for label in &labels {
        append_framed(&mut key, label);
    }

    let mut h1: i32 = 0xdeadbeef_u32 as i32;
    let mut h2: i32 = 0x41c6ce57;
    for unit in key.encode_utf16() {
        let c = i32::from(unit);
        h1 = (h1 ^ c).wrapping_mul(-1_640_531_535);
        h2 = (h2 ^ c).wrapping_mul(1_597_334_677);
    }
    let ushr = |x: i32, n: u32| ((x as u32) >> n) as i32;
    h1 = (h1 ^ ushr(h1, 16)).wrapping_mul(-2_048_144_789) ^ (h2 ^ ushr(h2, 13)).wrapping_mul(-1_028_477_387);
    h2 = (h2 ^ ushr(h2, 16)).wrapping_mul(-2_048_144_789) ^ (h1 ^ ushr(h1, 13)).wrapping_mul(-1_028_477_387);
    let value: u64 = 4_294_967_296_u64 * (2_097_151_u64 & (h2 as i64 as u64)) + (h1 as u32 as u64);
    format!("v2:{}", to_base36(value))
}

fn to_base36(mut value: u64) -> String {
    if value == 0 {
        return "0".into();
    }
    let digits = b"0123456789abcdefghijklmnopqrstuvwxyz";
    let mut out = Vec::new();
    while value > 0 {
        out.push(digits[(value % 36) as usize]);
        value /= 36;
    }
    out.reverse();
    String::from_utf8(out).expect("ascii")
}

pub fn is_conflicted_copy_title(title: &str) -> bool {
    title == BLANK_COPY_TITLE || title.ends_with(COPY_SUFFIX)
}

/// A new, never-synced note carrying `local`'s words under `new_id`.
pub fn conflicted_copy_of(local: &Note, new_id: i64) -> Note {
    let title = if local.title.trim().is_empty() {
        BLANK_COPY_TITLE.to_string()
    } else {
        let keep = MAX_TITLE_LENGTH - utf16_len(COPY_SUFFIX);
        let mut taken = String::new();
        let mut units = 0;
        for ch in local.title.chars() {
            units += ch.len_utf16();
            if units > keep {
                break;
            }
            taken.push(ch);
        }
        taken + COPY_SUFFIX
    };
    Note { id: new_id.to_string(), local_id: new_id, title, server_updated_at: None, ..local.clone() }
}

/// The redacted account tag diagnostics carry (FNV-1a 32 over UTF-16 units), as on every client.
pub fn owner_tag(owner_id: Option<&str>) -> String {
    match owner_id {
        None | Some("") => "none".into(),
        Some("__guest__") => "guest".into(),
        Some(id) => {
            let mut hash: u32 = 0x811c9dc5;
            for unit in id.encode_utf16() {
                hash ^= u32::from(unit);
                hash = hash.wrapping_mul(0x0100_0193);
            }
            format!("acct-{hash:08x}")
        }
    }
}
