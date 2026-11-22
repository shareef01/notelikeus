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
}

/// The image types the Worker accepts, from the file's first bytes (never the file name). The ones the
/// app can show are PNG, JPEG, GIF and WebP; HEIC/HEIF/AVIF come from phones and are kept and synced.
pub fn sniff_mime(bytes: &[u8]) -> Option<&'static str> {
    if bytes.starts_with(&[0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A]) {
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

/// A path segment the Worker accepts (`[A-Za-z0-9._-]{1,128}`).
pub fn valid_segment(segment: &str) -> bool {
    (1..=128).contains(&segment.len())
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
        merged.entry(row.note_id.clone()).or_default().push(Attachment {
            id: row.attachment_id.clone(),
            mime_type: row.mime_type.clone().unwrap_or_else(|| "application/octet-stream".into()),
            size_bytes: row.size_bytes.unwrap_or(0.0).max(0.0) as u64,
            location: Location::Uploaded { object_key: row.object_key.clone() },
        });
    }
    for (note_id, attachments) in local {
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

impl std::fmt::Debug for Blobs {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Blobs").field("dir", &self.dir).finish_non_exhaustive()
    }
}

impl Blobs {
    pub(crate) fn new(dir: PathBuf, owner_id: &str, key: [u8; KEY_LEN]) -> Blobs {
        Blobs { dir, owner_id: owner_id.to_string(), key }
    }

    fn aad(&self, id: &str) -> Vec<u8> {
        format!("NLA1|{}/{id}", self.owner_id).into_bytes()
    }

    fn path(&self, id: &str, cached: bool) -> Result<PathBuf, Error> {
        if !valid_segment(id) {
            return Err(Error::InvalidInput("attachment id".into()));
        }
        let dir = if cached { self.dir.join("cache") } else { self.dir.clone() };
        Ok(dir.join(format!("{id}.nla")))
    }

    fn write(&self, id: &str, bytes: &[u8], cached: bool) -> Result<(), Error> {
        let path = self.path(id, cached)?;
        fs::create_dir_all(path.parent().unwrap_or(&self.dir))?;
        let sealed = crypto::seal(&self.key, &self.aad(id), bytes)?;
        let temp = path.with_extension("tmp");
        fs::write(&temp, sealed)?;
        fs::rename(temp, path)?;
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
            fs::remove_file(self.path(id, false)?)?;
        }
        Ok(())
    }

    /// Forgets the image on this device: pending copy and cache.
    pub fn remove(&self, id: &str) -> Result<(), Error> {
        for cached in [false, true] {
            let path = self.path(id, cached)?;
            if path.exists() {
                fs::remove_file(path)?;
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn att(id: &str, location: Location) -> Attachment {
        Attachment { id: id.into(), mime_type: "image/png".into(), size_bytes: 3, location }
    }

    fn row(note: &str, id: &str) -> RemoteAttachment {
        RemoteAttachment {
            attachment_id: id.into(),
            note_id: note.into(),
            object_key: format!("owners/u/notes/{note}/{id}"),
            mime_type: Some("image/jpeg".into()),
            size_bytes: Some(1234.0),
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
