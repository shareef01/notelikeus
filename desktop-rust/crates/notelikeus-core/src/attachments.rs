//! Images on notes, as the other clients keep them.
//!
//! An image is *pending* while its bytes exist only on this device (sealed in the account's folder), and
//! *uploaded* once the attachments Worker has stored it in R2 under `owners/<owner>/notes/<note>/<id>`.
//! The note row itself never carries images: the server keeps one metadata row per upload, provisional
//! until a note commit names it (`p_attachment_ids`, R19.2), and `list_user_attachments` returns the
//! committed ones. So the images of a note are this device's pending ones plus whatever the server lists.
//!
//! Nothing here deletes an image the user did not remove: a note's pending images survive any cloud
//! version written over the note (Kotlin `keepingLocalOnlyAttachmentsOf`), and an uploaded image this
//! device removed stays hidden until its remote delete has gone through.

use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::path::PathBuf;

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::Error;
use crate::crypto::{self, KEY_LEN};

/// The editors' and the Worker's per-image cap.
pub const MAX_ATTACHMENT_BYTES: usize = 10 * 1024 * 1024;

/// Where an image's bytes are.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum Location {
    /// Only on this device, sealed in the account's attachments folder.
    Pending,
    /// In R2, under this object key.
    Uploaded { object_key: String },
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Attachment {
    pub id: String,
    pub mime_type: String,
    pub size_bytes: u64,
    pub location: Location,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub placement: Option<AttachmentPlacement>,
}

/// A position in the exact UTF-8 note body, counted in Unicode scalar values.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AttachmentPlacement {
    pub body_sha256: String,
    pub offset: u64,
    pub ordinal: u64,
}

pub struct PlacementBody {
    digest: String,
    offsets: BTreeSet<u64>,
}

impl PlacementBody {
    pub fn new(body: &str) -> Self {
        let mut lines = body.split('\n').peekable();
        let mut offsets = BTreeSet::new();
        let mut offset = 0;
        while let Some(line) = lines.next() {
            let next = lines.peek();
            offset += line.chars().count() as u64 + u64::from(next.is_some());
            if offset > 0 && !(line.starts_with("• ") && next.is_some_and(|line| line.starts_with("• "))) {
                offsets.insert(offset);
            }
        }
        Self { digest: format!("{:x}", Sha256::digest(body.as_bytes())), offsets }
    }

    pub fn matches(&self, placement: &AttachmentPlacement) -> bool {
        const MAX_SAFE_INTEGER: u64 = (1 << 53) - 1;
        placement.body_sha256 == self.digest
            && self.offsets.contains(&placement.offset)
            && placement.ordinal <= MAX_SAFE_INTEGER
    }

    pub fn is_boundary(&self, offset: u64) -> bool {
        self.offsets.contains(&offset)
    }

    pub fn at(&self, offset: u64, ordinal: u64) -> Option<AttachmentPlacement> {
        let placement = AttachmentPlacement { body_sha256: self.digest.clone(), offset, ordinal };
        self.matches(&placement).then_some(placement)
    }
}

/// The plain text editor saves snapshots. Keep boundaries that survive the changed span.
pub fn rebase_placements(images: &mut [Attachment], old: &str, new: &str) {
    if old == new || !images.iter().any(|image| image.placement.is_some()) {
        return;
    }
    let old_body = PlacementBody::new(old);
    let new_body = PlacementBody::new(new);
    let old_len = old.chars().count() as u64;
    let new_len = new.chars().count() as u64;
    let prefix = old.chars().zip(new.chars()).take_while(|(a, b)| a == b).count() as u64;
    let suffix = old
        .chars()
        .rev()
        .zip(new.chars().rev())
        .take((old_len.min(new_len) - prefix) as usize)
        .take_while(|(a, b)| a == b)
        .count() as u64;
    for image in images {
        let Some(placement) = image.placement.as_ref().filter(|placement| old_body.matches(placement)) else {
            continue;
        };
        let offset = placement.offset;
        let mapped = if offset == old_len && prefix == old_len && !old.ends_with('\n') {
            offset
                + new.chars().skip(offset as usize).position(|ch| ch == '\n').map_or(new_len - offset, |i| i as u64 + 1)
        } else if offset <= prefix {
            offset
        } else if offset > old_len - suffix {
            new_len - (old_len - offset)
        } else {
            continue;
        };
        // Retain the old digest when the boundary disappears so undo can recover it.
        if let Some(placement) = new_body.at(mapped, placement.ordinal) {
            image.placement = Some(placement);
        }
    }
}

impl Attachment {
    pub fn is_pending(&self) -> bool {
        self.location == Location::Pending
    }
}

/// One row of `list_user_attachments`.
#[derive(Clone, Debug, PartialEq, Deserialize)]
pub struct RemoteAttachment {
    pub attachment_id: String,
    pub note_id: String,
    pub object_key: String,
    #[serde(default)]
    pub mime_type: Option<String>,
    #[serde(default)]
    pub size_bytes: Option<f64>,
    #[serde(default)]
    pub placement: Option<AttachmentPlacement>,
}

/// The image types the Worker accepts, from the file's first bytes (never the file name). The ones the
/// app can show are PNG, JPEG, GIF and WebP; HEIC/HEIF/AVIF come from phones and are kept and synced.
pub fn sniff_mime(bytes: &[u8]) -> Option<&'static str> {
    if bytes.len() >= 8 && bytes.starts_with(&[0x89, b'P', b'N', b'G']) {
        Some("image/png")
    } else if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
        Some("image/jpeg")
    } else if bytes.starts_with(b"GIF87a") || bytes.starts_with(b"GIF89a") {
        Some("image/gif")
    } else if bytes.len() >= 12 && &bytes[..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        Some("image/webp")
    } else if bytes.len() >= 12 && &bytes[4..8] == b"ftyp" {
        match &bytes[8..12] {
            b"avif" | b"avis" => Some("image/avif"),
            b"heic" | b"heix" | b"heim" | b"heis" => Some("image/heic"),
            b"mif1" | b"msf1" => Some("image/heif"),
            _ => None,
        }
    } else {
        None
    }
}

/// Whether the app can draw this type itself.
pub fn can_preview(mime_type: &str) -> bool {
    matches!(mime_type, "image/png" | "image/jpeg" | "image/jpg" | "image/gif" | "image/webp")
}

/// A new attachment id: 32 random hex characters, inside the Worker's `[A-Za-z0-9._-]{1,128}`.
pub fn new_attachment_id() -> Result<String, Error> {
    Ok(crypto::random_bytes::<16>()?.iter().map(|b| format!("{b:02x}")).collect())
}

/// A path segment the Worker accepts (`[A-Za-z0-9._-]{1,128}`), excluding dot-segments.
pub fn valid_segment(segment: &str) -> bool {
    (1..=128).contains(&segment.len())
        && segment != "."
        && segment != ".."
        && !segment.contains("..")
        && segment.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'-'))
}

/// Each note's images after a cloud listing: what the server lists (less what this device removed and
/// has not finished deleting), plus every pending image, plus the uploaded images of notes whose
/// commit has not been confirmed yet (their uploads are still provisional, so still unlisted).
/// The web's `mergeAttachmentsIntoNotes`.
pub fn merge_listing(
    local: &BTreeMap<String, Vec<Attachment>>,
    remote: &[RemoteAttachment],
    removed: &BTreeMap<String, Vec<String>>,
    awaiting_commit: &BTreeSet<String>,
    live_notes: &BTreeSet<String>,
) -> BTreeMap<String, Vec<Attachment>> {
    let mut merged: BTreeMap<String, Vec<Attachment>> = BTreeMap::new();
    for row in remote {
        if !live_notes.contains(&row.note_id) {
            continue;
        }
        if removed.get(&row.note_id).is_some_and(|ids| ids.contains(&row.attachment_id)) {
            continue;
        }
        let placement = if awaiting_commit.contains(&row.note_id)
            && let Some(local_image) =
                local.get(&row.note_id).and_then(|images| images.iter().find(|image| image.id == row.attachment_id))
        {
            local_image.placement.clone()
        } else {
            row.placement.clone()
        };
        merged.entry(row.note_id.clone()).or_default().push(Attachment {
            id: row.attachment_id.clone(),
            mime_type: row.mime_type.clone().unwrap_or_else(|| "application/octet-stream".into()),
            size_bytes: row.size_bytes.unwrap_or(0.0).max(0.0) as u64,
            location: Location::Uploaded { object_key: row.object_key.clone() },
            placement,
        });
    }
    for (note_id, attachments) in local {
        if !live_notes.contains(note_id) {
            continue;
        }
        let keep: Vec<Attachment> = attachments
            .iter()
            .filter(|a| a.is_pending() || awaiting_commit.contains(note_id))
            .filter(|a| !merged.get(note_id).is_some_and(|listed| listed.iter().any(|l| l.id == a.id)))
            .cloned()
            .collect();
        if !keep.is_empty() {
            merged.entry(note_id.clone()).or_default().extend(keep);
        }
    }
    merged
}

/// The account's sealed image files: pending uploads in `attachments/`, downloaded copies in
/// `attachments/cache/`. Each file is bound to its account and id, so a swapped file fails to open.
#[derive(Clone)]
pub struct Blobs {
    dir: PathBuf,
    owner_id: String,
    key: [u8; KEY_LEN],
}

impl Drop for Blobs {
    fn drop(&mut self) {
        crypto::wipe(&mut self.key);
    }
}

impl std::fmt::Debug for Blobs {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Blobs").field("dir", &self.dir).finish_non_exhaustive()
    }
}

static BLOB_TEMP_SEQ: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

impl Blobs {
    pub(crate) fn new(dir: PathBuf, owner_id: &str, key: [u8; KEY_LEN]) -> Blobs {
        Blobs { dir, owner_id: owner_id.to_string(), key }
    }

    fn aad(&self, id: &str) -> Vec<u8> {
        format!("NLA1|{}/{id}", self.owner_id).into_bytes()
    }

    fn path(&self, id: &str, cached: bool) -> Result<PathBuf, Error> {
        let valid =
            (1..=128).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-'));
        if !valid {
            return Err(Error::InvalidInput("attachment id".into()));
        }
        let dir = if cached { self.dir.join("cache") } else { self.dir.clone() };
        Ok(dir.join(format!("{id}.nla")))
    }

    fn write(&self, id: &str, bytes: &[u8], cached: bool) -> Result<(), Error> {
        use std::io::Write;
        use std::sync::atomic::Ordering;

        if cached
            && let Some(account_dir) = self.dir.parent()
            && !account_dir.exists()
        {
            return Err(Error::InvalidInput("account directory no longer exists".into()));
        }
        let path = self.path(id, cached)?;
        let parent = path.parent().unwrap_or(&self.dir);
        fs::create_dir_all(parent)?;
        let sealed = crypto::seal(&self.key, &self.aad(id), bytes)?;
        let seq = BLOB_TEMP_SEQ.fetch_add(1, Ordering::Relaxed);
        let temp = parent.join(format!("{id}.{}.{seq}.tmp", std::process::id()));
        if let Err(err) = fs::File::create(&temp).and_then(|mut f| f.write_all(&sealed)) {
            let _ = fs::remove_file(&temp);
            return Err(err.into());
        }
        if let Err(err) = fs::rename(&temp, &path) {
            let _ = fs::remove_file(&temp);
            return Err(err.into());
        }
        Ok(())
    }

    fn read(&self, id: &str, cached: bool) -> Result<Option<Vec<u8>>, Error> {
        let path = self.path(id, cached)?;
        if !path.exists() {
            return Ok(None);
        }
        crypto::open(&self.key, &self.aad(id), &fs::read(path)?).map(Some)
    }

    /// Keeps a pending image's bytes.
    pub fn put_pending(&self, id: &str, bytes: &[u8]) -> Result<(), Error> {
        self.write(id, bytes, false)
    }

    /// A downloaded image, kept so it shows offline.
    pub fn put_cached(&self, id: &str, bytes: &[u8]) -> Result<(), Error> {
        self.write(id, bytes, true)
    }

    /// The image's bytes, pending or cached, if this device has them.
    pub fn get(&self, id: &str) -> Result<Option<Vec<u8>>, Error> {
        match self.read(id, false)? {
            Some(bytes) => Ok(Some(bytes)),
            None => self.read(id, true),
        }
    }

    /// The pending copy only: the bytes to upload.
    pub fn get_pending(&self, id: &str) -> Result<Option<Vec<u8>>, Error> {
        self.read(id, false)
    }

    /// Moves an uploaded image's pending copy into the cache: it is no longer the only copy.
    pub fn release_pending(&self, id: &str) -> Result<(), Error> {
        if let Some(bytes) = self.read(id, false)? {
            self.write(id, &bytes, true)?;
            match fs::remove_file(self.path(id, false)?) {
                Ok(()) => {}
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                Err(e) => return Err(e.into()),
            }
        }
        Ok(())
    }

    /// Forgets the image on this device: pending copy and cache.
    pub fn remove(&self, id: &str) -> Result<(), Error> {
        for cached in [false, true] {
            let path = self.path(id, cached)?;
            match fs::remove_file(path) {
                Ok(()) => {}
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                Err(e) => return Err(e.into()),
            }
        }
        Ok(())
    }

    /// Removes cached `.nla` files whose attachment ids are no longer referenced by any live note.
    pub fn prune_orphans(&self, keep_ids: &BTreeSet<String>) -> Result<usize, Error> {
        let cache = self.dir.join("cache");
        if !cache.exists() {
            return Ok(0);
        }
        let mut removed = 0usize;
        for entry in fs::read_dir(&cache)? {
            let path = entry?.path();
            if path.extension().and_then(|e| e.to_str()) != Some("nla") {
                continue;
            }
            let Some(stem) = path.file_stem().and_then(|s| s.to_str()) else { continue };
            if !keep_ids.contains(stem) && fs::remove_file(&path).is_ok() {
                removed += 1;
            }
        }
        Ok(removed)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn att(id: &str, location: Location) -> Attachment {
        Attachment { id: id.into(), mime_type: "image/png".into(), size_bytes: 3, location, placement: None }
    }

    #[test]
    fn a_removed_boundary_falls_back_and_undo_recovers_it() {
        let original = "First 🧭\nSecond";
        let merged = "First 🧭Second";
        let mut images = [att("image", Location::Pending)];
        images[0].placement = PlacementBody::new(original).at(8, 0);
        rebase_placements(&mut images, original, merged);
        let anchor = images[0].placement.as_ref().unwrap();
        assert!(!PlacementBody::new(merged).matches(anchor));
        rebase_placements(&mut images, merged, original);
        assert!(PlacementBody::new(original).matches(images[0].placement.as_ref().unwrap()));
    }

    fn row(note: &str, id: &str) -> RemoteAttachment {
        RemoteAttachment {
            attachment_id: id.into(),
            note_id: note.into(),
            object_key: format!("owners/u/notes/{note}/{id}"),
            mime_type: Some("image/jpeg".into()),
            size_bytes: Some(1234.0),
            placement: None,
        }
    }

    #[test]
    fn image_types_come_from_the_bytes() {
        assert_eq!(sniff_mime(b"\x89PNG\r\n\x1a\n...."), Some("image/png"));
        assert_eq!(sniff_mime(&[0xFF, 0xD8, 0xFF, 0xE0]), Some("image/jpeg"));
        assert_eq!(sniff_mime(b"GIF89a.."), Some("image/gif"));
        assert_eq!(sniff_mime(b"RIFF\0\0\0\0WEBPVP8 "), Some("image/webp"));
        assert_eq!(sniff_mime(b"\0\0\0\x18ftypheic"), Some("image/heic"));
        assert_eq!(sniff_mime(b"\0\0\0\x18ftypavif"), Some("image/avif"));
        assert_eq!(sniff_mime(b"%PDF-1.7"), None);
        assert!(can_preview("image/webp") && !can_preview("image/heic"));
        let id = new_attachment_id().unwrap();
        assert!(valid_segment(&id) && id.len() == 32);
        assert!(!valid_segment("../x") && !valid_segment(""));
    }

    #[test]
    fn the_listing_replaces_uploaded_images_and_keeps_what_only_this_device_has() {
        let mut local = BTreeMap::new();
        local.insert(
            "1".to_string(),
            vec![att("pending", Location::Pending), att("old", Location::Uploaded { object_key: "k".into() })],
        );
        local.insert("2".to_string(), vec![att("provisional", Location::Uploaded { object_key: "k2".into() })]);
        let remote = [row("1", "listed"), row("1", "removed-here"), row("3", "elsewhere"), row("9", "deleted-note")];
        let removed = BTreeMap::from([("1".to_string(), vec!["removed-here".to_string()])]);
        let awaiting = BTreeSet::from(["2".to_string()]);
        let live = BTreeSet::from(["1".to_string(), "2".to_string(), "3".to_string()]);
        let merged = merge_listing(&local, &remote, &removed, &awaiting, &live);
        let ids = |note: &str| {
            merged.get(note).map(|a| a.iter().map(|x| x.id.as_str()).collect::<Vec<_>>()).unwrap_or_default()
        };
        assert_eq!(
            ids("1"),
            ["listed", "pending"],
            "the server's list, less removals, plus pending; a stale upload goes"
        );
        assert_eq!(ids("2"), ["provisional"], "an upload awaiting its note's commit stays");
        assert_eq!(ids("3"), ["elsewhere"], "images added on another device arrive");
        assert!(!merged.contains_key("9"), "no images for a note this device does not have");
        assert_eq!(merged["1"][0].size_bytes, 1234);
    }

    #[test]
    fn the_listing_hydrates_remote_placements_and_preserves_pending_edits() {
        let remote_pos = PlacementBody::new("Before 🧭\n\nAfter").at(10, 0).unwrap();
        let local_pos = PlacementBody::new("Before 🧭\n\nAfter").at(15, 0).unwrap();
        let mut local = BTreeMap::new();
        local.insert(
            "1".to_string(),
            vec![Attachment {
                placement: Some(local_pos.clone()),
                ..att("a1", Location::Uploaded { object_key: "k1".into() })
            }],
        );
        local.insert(
            "2".to_string(),
            vec![Attachment {
                placement: Some(local_pos.clone()),
                ..att("a2", Location::Uploaded { object_key: "k2".into() })
            }],
        );
        local.insert(
            "3".to_string(),
            vec![Attachment {
                placement: Some(local_pos.clone()),
                ..att("a3", Location::Uploaded { object_key: "k3".into() })
            }],
        );
        let remote = [
            RemoteAttachment { placement: Some(remote_pos.clone()), ..row("1", "a1") },
            RemoteAttachment { placement: None, ..row("2", "a2") },
            RemoteAttachment { placement: Some(remote_pos.clone()), ..row("3", "a3") },
        ];
        let awaiting = BTreeSet::from(["3".to_string()]);
        let live = BTreeSet::from(["1".to_string(), "2".to_string(), "3".to_string()]);
        let merged = merge_listing(&local, &remote, &BTreeMap::new(), &awaiting, &live);
        assert_eq!(merged["1"][0].placement, Some(remote_pos), "synced note adopts remote placement");
        assert_eq!(merged["2"][0].placement, None, "synced note clears placement removed elsewhere");
        assert_eq!(merged["3"][0].placement, Some(local_pos), "unpushed note keeps its local placement");
    }

    #[test]
    fn blobs_are_sealed_per_account_and_id() {
        let dir = std::env::temp_dir().join(format!("nlk-blobs-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let blobs = Blobs::new(dir.clone(), "owner", [7; KEY_LEN]);
        blobs.put_pending("a1", b"image bytes").unwrap();
        assert_eq!(blobs.get("a1").unwrap().as_deref(), Some(&b"image bytes"[..]));
        assert!(!fs::read(dir.join("a1.nla")).unwrap().windows(5).any(|w| w == b"image"), "sealed on disk");
        // A file renamed onto another id does not open.
        fs::copy(dir.join("a1.nla"), dir.join("b2.nla")).unwrap();
        assert!(blobs.get("b2").is_err());
        let other_account = Blobs::new(dir.clone(), "someone-else", [7; KEY_LEN]);
        assert!(other_account.get("a1").is_err());
        // Released to the cache once uploaded; removed for good.
        blobs.release_pending("a1").unwrap();
        assert!(blobs.get_pending("a1").unwrap().is_none());
        assert!(blobs.get("a1").unwrap().is_some());
        blobs.remove("a1").unwrap();
        assert!(blobs.get("a1").unwrap().is_none());
        assert!(blobs.put_pending("../escape", b"x").is_err());
        fs::remove_dir_all(dir).unwrap();
    }
}
