//! What the window's commands do, without Tauri: open the right account's store, list, create, update
//! and delete notes (saving after every change), sign in with an emailed code, sync, and sign out.
//! Kept apart from `main.rs` so it is unit-tested directly.
//!
//! Each account has its own sealed store (`accounts/<owner>`); guest notes live under `__guest__`.
//! Signing in switches to the account's store and signing out switches back: notes written as a guest
//! are not merged into an account, and an account's notes stay sealed on disk after sign-out.

mod backup;
mod diagnostics;
mod settings;

pub use backup::{BackupAttachmentSource, BackupImportReport};
pub use settings::DeleteAccountReport;

use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

use notelikeus_core::Error as CoreError;
use notelikeus_core::attachments::{self, Attachment, Location, MAX_ATTACHMENT_BYTES};
use notelikeus_core::auth::{AuthClient, AuthError};
use notelikeus_core::captcha::checked_page_url;
use notelikeus_core::conflict;
use notelikeus_core::crypto::KeyProtector;
use notelikeus_core::http::{
    AttachmentError, AttachmentsClient, SupabaseTransport, checked_base_url, checked_publishable_key,
};
use notelikeus_core::model::{ChecklistItem, Note};
use notelikeus_core::query::{self, Filters, NoteScope, SavedFilter};

use crate::clock::LocalDays;
use notelikeus_core::store::{LocalState, Session, SessionVault, Store, TombstoneOrigin};
use notelikeus_core::sync::{self, IdSource, SyncError, TransportError};
use serde::Serialize;

pub const GUEST: &str = "__guest__";
/// `validate_note_payload` limits (Postgres `char_length`, so Unicode scalar values).
pub const MAX_TITLE_CHARS: usize = 2_000;
pub const MAX_CONTENT_CHARS: usize = 100_000;

/// The Supabase project this build syncs with. None: the build has no sync at all.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SyncConfig {
    pub url: String,
    pub publishable_key: String,
    /// The web app's Turnstile challenge page (`NLK_CAPTCHA_PAGE_URL`), which the hosted project
    /// requires before it emails a code. None for a local stack, which has no captcha.
    pub captcha_page_url: Option<String>,
    /// The attachments Worker (`NLK_ATTACHMENTS_URL`). None: images stay on this device.
    pub attachments_url: Option<String>,
}

impl SyncConfig {
    /// `NLK_SUPABASE_URL` and `NLK_SUPABASE_KEY`, read at run time, falling back to the values a
    /// build was compiled with, or a `local.properties` file when running locally during development.
    /// Unset in all places, or invalid URLs/secret keys, mean no sync.
    pub fn from_env() -> Option<SyncConfig> {
        let local_props = read_local_properties();
        let get_cfg = |env_name: &str, compiled: Option<&str>, prop_key: &str| -> Option<String> {
            std::env::var(env_name)
                .ok()
                .filter(|s| !s.trim().is_empty())
                .or_else(|| compiled.map(str::to_string).filter(|s| !s.trim().is_empty()))
                .or_else(|| local_props.get(prop_key).cloned().filter(|s| !s.trim().is_empty()))
        };
        let raw_url = get_cfg("NLK_SUPABASE_URL", option_env!("NLK_SUPABASE_URL"), "notelikeus.supabaseUrl")?;
        let raw_key = get_cfg("NLK_SUPABASE_KEY", option_env!("NLK_SUPABASE_KEY"), "notelikeus.supabaseAnonKey")?;
        let url = checked_base_url(&raw_url).ok()?;
        let publishable_key = checked_publishable_key(&raw_key).ok()?;
        let captcha_page_url =
            get_cfg("NLK_CAPTCHA_PAGE_URL", option_env!("NLK_CAPTCHA_PAGE_URL"), "notelikeus.captchaPageUrl")
                .and_then(|page| checked_page_url(page.trim()).ok());
        let attachments_url =
            get_cfg("NLK_ATTACHMENTS_URL", option_env!("NLK_ATTACHMENTS_URL"), "notelikeus.attachmentsWorkerUrl")
                .and_then(|u| checked_base_url(u.trim()).ok());
        Some(SyncConfig { url, publishable_key, captcha_page_url, attachments_url })
    }
}

fn read_local_properties() -> std::collections::HashMap<String, String> {
    let mut map = std::collections::HashMap::new();
    let Ok(mut dir) = std::env::current_dir() else { return map };
    for _ in 0..6 {
        let candidate = dir.join("local.properties");
        if candidate.is_file() {
            if let Ok(content) = std::fs::read_to_string(&candidate) {
                for raw_line in content.lines() {
                    let line = raw_line.trim();
                    if line.is_empty() || line.starts_with('#') || line.starts_with('!') {
                        continue;
                    }
                    if let Some((k, v)) = line.split_once('=') {
                        let unescaped = v.trim().replace("\\:", ":").replace("\\=", "=").replace("\\\\", "\\");
                        if !unescaped.is_empty() {
                            map.insert(k.trim().to_string(), unescaped);
                        }
                    }
                }
            }
            break;
        }
        if !dir.pop() {
            break;
        }
    }
    map
}

/// What the UI is shown of a note. Ids are strings: they exceed JavaScript's safe-integer range
/// only in theory, but a string never rounds.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NoteView {
    pub id: String,
    pub title: String,
    pub content: String,
    pub timestamp: i64,
    /// When this note should remind the user, in epoch milliseconds.
    pub reminder_timestamp: Option<i64>,
    pub is_pinned: bool,
    /// Saved here but not confirmed by the cloud. Always true in guest mode, which never syncs.
    pub unsynced: bool,
    /// The stored ARGB (see `notelikeus_core::palette`); 0 for no colour.
    pub color: i32,
    pub is_archived: bool,
    pub is_trashed: bool,
    pub labels: Vec<String>,
    /// A note is text or a checklist: when this is non-empty, `content` is empty.
    pub checklist: Vec<ChecklistItem>,
    /// The note's images, in the order they were added.
    pub images: Vec<ImageView>,
}

/// One image on a note.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImageView {
    pub id: String,
    pub mime_type: String,
    pub size_bytes: u64,
    /// Only on this device so far (a guest's images always are).
    pub pending: bool,
}

/// What a background thread needs to show an image without holding the notes: this account's sealed
/// files, and the Worker to fetch an uploaded image this device has not got yet.
#[derive(Clone)]
pub struct ImageSource {
    blobs: notelikeus_core::attachments::Blobs,
    worker: Option<(String, String)>,
}

impl std::fmt::Debug for ImageSource {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ImageSource")
            .field("blobs", &self.blobs)
            .field("worker_configured", &self.worker.is_some())
            .finish()
    }
}

impl ImageSource {
    /// The image's bytes: from this device, or fetched from the Worker and kept (sealed) for next time.
    pub fn bytes(&self, note_id: &str, image_id: &str) -> Result<Vec<u8>, String> {
        if let Some(bytes) = self.blobs.get(image_id).map_err(|e| e.to_string())? {
            return Ok(bytes);
        }
        let (url, token) = self.worker.as_ref().ok_or("This image is on another device and sync isn't on.")?;
        let client = AttachmentsClient::new(url, token).map_err(|e| e.to_string())?;
        let bytes = client.download(note_id, image_id).map_err(|e| match e {
            AttachmentError::Network(_) => "Offline: this image will show once you're back online.".to_string(),
            other => other.to_string(),
        })?;
        // A copy that cannot be kept still shows.
        let _ = self.blobs.put_cached(image_id, &bytes);
        Ok(bytes)
    }
}

/// What a search shows: the notes, whether they are near misses, and what the box could not use.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SearchView {
    pub notes: Vec<NoteView>,
    pub is_fuzzy: bool,
    pub search_text: String,
    pub unknown: Vec<String>,
    /// The scope searched, which `in:all` or `is:archived` typed in the box may have changed.
    pub scope: NoteScope,
}

/// A conflicted copy beside the note it was split from: each version's lines, marked `true` where the
/// other version has no such line. `original` is None when no single live note matches.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ConflictReview {
    pub copy: NoteView,
    pub original: Option<NoteView>,
    pub original_lines: Vec<(String, bool)>,
    pub copy_lines: Vec<(String, bool)>,
}

/// Which notes a view shows, as the web app's sidebar: Notes, Archive and Trash.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize)]
pub enum Scope {
    #[default]
    Notes,
    Archive,
    Trash,
}

/// How many notes each view holds, for the sidebar.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize)]
pub struct ScopeCounts {
    pub notes: usize,
    pub archive: usize,
    pub trash: usize,
}

/// `validate_note_payload`'s limits on labels, checklists, and attachments.
pub const MAX_LABELS_PER_NOTE: usize = 100;
pub const MAX_CHECKLIST_ITEMS: usize = 500;
pub const MAX_CHECKLIST_ITEM_CHARS: usize = 2_000;
pub const MAX_CHECKLIST_POSITION: i64 = 1_000_000_000;
pub const MAX_LABEL_CHARS: usize = 2_000;
pub const MAX_ATTACHMENTS_PER_NOTE: usize = 20;
pub const MAX_TIMESTAMP_MS: i64 = 9_999_999_999_999;

/// How notes are ordered inside a scope when not searching by typed relevance.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize)]
pub enum SortOrder {
    #[default]
    Manual,
    Newest,
    Oldest,
}

/// The form two label names are compared in, as the Kotlin client compares them: trimmed, inner
/// whitespace collapsed, lower case. "Work", " work " and "WORK" are one label.
pub fn label_key(name: &str) -> String {
    name.split_whitespace().collect::<Vec<_>>().join(" ").to_lowercase()
}

/// A label name as stored: trimmed, inner whitespace collapsed, case kept.
fn tidy_label(name: &str) -> String {
    name.split_whitespace().collect::<Vec<_>>().join(" ")
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppInfo {
    pub data_file: String,
    pub note_count: usize,
    pub mode: &'static str,
}

/// The account part of the window: whether sync exists in this build, who is signed in, how it went.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountView {
    pub sync_available: bool,
    pub signed_in: bool,
    pub email: Option<String>,
    /// Edits and deletes saved here that the cloud has not confirmed yet.
    pub waiting: usize,
    /// Epoch millis of the last sync that finished.
    pub last_synced_at: Option<i64>,
    pub last_error: Option<String>,
    /// Set when another device wiped the cloud account and uploads are paused until the user acts.
    pub uploads_blocked_after_wipe: bool,
}

/// What one sync did, in the words the UI shows.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncSummary {
    pub uploaded: usize,
    pub downloaded: usize,
    pub conflicted_copies: usize,
    pub deleted_elsewhere: usize,
    pub restored_by_cloud: usize,
    /// Images still only on this device: not uploaded yet, or no image service in this build.
    pub images_waiting: usize,
}

pub struct SystemIds;

impl IdSource for SystemIds {
    fn now_millis(&mut self) -> i64 {
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
    }
    fn random_below_1000(&mut self) -> u32 {
        notelikeus_core::crypto::random_bytes::<4>().map(u32::from_le_bytes).unwrap_or(0) % 1000
    }
}

pub struct NotesService {
    root: PathBuf,
    protector: Box<dyn KeyProtector + Send + Sync>,
    vault: SessionVault,
    config: Option<SyncConfig>,
    session: Option<Session>,
    store: Store,
    state: LocalState,
    ids: Box<dyn IdSource + Send>,
    last_synced_at: Option<i64>,
    last_error: Option<String>,
    account_generation: u64,
}

/// `%APPDATA%\<folder>`, or `NLK_RUST_DATA_DIR` when set (tests, side-by-side runs). Each front end has
/// its own folder, so two of them never write the same files.
pub fn data_dir_named(folder: &str) -> PathBuf {
    if let Some(dir) = std::env::var_os("NLK_RUST_DATA_DIR") {
        return PathBuf::from(dir);
    }
    std::env::var_os("APPDATA").map(PathBuf::from).unwrap_or_else(|| PathBuf::from(".")).join(folder)
}

/// The Tauri experiment's folder.
pub fn default_data_dir() -> PathBuf {
    data_dir_named("NotelikeusRustExperimental")
}

fn check_lengths(title: &str, content: &str) -> Result<(), String> {
    if title.chars().count() > MAX_TITLE_CHARS {
        return Err(format!("The title is longer than {MAX_TITLE_CHARS} characters."));
    }
    if content.chars().count() > MAX_CONTENT_CHARS {
        return Err(format!("The note is longer than {MAX_CONTENT_CHARS} characters."));
    }
    Ok(())
}

fn auth_message(error: AuthError) -> String {
    error.to_string()
}

impl NotesService {
    /// Opens the signed-in account's notes, or the guest notes when nobody is signed in. Only a
    /// permanently unreadable saved session is cleared; transient I/O errors fail loudly without
    /// forgetting the sign-in.
    pub fn open(
        root: &Path,
        protector: Box<dyn KeyProtector + Send + Sync>,
        ids: Box<dyn IdSource + Send>,
        config: Option<SyncConfig>,
    ) -> Result<Self, String> {
        let vault = SessionVault::open(root, protector.as_ref()).map_err(|e| e.to_string())?;
        let mut last_error = None;
        let session = match vault.load() {
            Ok(session) => session,
            Err(error @ (CoreError::Crypto(_) | CoreError::Json(_) | CoreError::Corrupt(_))) => {
                let _ = vault.clear();
                last_error = Some(format!("The saved sign-in could not be read ({error}); sign in again."));
                None
            }
            Err(error) => return Err(format!("Could not read the saved sign-in: {error}")),
        };
        let owner = session.as_ref().map_or(GUEST.to_string(), |s| s.user_id.clone());
        let store = Store::open(root, &owner, protector.as_ref()).map_err(|e| e.to_string())?;
        let state = store.load().map_err(|e| e.to_string())?;
        Ok(NotesService {
            root: root.to_path_buf(),
            protector,
            vault,
            config,
            session,
            store,
            state,
            ids,
            last_synced_at: None,
            last_error,
            account_generation: 0,
        })
    }

    pub fn root_dir(&self) -> &Path {
        &self.root
    }

    pub fn account_generation(&self) -> u64 {
        self.account_generation
    }

    pub fn uploads_blocked_after_wipe(&self) -> bool {
        self.state.uploads_blocked_after_wipe
    }

    pub fn has_pending_reminders(&self) -> bool {
        self.state.notes.values().any(|n| !n.is_trashed && !n.is_archived && n.reminder_timestamp.is_some())
    }

    fn switch_to(&mut self, owner: &str) -> Result<(), String> {
        let store = Store::open(&self.root, owner, self.protector.as_ref()).map_err(|e| e.to_string())?;
        self.state = store.load().map_err(|e| e.to_string())?;
        self.store = store;
        self.last_synced_at = None;
        self.account_generation = self.account_generation.saturating_add(1);
        Ok(())
    }

    fn view(&self, note: &Note) -> NoteView {
        NoteView {
            id: note.id.clone(),
            title: note.title.clone(),
            content: note.content.clone(),
            timestamp: note.timestamp,
            reminder_timestamp: note.reminder_timestamp,
            is_pinned: note.is_pinned,
            unsynced: self.state.pending.contains_key(&note.id),
            color: note.color,
            is_archived: note.is_archived,
            is_trashed: note.is_trashed,
            labels: note.labels.clone(),
            checklist: note.checklist.clone(),
            images: self
                .state
                .attachments
                .get(&note.id)
                .map(|images| {
                    images
                        .iter()
                        .map(|a| ImageView {
                            id: a.id.clone(),
                            mime_type: a.mime_type.clone(),
                            size_bytes: a.size_bytes,
                            pending: a.is_pending(),
                        })
                        .collect()
                })
                .unwrap_or_default(),
        }
    }

    // --- Images -------------------------------------------------------------------------------------------

    /// Adds an image to a note: its type is read from the bytes, at most 10 MB and 20 per note,
    /// sealed on this device until a sync uploads it. Adding an image is an edit, so the note syncs too.
    pub fn add_image(&mut self, id: &str, bytes: &[u8]) -> Result<NoteView, String> {
        if !self.state.notes.contains_key(id) {
            return Err("That note no longer exists.".into());
        }
        if self.state.attachments.get(id).map_or(0, Vec::len) >= MAX_ATTACHMENTS_PER_NOTE {
            return Err(format!("A note can have at most {MAX_ATTACHMENTS_PER_NOTE} images."));
        }
        if bytes.len() > MAX_ATTACHMENT_BYTES {
            return Err("That image is larger than 10 MB.".into());
        }
        let mime_type = attachments::sniff_mime(bytes)
            .filter(|m| attachments::can_preview(m))
            .ok_or("That file isn't a picture Notelikeus can attach. Use PNG, JPEG, GIF or WebP.")?;
        let image_id = attachments::new_attachment_id().map_err(|e| e.to_string())?;
        let blobs = self.store.blobs();
        blobs.put_pending(&image_id, bytes).map_err(|e| format!("Could not keep the image: {e}"))?;
        let before = self.state.clone();
        self.state.attachments.entry(id.to_string()).or_default().push(Attachment {
            id: image_id.clone(),
            mime_type: mime_type.to_string(),
            size_bytes: bytes.len() as u64,
            location: Location::Pending,
        });
        let now = self.ids.now_millis();
        if let Err(error) = sync::edit_note(&mut self.state, id, now, |_| {}) {
            self.state = before;
            let _ = blobs.remove(&image_id);
            return Err(error.to_string());
        }
        if let Err(message) = self.commit(before) {
            let _ = blobs.remove(&image_id);
            return Err(message);
        }
        Ok(self.view(&self.state.notes[id]))
    }

    /// Takes an image off a note. An uploaded one is deleted from the cloud at the next sync, and kept
    /// out of the note until then.
    pub fn remove_image(&mut self, id: &str, image_id: &str) -> Result<NoteView, String> {
        let position = self
            .state
            .attachments
            .get(id)
            .and_then(|images| images.iter().position(|a| a.id == image_id))
            .ok_or("That image is no longer on the note.")?;
        let before = self.state.clone();
        let removed = self.state.attachments.get_mut(id).map(|images| images.remove(position));
        if self.state.attachments.get(id).is_some_and(Vec::is_empty) {
            self.state.attachments.remove(id);
        }
        if let Some(removed) = &removed
            && !removed.is_pending()
        {
            self.state.attachment_removals.entry(id.to_string()).or_default().push(removed.id.clone());
        }
        let now = self.ids.now_millis();
        if let Err(error) = sync::edit_note(&mut self.state, id, now, |_| {}) {
            self.state = before;
            return Err(error.to_string());
        }
        self.commit(before)?;
        let _ = self.store.blobs().remove(image_id);
        Ok(self.view(&self.state.notes[id]))
    }

    /// For showing images off the window's thread.
    pub fn image_source(&self) -> ImageSource {
        let worker = match (&self.config, &self.session) {
            (Some(config), Some(session)) => {
                config.attachments_url.clone().map(|url| (url, session.access_token.clone()))
            }
            _ => None,
        };
        ImageSource { blobs: self.store.blobs(), worker }
    }

    /// A note's images go when the note is deleted for good: this device's copies now, the cloud's at
    /// the next sync.
    fn forget_images_of(&mut self, id: &str) -> Vec<String> {
        let images = self.state.attachments.remove(id).unwrap_or_default();
        let uploaded: Vec<String> = images.iter().filter(|a| !a.is_pending()).map(|a| a.id.clone()).collect();
        if !uploaded.is_empty() {
            self.state.attachment_removals.entry(id.to_string()).or_default().extend(uploaded);
        }
        images.into_iter().map(|a| a.id).collect()
    }

    /// Uploads pending images for either already-known notes (before note push) or newly created/forked
    /// notes (after note push). Never uploads while `uploads_blocked_after_wipe` is set.
    fn upload_pending_images(
        &mut self,
        client: &AttachmentsClient,
        only_existing_notes: bool,
    ) -> Result<usize, AttachmentError> {
        if self.state.uploads_blocked_after_wipe {
            return Ok(self.state.attachments.values().flatten().filter(|a| a.is_pending()).count());
        }
        let blobs = self.store.blobs();
        let mut uploaded_ids = Vec::new();
        let mut failed = 0;
        let pending: Vec<(String, Attachment)> = self
            .state
            .attachments
            .iter()
            .filter(|(note_id, _)| {
                !only_existing_notes
                    || self.state.revisions.contains_key(*note_id)
                    || self.state.known_cloud_ids.contains(*note_id)
            })
            .flat_map(|(note, images)| images.iter().filter(|a| a.is_pending()).map(move |a| (note.clone(), a.clone())))
            .collect();
        for (note_id, image) in pending {
            let Ok(Some(bytes)) = blobs.get_pending(&image.id) else {
                failed += 1;
                continue;
            };
            match client.upload(&note_id, &image.id, &image.mime_type, &bytes) {
                Ok(reply) => {
                    let object_key = reply
                        .object_key
                        .unwrap_or_else(|| format!("owners/{}/notes/{note_id}/{}", self.state.owner_id, image.id));
                    if let Some(entry) = self
                        .state
                        .attachments
                        .get_mut(&note_id)
                        .and_then(|images| images.iter_mut().find(|a| a.id == image.id))
                    {
                        entry.location = Location::Uploaded { object_key };
                        entry.size_bytes = reply.size_bytes.unwrap_or(entry.size_bytes);
                    }
                    // The note's next push names the upload, which is what makes it permanent.
                    if self.state.notes.contains_key(&note_id) && !self.state.pending.contains_key(&note_id) {
                        let now = self.ids.now_millis();
                        let _ = sync::edit_note(&mut self.state, &note_id, now, |_| {});
                    }
                    uploaded_ids.push(image.id.clone());
                }
                Err(AttachmentError::Unauthorized) => {
                    if !uploaded_ids.is_empty() && self.store.save(&self.state).is_ok() {
                        for id in &uploaded_ids {
                            let _ = blobs.release_pending(id);
                        }
                    }
                    return Err(AttachmentError::Unauthorized);
                }
                Err(_) => failed += 1,
            }
        }
        // The pending copies go only once the state that points at the uploads is on disk (AUD-011).
        if !uploaded_ids.is_empty() && self.store.save(&self.state).is_ok() {
            for id in &uploaded_ids {
                let _ = blobs.release_pending(id);
            }
        }
        Ok(failed)
    }

    /// Runs AFTER `sync::sync` (`push_deletes`) so delete conflicts that resurrect a note preserve its
    /// R2 objects. Also purges stale soft-deleted rows once confirmed absent.
    fn flush_attachment_removals(
        &mut self,
        client: &AttachmentsClient,
        transport: &SupabaseTransport,
    ) -> Result<(), AttachmentError> {
        let mut remaining: std::collections::BTreeMap<String, Vec<String>> = std::collections::BTreeMap::new();
        let removals = std::mem::take(&mut self.state.attachment_removals);
        let mut unauthorized = false;

        for (note_id, ids) in removals {
            for id in ids {
                if unauthorized {
                    remaining.entry(note_id.clone()).or_default().push(id);
                    continue;
                }
                let resurrected = self.state.notes.contains_key(&note_id)
                    && self.state.attachments.get(&note_id).is_some_and(|list| list.iter().any(|a| a.id == id));
                if resurrected {
                    continue;
                }
                match client.delete(&note_id, &id) {
                    Ok(()) | Err(AttachmentError::NotFound) => {}
                    Err(AttachmentError::Unauthorized) => {
                        unauthorized = true;
                        remaining.entry(note_id.clone()).or_default().push(id);
                    }
                    Err(_) => {
                        remaining.entry(note_id.clone()).or_default().push(id);
                    }
                }
            }
        }
        self.state.attachment_removals = remaining;
        if unauthorized {
            return Err(AttachmentError::Unauthorized);
        }

        if let Ok(pending_deleted) = transport.list_pending_deleted_attachments() {
            for row in pending_deleted {
                if !self.state.notes.contains_key(&row.note_id)
                    && matches!(
                        client.delete(&row.note_id, &row.attachment_id),
                        Ok(()) | Err(AttachmentError::NotFound)
                    )
                {
                    let _ = transport.purge_deleted_note_attachment(&row.note_id, &row.attachment_id);
                }
            }
        }
        Ok(())
    }

    /// Copies locally available attachment bytes onto conflicted copies created during sync.
    fn clone_forked_conflict_images(&mut self, forked: &[(String, String)]) {
        if forked.is_empty() {
            return;
        }
        let blobs = self.store.blobs();
        let now = self.ids.now_millis();
        for (original_id, copy_id) in forked {
            if !self.state.notes.contains_key(copy_id) {
                continue;
            }
            let Some(orig_images) = self.state.attachments.get(original_id).cloned() else {
                continue;
            };
            let existing = self.state.attachments.get(copy_id).map_or(0, Vec::len);
            if existing >= MAX_ATTACHMENTS_PER_NOTE {
                continue;
            }
            let mut added_any = false;
            for image in orig_images.into_iter().take(MAX_ATTACHMENTS_PER_NOTE - existing) {
                let Ok(Some(bytes)) = blobs.get(&image.id) else {
                    continue;
                };
                let Ok(new_id) = attachments::new_attachment_id() else {
                    continue;
                };
                if blobs.put_pending(&new_id, &bytes).is_err() {
                    continue;
                }
                self.state.attachments.entry(copy_id.clone()).or_default().push(Attachment {
                    id: new_id,
                    mime_type: image.mime_type,
                    size_bytes: bytes.len() as u64,
                    location: Location::Pending,
                });
                added_any = true;
            }
            if added_any {
                let _ = sync::edit_note(&mut self.state, copy_id, now, |_| {});
            }
        }
    }

    /// Rebuilds each note's images from the account's committed uploads, keeping what only this device has.
    fn hydrate_images(&mut self, transport: &SupabaseTransport) -> Result<(), String> {
        let remote = transport.list_user_attachments().map_err(|e| e.to_string())?;
        let awaiting: std::collections::BTreeSet<String> = self.state.pending.keys().cloned().collect();
        let live: std::collections::BTreeSet<String> = self.state.notes.keys().cloned().collect();
        self.state.attachments = attachments::merge_listing(
            &self.state.attachments,
            &remote,
            &self.state.attachment_removals,
            &awaiting,
            &live,
        );
        Ok(())
    }

    fn in_scope(note: &Note, scope: Scope) -> bool {
        match scope {
            Scope::Notes => !note.is_trashed && !note.is_archived,
            Scope::Archive => !note.is_trashed && note.is_archived,
            Scope::Trash => note.is_trashed,
        }
    }

    fn compare_notes(a: &Note, b: &Note, pin_first: bool, sort: SortOrder) -> std::cmp::Ordering {
        let pin = if pin_first { b.is_pinned.cmp(&a.is_pinned) } else { std::cmp::Ordering::Equal };
        pin.then_with(|| match sort {
            SortOrder::Manual => a.position.cmp(&b.position).then(b.timestamp.cmp(&a.timestamp)).then(b.id.cmp(&a.id)),
            SortOrder::Newest => b.timestamp.cmp(&a.timestamp).then(b.id.cmp(&a.id)),
            SortOrder::Oldest => a.timestamp.cmp(&b.timestamp).then(a.id.cmp(&b.id)),
        })
    }

    /// The notes one view shows: in Notes, pinned first; then most recently edited.
    pub fn list_in(&self, scope: Scope) -> Vec<NoteView> {
        self.list_in_sorted(scope, SortOrder::Newest)
    }

    /// The notes one view shows ordered according to `sort` (with pinned first in `Scope::Notes`).
    pub fn list_in_sorted(&self, scope: Scope, sort: SortOrder) -> Vec<NoteView> {
        let mut notes: Vec<&Note> = self.state.notes.values().filter(|n| Self::in_scope(n, scope)).collect();
        notes.sort_by(|a, b| Self::compare_notes(a, b, scope == Scope::Notes, sort));
        notes.into_iter().map(|n| self.view(n)).collect()
    }

    fn in_sorted_order(&self, sort: SortOrder) -> Vec<&Note> {
        let mut notes: Vec<&Note> = self.state.notes.values().collect();
        notes.sort_by(|a, b| {
            let leads = |n: &Note| n.is_pinned && !n.is_archived && !n.is_trashed;
            leads(b).cmp(&leads(a)).then_with(|| match sort {
                SortOrder::Manual => {
                    a.position.cmp(&b.position).then(b.timestamp.cmp(&a.timestamp)).then(b.id.cmp(&a.id))
                }
                SortOrder::Newest => b.timestamp.cmp(&a.timestamp).then(b.id.cmp(&a.id)),
                SortOrder::Oldest => a.timestamp.cmp(&b.timestamp).then(a.id.cmp(&b.id)),
            })
        });
        notes
    }

    /// The notes `filters` picks out, search box operators included, as of `now` (epoch millis).
    pub fn search(&self, filters: &Filters, now: i64) -> SearchView {
        self.search_sorted(filters, SortOrder::Newest, now)
    }

    /// The notes `filters` picks out, ordered by `sort` when the text box is empty and by relevance otherwise.
    pub fn search_sorted(&self, filters: &Filters, sort: SortOrder, now: i64) -> SearchView {
        let labels = self.label_names();
        let outcome = query::search(self.in_sorted_order(sort), filters, &labels, now, &LocalDays::at(now));
        SearchView {
            notes: outcome.notes.iter().map(|n| self.view(n)).collect(),
            is_fuzzy: outcome.is_fuzzy,
            search_text: outcome.search_text,
            unknown: outcome.unknown,
            scope: outcome.scope,
        }
    }

    /// Whether the note is a copy kept from a sync conflict, by its title, as every client marks them.
    pub fn is_conflicted_copy(&self, id: &str) -> bool {
        self.state.notes.get(id).is_some_and(|n| conflict::is_conflicted_copy_title(&n.title))
    }

    /// A conflicted copy beside the note it was most likely split from, line by line.
    pub fn conflict_review(&self, copy_id: &str) -> Option<ConflictReview> {
        let copy = self.state.notes.get(copy_id).filter(|n| conflict::is_conflicted_copy_title(&n.title))?;
        let original = conflict::find_original(copy, self.state.notes.values());
        let (original_lines, copy_lines) = match original {
            Some(original) => conflict::compare_versions(original, copy),
            None => (Vec::new(), conflict::compare_versions(copy, copy).0),
        };
        Some(ConflictReview {
            copy: self.view(copy),
            original: original.map(|n| self.view(n)),
            original_lines: original_lines.into_iter().map(|l| (l.text, l.changed)).collect(),
            copy_lines: copy_lines.into_iter().map(|l| (l.text, l.changed)).collect(),
        })
    }

    /// The copy's title without the suffix: what it becomes when it is kept as an ordinary note.
    fn retitle_copy(&mut self, copy_id: &str, now: i64) -> Result<(), String> {
        let Some(note) = self.state.notes.get(copy_id) else {
            return Err("That note no longer exists.".into());
        };
        let Some(title) = conflict::original_title_of(&note.title).map(str::to_string) else {
            return Err("That note is not a conflicted copy.".into());
        };
        sync::edit_note(&mut self.state, copy_id, now, |n| n.title = title).map_err(|e| e.to_string())
    }

    /// Keeps both versions: the copy loses its suffix and becomes an ordinary note.
    pub fn keep_both(&mut self, copy_id: &str) -> Result<(), String> {
        let before = self.state.clone();
        let now = self.ids.now_millis();
        self.retitle_copy(copy_id, now)?;
        self.commit(before)
    }

    /// Keeps the copy's version: the original goes to the trash and the copy takes its title.
    pub fn keep_copy(&mut self, copy_id: &str, original_id: &str) -> Result<(), String> {
        if copy_id == original_id {
            return Err("Choose two different notes.".into());
        }
        if !self.is_conflicted_copy(copy_id) {
            return Err("That note is not a conflicted copy.".into());
        }
        if !self.state.notes.contains_key(original_id) {
            return Err("The original note no longer exists.".into());
        }
        let before = self.state.clone();
        let now = self.ids.now_millis();
        let result = sync::edit_note(&mut self.state, original_id, now, |n| n.is_trashed = true)
            .map_err(|e| e.to_string())
            .and_then(|()| self.retitle_copy(copy_id, now));
        if let Err(message) = result {
            self.state = before;
            return Err(message);
        }
        self.commit(before)
    }

    /// Keeps the current note: the copy goes to the trash, where it can still be restored.
    pub fn keep_original(&mut self, copy_id: &str) -> Result<(), String> {
        self.move_to_trash(copy_id).map(|_| ())
    }

    /// Active notes in each smart view (Reminders, Unfinished, Unlabelled).
    pub fn smart_view_counts(&self, now: i64) -> [usize; 3] {
        query::smart_view_counts(self.state.notes.values(), now)
    }

    /// Saved filters, newest first.
    pub fn saved_filters(&self) -> &[SavedFilter] {
        &self.state.saved_filters
    }

    /// Saves `filters` under `name`, replacing a filter of the same name (ignoring case).
    pub fn save_filter(&mut self, name: &str, filters: &Filters) -> Result<(), String> {
        if name.trim().is_empty() {
            return Err("A saved filter needs a name.".into());
        }
        let before = self.state.clone();
        self.state.saved_filters = query::with_saved(&self.state.saved_filters, name, filters);
        self.commit(before)
    }

    pub fn delete_saved_filter(&mut self, name: &str) -> Result<(), String> {
        let before = self.state.clone();
        self.state.saved_filters = query::without_saved(&self.state.saved_filters, name);
        self.commit(before)
    }

    pub fn counts(&self) -> ScopeCounts {
        let mut counts = ScopeCounts::default();
        for note in self.state.notes.values() {
            if Self::in_scope(note, Scope::Trash) {
                counts.trash += 1;
            } else if Self::in_scope(note, Scope::Archive) {
                counts.archive += 1;
            } else {
                counts.notes += 1;
            }
        }
        counts
    }

    pub fn note(&self, id: &str) -> Option<NoteView> {
        self.state.notes.get(id).map(|n| self.view(n))
    }

    /// Changes one note through the sync engine (so the change is queued for upload and re-stamped)
    /// and saves, putting everything back if the save fails.
    fn modify(&mut self, id: &str, change: impl FnOnce(&mut Note)) -> Result<NoteView, String> {
        if !self.state.notes.contains_key(id) {
            return Err("That note no longer exists.".into());
        }
        let before = self.state.clone();
        let now = self.ids.now_millis();
        sync::edit_note(&mut self.state, id, now, change).map_err(|e| e.to_string())?;
        self.commit(before)?;
        Ok(self.view(&self.state.notes[id]))
    }

    pub fn set_color(&mut self, id: &str, color: i32) -> Result<NoteView, String> {
        match self.state.notes.get(id) {
            Some(note) if note.color == color => Ok(self.view(note)),
            _ => self.modify(id, |n| n.color = color),
        }
    }

    /// Schedules a reminder for an active note. Delivery is handled by the running desktop app.
    pub fn set_reminder_timestamp(&mut self, id: &str, timestamp: i64) -> Result<NoteView, String> {
        if timestamp <= self.ids.now_millis() || timestamp > MAX_TIMESTAMP_MS {
            return Err("Choose a valid time in the future.".into());
        }
        match self.state.notes.get(id) {
            Some(note) if note.is_archived || note.is_trashed => Err("Only notes in Notes can have reminders.".into()),
            Some(note) if note.reminder_timestamp == Some(timestamp) => Ok(self.view(note)),
            Some(_) => self.modify(id, |n| n.reminder_timestamp = Some(timestamp)),
            None => Err("That note no longer exists.".into()),
        }
    }

    pub fn clear_reminder(&mut self, id: &str) -> Result<NoteView, String> {
        match self.state.notes.get(id) {
            Some(note) if note.reminder_timestamp.is_none() => Ok(self.view(note)),
            Some(_) => self.modify(id, |n| n.reminder_timestamp = None),
            None => Err("That note no longer exists.".into()),
        }
    }

    pub fn set_archived(&mut self, id: &str, archived: bool) -> Result<NoteView, String> {
        self.modify(id, |n| n.is_archived = archived)
    }

    /// Moving to the trash is an edit like any other: the note syncs as trashed and can come back.
    pub fn move_to_trash(&mut self, id: &str) -> Result<NoteView, String> {
        self.modify(id, |n| n.is_trashed = true)
    }

    pub fn restore_from_trash(&mut self, id: &str) -> Result<NoteView, String> {
        self.modify(id, |n| n.is_trashed = false)
    }

    /// Deletes every note in the trash for good, in one save. Returns how many went.
    pub fn empty_trash(&mut self) -> Result<usize, String> {
        let trashed: Vec<String> = self.state.notes.values().filter(|n| n.is_trashed).map(|n| n.id.clone()).collect();
        if trashed.is_empty() {
            return Ok(0);
        }
        let before = self.state.clone();
        let now = self.ids.now_millis();
        let mut images = Vec::new();
        for id in &trashed {
            sync::delete_note(&mut self.state, id, now).map_err(|e| e.to_string())?;
            images.extend(self.forget_images_of(id));
        }
        self.commit(before)?;
        let blobs = self.store.blobs();
        for image in images {
            let _ = blobs.remove(&image);
        }
        Ok(trashed.len())
    }

    /// Every label: the registry and every label on a note, one per name (compared as [`label_key`]),
    /// sorted ignoring case.
    pub fn label_names(&self) -> Vec<String> {
        let mut seen = std::collections::BTreeMap::new();
        let on_notes = self.state.notes.values().filter(|n| !n.is_trashed).flat_map(|n| n.labels.iter());
        for name in self.state.label_registry.iter().chain(on_notes) {
            seen.entry(label_key(name)).or_insert_with(|| tidy_label(name));
        }
        seen.into_values().collect()
    }

    fn check_label(name: &str) -> Result<String, String> {
        let name = tidy_label(name);
        if name.is_empty() {
            return Err("A label needs a name.".into());
        }
        if name.chars().count() > MAX_LABEL_CHARS {
            return Err(format!("A label can be at most {MAX_LABEL_CHARS} characters."));
        }
        Ok(name)
    }

    /// Adds a label to the registry. A name that already exists (ignoring case and spacing) returns
    /// the existing one rather than a near-duplicate.
    pub fn create_label(&mut self, name: &str) -> Result<String, String> {
        let name = Self::check_label(name)?;
        if let Some(existing) = self.label_names().into_iter().find(|n| label_key(n) == label_key(&name)) {
            if !self.state.label_registry.iter().any(|n| label_key(n) == label_key(&existing)) {
                let before = self.state.clone();
                self.state.label_registry.push(existing.clone());
                self.commit(before)?;
            }
            return Ok(existing);
        }
        let before = self.state.clone();
        self.state.label_registry.push(name.clone());
        self.commit(before)?;
        Ok(name)
    }

    /// Puts a label on a note, or takes it off.
    pub fn set_note_label(&mut self, id: &str, name: &str, on: bool) -> Result<NoteView, String> {
        let name = Self::check_label(name)?;
        let key = label_key(&name);
        let note = self.state.notes.get(id).ok_or("That note no longer exists.")?;
        let has = note.labels.iter().any(|l| label_key(l) == key);
        if has == on {
            return Ok(self.view(note));
        }
        if on && note.labels.len() >= MAX_LABELS_PER_NOTE {
            return Err(format!("A note can have at most {MAX_LABELS_PER_NOTE} labels."));
        }
        self.modify(id, |n| {
            if on {
                n.labels.push(name);
            } else {
                n.labels.retain(|l| label_key(l) != key);
            }
        })
    }

    /// Renames a label everywhere: the registry and every note carrying it (each such note syncs).
    /// Renaming onto an existing label merges the two.
    pub fn rename_label(&mut self, old: &str, new: &str) -> Result<(), String> {
        let new = Self::check_label(new)?;
        let (old_key, new_key) = (label_key(old), label_key(&new));
        let before = self.state.clone();
        let now = self.ids.now_millis();
        let carrying: Vec<String> = self
            .state
            .notes
            .values()
            .filter(|n| n.labels.iter().any(|l| label_key(l) == old_key))
            .map(|n| n.id.clone())
            .collect();
        for id in carrying {
            sync::edit_note(&mut self.state, &id, now, |n| {
                n.labels.retain(|l| label_key(l) != old_key && label_key(l) != new_key);
                n.labels.push(new.clone());
            })
            .map_err(|e| e.to_string())?;
        }
        self.state.label_registry.retain(|l| label_key(l) != old_key && label_key(l) != new_key);
        self.state.label_registry.push(new);
        self.commit(before)
    }

    /// Deletes a label: off every note (each syncs) and out of the registry. The notes stay.
    pub fn delete_label(&mut self, name: &str) -> Result<(), String> {
        let key = label_key(name);
        let before = self.state.clone();
        let now = self.ids.now_millis();
        let carrying: Vec<String> = self
            .state
            .notes
            .values()
            .filter(|n| n.labels.iter().any(|l| label_key(l) == key))
            .map(|n| n.id.clone())
            .collect();
        for id in carrying {
            sync::edit_note(&mut self.state, &id, now, |n| n.labels.retain(|l| label_key(l) != key))
                .map_err(|e| e.to_string())?;
        }
        self.state.label_registry.retain(|l| label_key(l) != key);
        self.commit(before)
    }

    /// Replaces a checklist note's items (the editor's ticks, edits, additions and removals).
    pub fn set_checklist(&mut self, id: &str, items: Vec<ChecklistItem>) -> Result<NoteView, String> {
        if items.len() > MAX_CHECKLIST_ITEMS {
            return Err(format!("A checklist can have at most {MAX_CHECKLIST_ITEMS} items."));
        }
        if let Some(item) = items.iter().find(|i| i.text.chars().count() > MAX_CHECKLIST_ITEM_CHARS) {
            let start: String = item.text.chars().take(20).collect();
            return Err(format!("A checklist item is longer than {MAX_CHECKLIST_ITEM_CHARS} characters ({start}…)."));
        }
        match self.state.notes.get(id) {
            Some(note) if note.checklist == items => Ok(self.view(note)),
            _ => self.modify(id, |n| n.checklist = items),
        }
    }

    /// Saves a checklist note's title and items together while preserving its `content` field.
    pub fn update_checklist_note(
        &mut self,
        id: &str,
        title: &str,
        items: Vec<ChecklistItem>,
    ) -> Result<NoteView, String> {
        check_lengths(title, "")?;
        if items.len() > MAX_CHECKLIST_ITEMS {
            return Err(format!("A checklist can have at most {MAX_CHECKLIST_ITEMS} items."));
        }
        if let Some(item) = items.iter().find(|i| i.text.chars().count() > MAX_CHECKLIST_ITEM_CHARS) {
            let start: String = item.text.chars().take(20).collect();
            return Err(format!("A checklist item is longer than {MAX_CHECKLIST_ITEM_CHARS} characters ({start}…)."));
        }
        let current = self.state.notes.get(id).ok_or("That note no longer exists.")?;
        if current.title == title && current.checklist == items {
            return Ok(self.view(current));
        }
        self.modify(id, |n| {
            n.title = title.to_string();
            n.checklist = items;
        })
    }

    /// Text to checklist, as the web editor converts: each non-empty line, trimmed, becomes an unticked
    /// item; an empty note gets one empty item.
    pub fn convert_to_checklist(&mut self, id: &str) -> Result<NoteView, String> {
        let note = self.state.notes.get(id).ok_or("That note no longer exists.")?;
        if !note.checklist.is_empty() {
            return Ok(self.view(note));
        }
        let mut lines: Vec<String> =
            note.content.lines().map(str::trim).filter(|l| !l.is_empty()).map(str::to_string).collect();
        if lines.is_empty() {
            lines.push(String::new());
        }
        if lines.len() > MAX_CHECKLIST_ITEMS {
            return Err(format!("A checklist can have at most {MAX_CHECKLIST_ITEMS} items."));
        }
        if let Some(line) = lines.iter().find(|l| l.chars().count() > MAX_CHECKLIST_ITEM_CHARS) {
            let start: String = line.chars().take(20).collect();
            return Err(format!("A checklist item is longer than {MAX_CHECKLIST_ITEM_CHARS} characters ({start}…)."));
        }
        self.modify(id, move |n| {
            n.content.clear();
            n.checklist = lines
                .into_iter()
                .enumerate()
                .map(|(position, text)| ChecklistItem { text, is_checked: false, position: position as i64 })
                .collect();
        })
    }

    /// Checklist to text: the item texts, trimmed, one per line. The ticks are not kept.
    pub fn convert_to_text(&mut self, id: &str) -> Result<NoteView, String> {
        let note = self.state.notes.get(id).ok_or("That note no longer exists.")?;
        if note.checklist.is_empty() {
            return Ok(self.view(note));
        }
        let mut items = note.checklist.clone();
        items.sort_by_key(|i| i.position);
        let content = items.iter().map(|i| i.text.trim()).collect::<Vec<_>>().join("\n");
        if content.chars().count() > MAX_CONTENT_CHARS {
            return Err(format!("The note is longer than {MAX_CONTENT_CHARS} characters."));
        }
        self.modify(id, move |n| {
            n.checklist.clear();
            n.content = content;
        })
    }

    /// Pinned first, then most recently edited.
    pub fn list(&self) -> Vec<NoteView> {
        let mut notes: Vec<&Note> = self.state.notes.values().filter(|n| !n.is_trashed).collect();
        notes.sort_by(|a, b| b.is_pinned.cmp(&a.is_pinned).then(b.timestamp.cmp(&a.timestamp)).then(b.id.cmp(&a.id)));
        notes.into_iter().map(|n| self.view(n)).collect()
    }

    /// Writes the state, and on failure puts the in-memory state back so the UI never shows a change
    /// that is not on disk.
    fn commit(&mut self, before: LocalState) -> Result<(), String> {
        if let Err(error) = self.store.save(&self.state) {
            self.state = before;
            return Err(format!("Could not save: {error}"));
        }
        Ok(())
    }

    pub fn create(&mut self, title: &str, content: &str) -> Result<NoteView, String> {
        check_lengths(title, content)?;
        let before = self.state.clone();
        let id = sync::create_note(&mut self.state, self.ids.as_mut(), |n| {
            n.title = title.to_string();
            n.content = content.to_string();
        });
        self.commit(before)?;
        Ok(self.view(&self.state.notes[&id]))
    }

    /// No-op when nothing changed, so an autosave of identical text does not re-stamp the note.
    pub fn update(&mut self, id: &str, title: &str, content: &str) -> Result<NoteView, String> {
        check_lengths(title, content)?;
        let current = self.state.notes.get(id).ok_or("That note no longer exists.")?;
        if current.title == title && current.content == content {
            return Ok(self.view(current));
        }
        let before = self.state.clone();
        let now = self.ids.now_millis();
        sync::edit_note(&mut self.state, id, now, |n| {
            n.title = title.to_string();
            n.content = content.to_string();
        })
        .map_err(|e| e.to_string())?;
        self.commit(before)?;
        Ok(self.view(&self.state.notes[id]))
    }

    pub fn set_pinned(&mut self, id: &str, pinned: bool) -> Result<NoteView, String> {
        let current = self.state.notes.get(id).ok_or("That note no longer exists.")?;
        if current.is_pinned == pinned {
            return Ok(self.view(current));
        }
        let before = self.state.clone();
        let now = self.ids.now_millis();
        sync::edit_note(&mut self.state, id, now, |n| n.is_pinned = pinned).map_err(|e| e.to_string())?;
        self.commit(before)?;
        Ok(self.view(&self.state.notes[id]))
    }

    /// Returns the deleted note so the UI can offer Undo by re-creating it.
    pub fn delete(&mut self, id: &str) -> Result<NoteView, String> {
        let removed = self.view(self.state.notes.get(id).ok_or("That note no longer exists.")?);
        let before = self.state.clone();
        let now = self.ids.now_millis();
        sync::delete_note(&mut self.state, id, now).map_err(|e| e.to_string())?;
        let images = self.forget_images_of(id);
        self.commit(before)?;
        let blobs = self.store.blobs();
        for image in images {
            let _ = blobs.remove(&image);
        }
        Ok(removed)
    }

    pub fn info(&self) -> AppInfo {
        AppInfo {
            data_file: self.store.state_path().display().to_string(),
            note_count: self.state.notes.len(),
            mode: if self.session.is_some() { "account" } else { "guest" },
        }
    }

    pub fn account(&self) -> AccountView {
        let unconfirmed_deletes =
            self.state.tombstones.values().filter(|t| t.origin == TombstoneOrigin::Local { confirmed: false }).count();
        AccountView {
            sync_available: self.config.is_some(),
            signed_in: self.session.is_some(),
            email: self.session.as_ref().and_then(|s| s.email.clone()),
            waiting: if self.session.is_some() { self.state.pending.len() + unconfirmed_deletes } else { 0 },
            last_synced_at: self.last_synced_at,
            last_error: self.last_error.clone(),
            uploads_blocked_after_wipe: self.state.uploads_blocked_after_wipe,
        }
    }

    fn auth(&self) -> Result<AuthClient, String> {
        let config = self.config.as_ref().ok_or("Sync isn't set up in this build.")?;
        AuthClient::new(&config.url, &config.publishable_key).map_err(auth_message)
    }

    /// The challenge page to open before asking for a code, when this build's project needs one.
    pub fn captcha_page_url(&self) -> Option<String> {
        self.config.as_ref().and_then(|c| c.captcha_page_url.clone())
    }

    /// Emails a sign-in code. `captcha_token` is the Turnstile token when the project requires one.
    pub fn request_code(&self, email: &str, captcha_token: Option<&str>) -> Result<(), String> {
        if !email.contains('@') {
            return Err("Enter your email address.".into());
        }
        if self.captcha_page_url().is_some() && captcha_token.is_none() {
            return Err("Sign-in needs a quick security check before we can email a code. Try again.".into());
        }
        self.auth()?.request_email_code(email, captcha_token).map_err(auth_message)
    }

    pub fn verify_code(&mut self, email: &str, code: &str) -> Result<AccountView, String> {
        let session = self.auth()?.verify_email_code(email, code).map_err(auth_message)?;
        self.signed_in(session)
    }

    #[cfg(test)]
    fn sign_in_with_password(&mut self, email: &str, password: &str) -> Result<AccountView, String> {
        let session = self.auth()?.sign_in_with_password(email, password).map_err(auth_message)?;
        self.signed_in(session)
    }

    fn signed_in(&mut self, session: Session) -> Result<AccountView, String> {
        let owner = session.user_id.clone();
        let store = Store::open(&self.root, &owner, self.protector.as_ref()).map_err(|e| e.to_string())?;
        let state = store.load().map_err(|e| e.to_string())?;
        self.vault.save(&session).map_err(|e| format!("Could not save the sign-in: {e}"))?;
        self.store = store;
        self.state = state;
        self.session = Some(session);
        self.last_synced_at = None;
        self.last_error = None;
        self.account_generation = self.account_generation.saturating_add(1);
        Ok(self.account())
    }

    /// Forgets the session and shows the guest notes. The account's notes stay sealed on disk.
    pub fn sign_out(&mut self) -> Result<AccountView, String> {
        let guest_store = Store::open(&self.root, GUEST, self.protector.as_ref()).map_err(|e| e.to_string())?;
        let guest_state = guest_store.load().map_err(|e| e.to_string())?;
        self.vault.clear().map_err(|e| e.to_string())?;
        self.store = guest_store;
        self.state = guest_state;
        self.session = None;
        self.last_synced_at = None;
        self.last_error = None;
        self.account_generation = self.account_generation.saturating_add(1);
        Ok(self.account())
    }

    fn refreshed(&mut self, auth: &AuthClient) -> Result<Session, String> {
        let current = self.session.clone().ok_or("Sign in to sync.")?;
        let token = current.refresh_token.as_deref().ok_or("The sign-in has ended; sign in again.")?;
        match auth.refresh(token) {
            Ok(session) => {
                if session.user_id != current.user_id {
                    let _ = self.store.save(&self.state);
                    let _ = self.sign_out();
                    return Err("The signed-in account changed unexpectedly; sign in again.".into());
                }
                self.vault.save(&session).map_err(|e| e.to_string())?;
                self.session = Some(session.clone());
                Ok(session)
            }
            Err(AuthError::SessionExpired | AuthError::Refused { .. }) => {
                // Save already-completed progress on the account before switching back to guest.
                let _ = self.store.save(&self.state);
                let _ = self.sign_out();
                Err("The sign-in has ended; sign in again. Your notes are still on this device.".into())
            }
            Err(other) => Err(auth_message(other)),
        }
    }

    fn handle_account_deleted_from_sync(&mut self) -> String {
        let _ = self.vault.clear();
        let _ = self.store.remove_account_data();
        self.session = None;
        self.last_synced_at = None;
        let _ = self.switch_to(GUEST);
        let message = "This account was deleted. Signed out and returned to guest notes.".to_string();
        self.last_error = Some(message.clone());
        message
    }

    /// One full sync of the signed-in account. Progress made before a failure is kept on disk, so
    /// the next sync carries on from there.
    pub fn sync_now(&mut self) -> Result<SyncSummary, String> {
        let config = self.config.clone().ok_or("Sync isn't set up in this build.")?;
        let auth = self.auth()?;
        let mut session = self.session.clone().ok_or("Sign in to sync.")?;
        if self.state.owner_id != session.user_id {
            return Err("Signed-in account mismatch; sign in again.".into());
        }
        if session.needs_refresh(self.ids.now_millis()) {
            session = self.refreshed(&auth)?;
        }
        let mut transport = SupabaseTransport::new(&config.url, &config.publishable_key, &session.access_token)
            .map_err(|e| e.to_string())?;
        // Step A: upload pending images ONLY for notes already known on the server (new notes wait
        // until Step D after `sync::sync` creates their note row).
        if let Some(url) = &config.attachments_url
            && !self.state.uploads_blocked_after_wipe
        {
            let mut client = AttachmentsClient::new(url, &session.access_token).map_err(|e| e.to_string())?;
            if matches!(self.upload_pending_images(&client, true), Err(AttachmentError::Unauthorized)) {
                session = self.refreshed(&auth)?;
                transport.set_access_token(&session.access_token);
                client.set_access_token(&session.access_token);
                let _ = self.upload_pending_images(&client, true);
            }
        }

        // Step B: pull, conflict-resolve, push pending edits, and push deletes.
        let mut result = sync::sync(&mut self.state, &mut transport, self.ids.as_mut());
        if matches!(result, Err(SyncError::Transport(TransportError::Unauthorized))) {
            session = self.refreshed(&auth)?;
            transport.set_access_token(&session.access_token);
            result = sync::sync(&mut self.state, &mut transport, self.ids.as_mut());
        }
        if matches!(result, Err(SyncError::Transport(TransportError::AccountDeleted))) {
            return Err(self.handle_account_deleted_from_sync());
        }

        // Step C & D: only when sync succeeded, clone conflict images, upload remaining pending images
        // (for newly created notes or forks), flush confirmed attachment removals, and hydrate.
        if let Ok(report) = &result {
            self.clone_forked_conflict_images(&report.forked_sources);
            if let Some(url) = &config.attachments_url {
                let mut client = AttachmentsClient::new(url, &session.access_token).map_err(|e| e.to_string())?;
                if !self.state.uploads_blocked_after_wipe
                    && matches!(self.upload_pending_images(&client, false), Err(AttachmentError::Unauthorized))
                {
                    session = self.refreshed(&auth)?;
                    transport.set_access_token(&session.access_token);
                    client.set_access_token(&session.access_token);
                    let _ = self.upload_pending_images(&client, false);
                }
                if matches!(self.flush_attachment_removals(&client, &transport), Err(AttachmentError::Unauthorized)) {
                    session = self.refreshed(&auth)?;
                    transport.set_access_token(&session.access_token);
                    client.set_access_token(&session.access_token);
                    let _ = self.flush_attachment_removals(&client, &transport);
                }
            }
            if self.hydrate_images(&transport).is_ok() {
                let keep_ids: BTreeSet<String> =
                    self.state.attachments.values().flatten().map(|a| a.id.clone()).collect();
                let _ = self.store.blobs().prune_orphans(&keep_ids);
            }
        }

        let images_waiting = self.state.attachments.values().flatten().filter(|a| a.is_pending()).count();
        // Saved whatever happened: a half-finished run's confirmed pushes must not be forgotten.
        let saved = self.store.save(&self.state).map_err(|e| format!("Could not save: {e}"));
        match result {
            Ok(report) => {
                saved?;
                self.last_synced_at = Some(self.ids.now_millis());
                self.last_error = None;
                Ok(SyncSummary {
                    uploaded: report.pushed.len(),
                    downloaded: report.pulled,
                    conflicted_copies: report.conflicted_copies.len(),
                    deleted_elsewhere: report.deleted_remotely.len(),
                    restored_by_cloud: report.restored_by_cloud.len(),
                    images_waiting,
                })
            }
            Err(error) => {
                let message = match error {
                    SyncError::Transport(TransportError::Network(_)) => {
                        "Offline. Your changes are saved here and will sync later.".to_string()
                    }
                    SyncError::Transport(TransportError::WipeGenerationStale) => {
                        "This account's cloud notes were deleted on another device. Nothing was uploaded.".to_string()
                    }
                    SyncError::Transport(TransportError::ReauthenticationRequired) => {
                        "Your sign-in is too old for this action. Sign out and sign in again.".to_string()
                    }
                    SyncError::Transport(TransportError::AccountNoteLimit) => {
                        "This account has reached its cloud storage limit. Delete notes you no longer need to sync new ones."
                            .to_string()
                    }
                    other => format!("Sync failed: {other}"),
                };
                self.last_error = Some(message.clone());
                Err(message)
            }
        }
    }

    /// Wipes all cloud notes, tombstones, and remote attachments for the signed-in account, leaving
    /// local notes intact on this device and recording the new `wipe_generation`.
    pub fn delete_cloud_data(&mut self) -> Result<(), String> {
        let config = self.config.clone().ok_or("Sync isn't set up in this build.")?;
        let auth = self.auth()?;
        let mut session = self.session.clone().ok_or("Sign in to manage cloud data.")?;
        if self.state.owner_id != session.user_id {
            return Err("Signed-in account mismatch; sign in again.".into());
        }
        if session.needs_refresh(self.ids.now_millis()) {
            session = self.refreshed(&auth)?;
        }
        let mut transport = SupabaseTransport::new(&config.url, &config.publishable_key, &session.access_token)
            .map_err(|e| e.to_string())?;
        let wipe_res = match transport.delete_all_user_cloud_data() {
            Err(TransportError::Unauthorized) => {
                session = self.refreshed(&auth)?;
                transport.set_access_token(&session.access_token);
                transport.delete_all_user_cloud_data()
            }
            other => other,
        };
        let new_gen = match wipe_res {
            Ok(gen_val) => gen_val,
            Err(TransportError::AccountDeleted) => return Err(self.handle_account_deleted_from_sync()),
            Err(TransportError::ReauthenticationRequired) => {
                return Err("Your sign-in is too old to wipe cloud data. Sign out and sign in again.".into());
            }
            Err(TransportError::Network(_)) => return Err("Offline. Check your connection and try again.".into()),
            Err(other) => return Err(format!("Could not delete cloud data: {other}")),
        };

        if let Some(url) = &config.attachments_url
            && let Ok(client) = AttachmentsClient::new(url, &session.access_token)
        {
            if let Ok(pending_deleted) = transport.list_pending_deleted_attachments() {
                for row in pending_deleted {
                    let _ = client.delete(&row.note_id, &row.attachment_id);
                }
            }
            let _ = transport.finalize_cloud_wipe();
        }

        let blobs = self.store.blobs();
        let old_attachments = std::mem::take(&mut self.state.attachments);
        for (note_id, images) in old_attachments {
            let mut kept = Vec::new();
            for mut image in images {
                if image.is_pending() {
                    kept.push(image);
                } else if let Ok(Some(bytes)) = blobs.get(&image.id)
                    && blobs.put_pending(&image.id, &bytes).is_ok()
                {
                    image.location = Location::Pending;
                    kept.push(image);
                }
            }
            if !kept.is_empty() {
                self.state.attachments.insert(note_id, kept);
            }
        }

        self.state.wipe_generation = new_gen;
        self.state.last_remote_revision = 0;
        self.state.revisions.clear();
        self.state.synced_timestamps.clear();
        self.state.known_cloud_ids.clear();
        self.state.pending.clear();
        self.state.tombstones.clear();
        self.state.attachment_removals.clear();
        self.state.uploads_blocked_after_wipe = false;
        self.store.save(&self.state).map_err(|e| format!("Could not save: {e}"))?;
        self.last_error = None;
        Ok(())
    }

    /// Adopts the current server `wipe_generation`, clears the wipe block, queues all local notes for
    /// upload, and syncs.
    pub fn reupload_after_wipe(&mut self) -> Result<SyncSummary, String> {
        let config = self.config.clone().ok_or("Sync isn't set up in this build.")?;
        let auth = self.auth()?;
        let mut session = self.session.clone().ok_or("Sign in to sync.")?;
        if self.state.owner_id != session.user_id {
            return Err("Signed-in account mismatch; sign in again.".into());
        }
        if session.needs_refresh(self.ids.now_millis()) {
            session = self.refreshed(&auth)?;
        }
        let mut transport = SupabaseTransport::new(&config.url, &config.publishable_key, &session.access_token)
            .map_err(|e| e.to_string())?;
        let gen_res = match transport.get_sync_generation() {
            Err(TransportError::Unauthorized) => {
                session = self.refreshed(&auth)?;
                transport.set_access_token(&session.access_token);
                transport.get_sync_generation()
            }
            other => other,
        };
        let remote_gen = match gen_res {
            Ok(gen_val) => gen_val,
            Err(TransportError::AccountDeleted) => return Err(self.handle_account_deleted_from_sync()),
            Err(TransportError::Network(_)) => return Err("Offline. Check your connection and try again.".into()),
            Err(other) => return Err(format!("Could not reach the server: {other}")),
        };

        self.state.wipe_generation = remote_gen;
        self.state.last_remote_revision = 0;
        self.state.uploads_blocked_after_wipe = false;
        self.state.revisions.clear();
        self.state.synced_timestamps.clear();
        self.state.known_cloud_ids.clear();
        self.state.tombstones.clear();
        self.state.pending.clear();
        let note_ids: Vec<String> = self.state.notes.keys().cloned().collect();
        for id in note_ids {
            self.state.push_seq = self.state.push_seq.saturating_add(1);
            let seq = self.state.push_seq;
            self.state.pending.insert(id, notelikeus_core::store::PendingPush { base_revision: None, seq });
        }
        self.store.save(&self.state).map_err(|e| format!("Could not save: {e}"))?;
        self.sync_now()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use notelikeus_core::crypto::PlaintextKeyProtectorForTests;

    struct TestIds(i64);
    impl IdSource for TestIds {
        fn now_millis(&mut self) -> i64 {
            self.0 += 1;
            self.0
        }
        fn random_below_1000(&mut self) -> u32 {
            1
        }
    }

    fn root(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("nlk-tauri-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        dir
    }

    fn open_with(dir: &Path, config: Option<SyncConfig>, ids: Box<dyn IdSource + Send>) -> NotesService {
        NotesService::open(dir, Box::new(PlaintextKeyProtectorForTests), ids, config).unwrap()
    }

    fn open(dir: &Path) -> NotesService {
        open_with(dir, None, Box::new(TestIds(1_000)))
    }

    /// The native app runs sync and sign-in on a worker thread, so the service must be able to move there.
    #[test]
    fn the_service_can_move_to_a_worker_thread() {
        fn is_send<T: Send>() {}
        is_send::<NotesService>();
    }

    /// A real 1x1 PNG.
    pub(crate) const TINY_PNG: &[u8] = &[
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, 0x00, 0x00,
        0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4, 0x89, 0x00, 0x00, 0x00,
        0x0D, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0xF8, 0xCF, 0xC0, 0xF0, 0x1F, 0x00, 0x05, 0x00, 0x01, 0xFF,
        0x89, 0x99, 0x3D, 0x1D, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82,
    ];

    #[test]
    fn images_are_kept_sealed_on_the_note_and_go_with_it() {
        let dir = root("images");
        let mut service = open(&dir);
        let note = service.create("Garden", "beds").unwrap();
        assert!(service.add_image(&note.id, b"%PDF-1.7 not a picture").unwrap_err().contains("PNG, JPEG, GIF or WebP"));
        assert!(service.add_image(&note.id, &vec![0u8; MAX_ATTACHMENT_BYTES + 1]).unwrap_err().contains("10 MB"));
        let with = service.add_image(&note.id, TINY_PNG).unwrap();
        assert_eq!(with.images.len(), 1);
        assert_eq!((with.images[0].mime_type.as_str(), with.images[0].pending), ("image/png", true));
        let image = with.images[0].id.clone();
        assert_eq!(service.image_source().bytes(&note.id, &image).unwrap(), TINY_PNG, "readable here, offline");

        // After a restart it is still there; removing it forgets the bytes too.
        drop(service);
        let mut service = open(&dir);
        assert_eq!(service.note(&note.id).unwrap().images.len(), 1);
        let without = service.remove_image(&note.id, &image).unwrap();
        assert!(without.images.is_empty());
        assert!(service.image_source().bytes(&note.id, &image).is_err());
        assert!(service.state.attachment_removals.is_empty(), "a pending image has nothing in the cloud to delete");

        // Deleting a note for good takes its images; an uploaded one is queued for the cloud's delete.
        let second = service.add_image(&note.id, TINY_PNG).unwrap().images[0].id.clone();
        if let Some(images) = service.state.attachments.get_mut(&note.id) {
            images[0].location = Location::Uploaded { object_key: "owners/x/notes/n/i".into() };
        }
        service.move_to_trash(&note.id).unwrap();
        service.empty_trash().unwrap();
        assert!(service.state.attachments.is_empty());
        assert_eq!(service.state.attachment_removals.get(&note.id), Some(&vec![second.clone()]));
        assert!(service.image_source().bytes(&note.id, &second).is_err());
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_conflict_review_keeps_one_version_or_both_and_trashes_nothing_for_good() {
        let dir = root("review");
        let mut service = open(&dir);
        let original = service.create("Groceries", "milk").unwrap();
        let copy = service.create("Groceries (conflicted copy)", "milk\neggs").unwrap();
        assert!(service.is_conflicted_copy(&copy.id) && !service.is_conflicted_copy(&original.id));
        let review = service.conflict_review(&copy.id).unwrap();
        assert_eq!(review.original.as_ref().map(|n| n.id.as_str()), Some(original.id.as_str()));
        assert_eq!(review.copy_lines, [("milk".to_string(), false), ("eggs".to_string(), true)]);
        assert!(service.conflict_review(&original.id).is_none(), "only a copy is reviewed");

        // Keep the copy: the original goes to the trash and the copy takes its title.
        service.keep_copy(&copy.id, &original.id).unwrap();
        assert!(service.note(&original.id).unwrap().is_trashed);
        assert_eq!(service.note(&copy.id).unwrap().title, "Groceries");

        // Keep both: the suffix goes. Keep the original: the copy goes to the trash.
        let second = service.create("Groceries (conflicted copy)", "bread").unwrap();
        service.keep_both(&second.id).unwrap();
        assert_eq!(service.note(&second.id).unwrap().title, "Groceries");
        let third = service.create("Trip (conflicted copy)", "").unwrap();
        service.keep_original(&third.id).unwrap();
        assert!(service.note(&third.id).unwrap().is_trashed);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn search_uses_operators_and_saved_filters_survive_a_restart() {
        let dir = root("search");
        let mut service = open(&dir);
        let work = service.create("Quarterly report", "slides").unwrap();
        service.set_note_label(&work.id, "Work", true).unwrap();
        let list = service.create("Packing", "passport\ncharger").unwrap();
        service.convert_to_checklist(&list.id).unwrap();
        let old = service.create("Receipts", "scanned").unwrap();
        service.set_archived(&old.id, true).unwrap();

        let ids = |view: SearchView| view.notes.into_iter().map(|n| n.title).collect::<Vec<_>>();
        let now = 2_000_000_000_000;
        let typed = |text: &str| Filters { search: text.into(), ..Filters::default() };
        assert_eq!(ids(service.search(&typed("label:work"), now)), ["Quarterly report"]);
        assert_eq!(ids(service.search(&typed("has:unchecked"), now)), ["Packing"]);
        let archived = service.search(&typed("is:archived"), now);
        assert_eq!(archived.scope, NoteScope::Archive);
        assert_eq!(ids(archived), ["Receipts"]);
        assert_eq!(service.search(&typed("in:all"), now).notes.len(), 3);
        assert_eq!(service.smart_view_counts(now), [0, 1, 1], "reminders, unfinished, unlabelled (active only)");

        let filters = typed("label:work");
        service.save_filter("Work stuff", &filters).unwrap();
        assert!(service.save_filter("  ", &filters).is_err());
        drop(service);
        let mut service = open(&dir);
        assert_eq!(service.saved_filters().len(), 1);
        assert_eq!(service.saved_filters()[0].filters.search, "label:work");
        service.delete_saved_filter("Work stuff").unwrap();
        assert!(service.saved_filters().is_empty());
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn notes_persist_across_restarts() {
        let dir = root("persist");
        let mut service = open(&dir);
        let note = service.create("Trip", "pack").unwrap();
        service.update(&note.id, "Trip", "pack, passport").unwrap();
        drop(service);
        let reopened = open(&dir);
        let listed = reopened.list();
        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0].content, "pack, passport");
        assert!(listed[0].unsynced, "guest notes are never confirmed by a cloud");
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn an_unchanged_autosave_does_not_restamp_the_note() {
        let dir = root("noop");
        let mut service = open(&dir);
        let note = service.create("A", "b").unwrap();
        let again = service.update(&note.id, "A", "b").unwrap();
        assert_eq!(again.timestamp, note.timestamp);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn pinned_notes_come_first_then_newest() {
        let dir = root("order");
        let mut service = open(&dir);
        let old = service.create("old", "").unwrap();
        let _mid = service.create("mid", "").unwrap();
        let _new = service.create("new", "").unwrap();
        service.set_pinned(&old.id, true).unwrap();
        let titles: Vec<String> = service.list().into_iter().map(|n| n.title).collect();
        assert_eq!(titles, ["old", "new", "mid"]);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn delete_returns_the_note_and_removes_it_for_good() {
        let dir = root("delete");
        let mut service = open(&dir);
        let note = service.create("gone", "soon").unwrap();
        let removed = service.delete(&note.id).unwrap();
        assert_eq!(removed.content, "soon");
        assert!(service.list().is_empty());
        assert!(open(&dir).list().is_empty());
        assert!(service.update(&note.id, "x", "y").is_err());
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn over_long_notes_are_refused_before_anything_is_saved() {
        let dir = root("limits");
        let mut service = open(&dir);
        assert!(service.create(&"t".repeat(MAX_TITLE_CHARS + 1), "").is_err());
        assert!(service.create("", &"c".repeat(MAX_CONTENT_CHARS + 1)).is_err());
        // Counted in characters, as Postgres char_length does: 2000 emoji is a valid title.
        assert!(service.create(&"😀".repeat(MAX_TITLE_CHARS), "").is_ok());
        assert_eq!(service.list().len(), 1);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_failed_save_leaves_the_previous_state_in_memory() {
        let dir = root("failed-save");
        let mut service = open(&dir);
        service.create("kept", "").unwrap();
        // Block rotation of `state.nlkr` to `state.nlkr.prev` by putting a directory in its way.
        let prev = service.store.state_path().with_extension("nlkr.prev");
        std::fs::create_dir_all(&prev).unwrap();
        assert!(service.create("lost", "").is_err());
        let titles: Vec<String> = service.list().into_iter().map(|n| n.title).collect();
        assert_eq!(titles, ["kept"]);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn without_a_configured_project_there_is_no_sync() {
        let dir = root("no-sync");
        let mut service = open(&dir);
        let account = service.account();
        assert!(!account.sync_available && !account.signed_in);
        assert!(service.request_code("someone@example.test", None).is_err());
        assert!(service.sync_now().is_err());
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_project_that_needs_the_security_check_never_asks_without_a_token() {
        let dir = root("needs-captcha");
        let config = SyncConfig {
            // Port 9 (discard): if the check were skipped, the request would fail as a network error instead.
            url: "http://127.0.0.1:9".into(),
            publishable_key: "k".into(),
            captcha_page_url: Some("https://notelikeus.pages.dev/captcha".into()),
            attachments_url: None,
        };
        let service = open_with(&dir, Some(config), Box::new(TestIds(1)));
        assert_eq!(service.captcha_page_url().as_deref(), Some("https://notelikeus.pages.dev/captcha"));
        let error = service.request_code("someone@example.test", None).unwrap_err();
        assert!(error.contains("security check"), "{error}");
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn an_unreadable_saved_session_is_forgotten_and_the_notes_still_open() {
        let dir = root("bad-session");
        let mut service = open(&dir);
        service.create("guest note", "").unwrap();
        drop(service);
        std::fs::write(dir.join("session.nlkr"), b"damaged").unwrap();
        let reopened = open(&dir);
        assert!(!reopened.account().signed_in);
        assert!(reopened.account().last_error.is_some());
        assert_eq!(reopened.list().len(), 1);
        assert!(!dir.join("session.nlkr").exists());
        std::fs::remove_dir_all(dir).unwrap();
    }

    /// Against a LOCAL Supabase stack only; see crates/notelikeus-core/tests/live_supabase.rs.
    fn live_config() -> Option<SyncConfig> {
        let url = std::env::var("NLK_TEST_SUPABASE_URL").ok()?;
        assert!(url.starts_with("http://127.0.0.1:") || url.starts_with("http://localhost:"));
        Some(SyncConfig {
            url,
            publishable_key: std::env::var("NLK_TEST_SUPABASE_KEY").ok()?,
            captcha_page_url: None,
            attachments_url: std::env::var("NLK_TEST_ATTACHMENTS_URL").ok(),
        })
    }

    #[test]
    fn signed_in_notes_sync_between_installs_and_stay_apart_from_guest_notes() {
        let Some(config) = live_config() else {
            eprintln!("skipped: set NLK_TEST_SUPABASE_URL and NLK_TEST_SUPABASE_KEY");
            return;
        };
        let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos();
        let email = format!("nlk-tauri-{nanos}@example.test");
        AuthClient::new(&config.url, &config.publishable_key)
            .unwrap()
            .sign_up(&email, "a-long-test-password-1")
            .unwrap();

        let (dir_a, dir_b) = (root("live-a"), root("live-b"));
        let mut laptop = open_with(&dir_a, Some(config.clone()), Box::new(TestIds(1_000_000)));
        laptop.create("written as a guest", "").unwrap();
        laptop.sign_in_with_password(&email, "a-long-test-password-1").unwrap();
        assert!(laptop.list().is_empty(), "guest notes are not merged into the account");
        laptop.create("Groceries", "milk").unwrap();
        assert_eq!(laptop.account().waiting, 1);
        let summary = laptop.sync_now().unwrap();
        assert_eq!(summary.uploaded, 1);
        assert_eq!(laptop.account().waiting, 0);

        // Another install signs in to the same account and gets the note.
        let mut desktop = open_with(&dir_b, Some(config.clone()), Box::new(TestIds(9_000_000)));
        desktop.sign_in_with_password(&email, "a-long-test-password-1").unwrap();
        desktop.sync_now().unwrap();
        assert_eq!(desktop.list().iter().map(|n| n.title.as_str()).collect::<Vec<_>>(), ["Groceries"]);

        // The sign-in survives a restart; signing out brings the guest notes back.
        drop(laptop);
        let mut laptop = open_with(&dir_a, Some(config), Box::new(TestIds(2_000_000)));
        assert!(laptop.account().signed_in);
        assert_eq!(laptop.list().len(), 1);
        laptop.sign_out().unwrap();
        assert_eq!(laptop.list().iter().map(|n| n.title.as_str()).collect::<Vec<_>>(), ["written as a guest"]);

        std::fs::remove_dir_all(dir_a).unwrap();
        std::fs::remove_dir_all(dir_b).unwrap();
    }

    #[test]
    fn signed_in_images_upload_between_installs_and_delete_through_local_worker() {
        let Some(config) = live_config() else {
            eprintln!("skipped: set NLK_TEST_SUPABASE_URL and NLK_TEST_SUPABASE_KEY");
            return;
        };
        let Some(worker) = config.attachments_url.as_deref() else {
            eprintln!("skipped: set NLK_TEST_ATTACHMENTS_URL to a local attachments Worker");
            return;
        };
        assert!(
            worker.starts_with("http://127.0.0.1:") || worker.starts_with("http://localhost:"),
            "this test uploads and deletes data, so it only runs against a local Worker, not {worker}"
        );

        let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos();
        let email = format!("nlk-native-images-{nanos}@example.test");
        let auth = AuthClient::new(&config.url, &config.publishable_key).unwrap();
        auth.sign_up(&email, "a-long-test-password-1").unwrap();

        let (dir_a, dir_b) = (root("images-live-a"), root("images-live-b"));
        let mut laptop = open_with(&dir_a, Some(config.clone()), Box::new(TestIds(1_000_000)));
        laptop.sign_in_with_password(&email, "a-long-test-password-1").unwrap();
        let note = laptop.create("A picture", "kept sealed on each device").unwrap();
        laptop.sync_now().unwrap(); // The Worker only accepts uploads for a note that already exists.
        let with_image = laptop.add_image(&note.id, TINY_PNG).unwrap();
        let image_id = with_image.images[0].id.clone();
        assert!(with_image.images[0].pending);

        let uploaded = laptop.sync_now().unwrap();
        assert_eq!(uploaded.images_waiting, 0, "the Worker accepted the image");
        assert!(!laptop.note(&note.id).unwrap().images[0].pending, "the upload is sealed in the local cache");

        let mut desktop = open_with(&dir_b, Some(config.clone()), Box::new(TestIds(9_000_000)));
        desktop.sign_in_with_password(&email, "a-long-test-password-1").unwrap();
        desktop.sync_now().unwrap();
        let remote_image = desktop.note(&note.id).unwrap().images[0].clone();
        assert_eq!(remote_image.id, image_id);
        assert!(!remote_image.pending, "the other install got the committed image listing");
        assert_eq!(desktop.image_source().bytes(&note.id, &image_id).unwrap(), TINY_PNG);

        // Check the Worker itself after the app cached a copy, then remove through the other install.
        let access_token = desktop.session.as_ref().unwrap().access_token.clone();
        let client = AttachmentsClient::new(worker, &access_token).unwrap();
        assert_eq!(client.download(&note.id, &image_id).unwrap(), TINY_PNG);
        desktop.remove_image(&note.id, &image_id).unwrap();
        desktop.sync_now().unwrap();
        assert_eq!(client.download(&note.id, &image_id).unwrap_err(), AttachmentError::NotFound);
        assert!(desktop.note(&note.id).unwrap().images.is_empty());

        laptop.delete(&note.id).unwrap();
        laptop.sync_now().unwrap();
        drop((laptop, desktop));
        std::fs::remove_dir_all(dir_a).unwrap();
        std::fs::remove_dir_all(dir_b).unwrap();
    }

    #[test]
    fn views_count_notes_archive_and_trash_and_list_what_each_shows() {
        let dir = root("scopes");
        let mut service = open(&dir);
        let a = service.create("Active", "").unwrap();
        let b = service.create("Archived", "").unwrap();
        let c = service.create("Trashed", "").unwrap();
        service.set_archived(&b.id, true).unwrap();
        service.move_to_trash(&c.id).unwrap();
        assert_eq!(service.counts(), ScopeCounts { notes: 1, archive: 1, trash: 1 });
        let titles = |s: &NotesService, scope| s.list_in(scope).into_iter().map(|n| n.title).collect::<Vec<_>>();
        assert_eq!(titles(&service, Scope::Notes), ["Active"]);
        assert_eq!(titles(&service, Scope::Archive), ["Archived"]);
        assert_eq!(titles(&service, Scope::Trash), ["Trashed"]);
        // Restored notes come back to where they were; trash wins over archive while trashed.
        service.restore_from_trash(&c.id).unwrap();
        service.move_to_trash(&b.id).unwrap();
        assert_eq!(service.counts(), ScopeCounts { notes: 2, archive: 0, trash: 1 });
        assert!(service.note(&b.id).unwrap().is_archived, "a trashed archived note keeps its archive flag");
        let _ = a;
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn colour_archive_and_trash_survive_a_restart_and_are_queued_for_sync() {
        let dir = root("flags-persist");
        let mut service = open(&dir);
        let note = service.create("Note", "words").unwrap();
        let green = notelikeus_core::palette::to_store(4, false);
        service.set_color(&note.id, green).unwrap();
        service.set_archived(&note.id, true).unwrap();
        drop(service);
        let service = open(&dir);
        let reopened = service.note(&note.id).unwrap();
        assert_eq!((reopened.color, reopened.is_archived), (green, true));
        assert!(reopened.unsynced);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn emptying_the_trash_deletes_only_trashed_notes_for_good() {
        let dir = root("empty-trash");
        let mut service = open(&dir);
        let keep = service.create("Keep", "").unwrap();
        for title in ["Gone 1", "Gone 2"] {
            let n = service.create(title, "").unwrap();
            service.move_to_trash(&n.id).unwrap();
        }
        assert_eq!(service.empty_trash().unwrap(), 2);
        assert_eq!(service.counts(), ScopeCounts { notes: 1, archive: 0, trash: 0 });
        assert!(service.note(&keep.id).is_some());
        assert_eq!(open(&dir).counts().trash, 0, "on disk too");
        assert_eq!(service.empty_trash().unwrap(), 0);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn labels_are_one_per_name_and_rename_and_delete_reach_every_note() {
        let dir = root("labels");
        let mut service = open(&dir);
        assert_eq!(service.create_label("  Work  ").unwrap(), "Work");
        assert_eq!(service.create_label("WORK").unwrap(), "Work", "the same name in another case is the same label");
        assert!(service.create_label("   ").is_err());
        service.create_label("Home").unwrap();
        assert_eq!(service.label_names(), ["Home", "Work"]);

        let a = service.create("A", "").unwrap();
        let b = service.create("B", "").unwrap();
        service.set_note_label(&a.id, "work", true).unwrap();
        service.set_note_label(&b.id, "Work", true).unwrap();
        service.set_note_label(&b.id, "Errands", true).unwrap();
        assert_eq!(service.note(&b.id).unwrap().labels, ["Work", "Errands"]);
        assert_eq!(service.label_names(), ["Errands", "Home", "Work"], "a label used on a note is known");

        service.rename_label("Work", "Job").unwrap();
        assert_eq!(service.note(&a.id).unwrap().labels, ["Job"]);
        assert_eq!(service.label_names(), ["Errands", "Home", "Job"]);
        // Renaming onto an existing label merges: no note ends up with it twice.
        service.rename_label("Errands", "job").unwrap();
        assert_eq!(service.note(&b.id).unwrap().labels, ["job"]);

        service.delete_label("JOB").unwrap();
        assert!(service.note(&a.id).unwrap().labels.is_empty());
        assert!(service.note(&b.id).unwrap().labels.is_empty());
        assert_eq!(service.label_names(), ["Home"]);
        drop(service);
        assert_eq!(open(&dir).label_names(), ["Home"], "the registry is saved");
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_note_holds_at_most_the_servers_number_of_labels() {
        let dir = root("label-limit");
        let mut service = open(&dir);
        let note = service.create("Many", "").unwrap();
        for i in 0..MAX_LABELS_PER_NOTE {
            service.set_note_label(&note.id, &format!("l{i}"), true).unwrap();
        }
        assert!(service.set_note_label(&note.id, "one too many", true).is_err());
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn checklists_convert_both_ways_as_the_web_editor_does() {
        let dir = root("checklist");
        let mut service = open(&dir);
        let note = service.create("Groceries", "  milk \n\n eggs\nbread  ").unwrap();
        let list = service.convert_to_checklist(&note.id).unwrap();
        assert_eq!(list.content, "");
        let texts: Vec<_> = list.checklist.iter().map(|i| (i.text.as_str(), i.is_checked, i.position)).collect();
        assert_eq!(texts, [("milk", false, 0), ("eggs", false, 1), ("bread", false, 2)]);

        let mut items = list.checklist.clone();
        items[1].is_checked = true;
        items.push(ChecklistItem { text: "lemons".into(), is_checked: false, position: 3 });
        let ticked = service.set_checklist(&note.id, items).unwrap();
        assert_eq!(ticked.checklist.len(), 4);
        assert!(ticked.checklist[1].is_checked);

        let text = service.convert_to_text(&note.id).unwrap();
        assert!(text.checklist.is_empty());
        assert_eq!(text.content, "milk\neggs\nbread\nlemons", "ticks are not kept, order is");

        let empty = service.create("Empty", "").unwrap();
        let one = service.convert_to_checklist(&empty.id).unwrap();
        assert_eq!(one.checklist.len(), 1, "an empty note becomes one empty item");
        assert!(
            service
                .set_checklist(
                    &note.id,
                    vec![
                        ChecklistItem { text: String::new(), is_checked: false, position: 0 };
                        MAX_CHECKLIST_ITEMS + 1
                    ]
                )
                .is_err()
        );
        std::fs::remove_dir_all(dir).unwrap();
    }

    fn contracts_dir() -> PathBuf {
        let mut dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        loop {
            let candidate = dir.join("contracts");
            if candidate.join("README.md").exists() {
                return candidate;
            }
            assert!(dir.pop(), "contracts/ not found");
        }
    }

    #[test]
    fn backup_limits_match_contracts_fixture() {
        let text = std::fs::read_to_string(contracts_dir().join("backup/import-limits.json")).unwrap();
        let limits: serde_json::Value = serde_json::from_str(&text).unwrap();
        assert_eq!(MAX_TITLE_CHARS as u64, limits["maxNoteTitleChars"].as_u64().unwrap());
        assert_eq!(MAX_CONTENT_CHARS as u64, limits["maxNoteContentChars"].as_u64().unwrap());
        assert_eq!(MAX_CHECKLIST_ITEMS as u64, limits["maxNoteChecklistItems"].as_u64().unwrap());
        assert_eq!(MAX_LABELS_PER_NOTE as u64, limits["maxNoteLabels"].as_u64().unwrap());
        assert_eq!(
            backup::contract_limits(),
            (
                limits["maxJsonDepth"].as_u64().unwrap() as usize,
                limits["maxBackupNotes"].as_u64().unwrap() as usize,
                limits["maxBackupLabels"].as_u64().unwrap() as usize,
            )
        );
    }

    #[test]
    fn v3_kotlin_and_web_fixtures_match_expected_normal_form() {
        let contracts = contracts_dir();
        let expected: serde_json::Value =
            serde_json::from_slice(&std::fs::read(contracts.join("backup/v3-expected-notes.json")).unwrap()).unwrap();

        for fixture_path in ["backup/v3-kotlin-export.json", "backup/v3-web-export.json"] {
            let dir = root("v3-contract");
            let mut service =
                NotesService::open(&dir, Box::new(PlaintextKeyProtectorForTests), Box::new(SystemIds), None).unwrap();
            let report = service.import_backup_v3(&std::fs::read(contracts.join(fixture_path)).unwrap()).unwrap();
            assert_eq!(report.labels_created, expected["labelsCreated"].as_u64().unwrap() as usize, "{fixture_path}");
            let mut actual: Vec<serde_json::Value> = [Scope::Notes, Scope::Archive, Scope::Trash]
                .into_iter()
                .flat_map(|scope| service.list_in_sorted(scope, SortOrder::Oldest))
                .map(|note| {
                    let mut labels = note.labels;
                    labels.sort();
                    serde_json::json!({
                        "title": note.title,
                        "content": note.content,
                        "timestamp": note.timestamp,
                        "color": note.color,
                        "isPinned": note.is_pinned,
                        "isArchived": note.is_archived,
                        "isTrashed": note.is_trashed,
                        "reminderTimestamp": note.reminder_timestamp,
                        "labels": labels,
                        "checklist": note.checklist,
                    })
                })
                .collect();
            actual.sort_by_key(|v| v["timestamp"].as_i64().unwrap_or_default());
            assert_eq!(serde_json::Value::Array(actual), expected["notes"], "{fixture_path}");
            std::fs::remove_dir_all(dir).unwrap();
        }

        let dir = root("v3-rich");
        let mut service = open(&dir);
        let rich = service
            .import_backup_v3(&std::fs::read(contracts.join("backup/v3-cross-platform-rich.json")).unwrap())
            .unwrap();
        assert!(rich.notes_imported >= 3);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn update_checklist_note_preserves_dual_content() {
        let dir = root("dual-update");
        let mut service = open(&dir);
        let backup = serde_json::json!({
            "version": 3,
            "exportedAt": 1_767_225_600_000_i64,
            "labels": [],
            "notes": [{
                "id": 1,
                "title": "Dual",
                "content": "keep this prose intact",
                "timestamp": 1_767_225_000_000_i64,
                "color": 0,
                "isPinned": false,
                "isArchived": false,
                "isTrashed": false,
                "position": 0,
                "labels": [],
                "checklist": [{ "text": "step 1", "isChecked": false, "position": 0 }]
            }]
        });
        service.import_backup_v3(backup.to_string().as_bytes()).unwrap();
        let id = service.list()[0].id.clone();
        let updated = service
            .update_checklist_note(
                &id,
                "Dual Edited",
                vec![ChecklistItem { text: "step 1".into(), is_checked: true, position: 0 }],
            )
            .unwrap();
        assert_eq!(updated.content, "keep this prose intact");
        assert_eq!(updated.title, "Dual Edited");
        assert!(updated.checklist[0].is_checked);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn diagnostics_allows_sensitive_words_inside_note_text() {
        let dir = root("diag-false-positive");
        let mut service = open(&dir);
        service.create("schemaVersion", "token password secret @ example.com").unwrap();
        let json = service.diagnostics_json().unwrap();
        let report: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(report["notes"]["total"], 1);
        assert!(!json.contains("password"));
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn convert_to_checklist_errors_instead_of_truncating_overflow() {
        let dir = root("checklist-overflow");
        let mut service = open(&dir);
        let many_lines = (0..=MAX_CHECKLIST_ITEMS).map(|i| format!("item {i}")).collect::<Vec<_>>().join("\n");
        let n1 = service.create("Too many lines", &many_lines).unwrap();
        assert!(service.convert_to_checklist(&n1.id).is_err());
        assert!(service.note(&n1.id).unwrap().checklist.is_empty());

        let long_line = "x".repeat(MAX_CHECKLIST_ITEM_CHARS + 1);
        let n2 = service.create("Long line", &long_line).unwrap();
        assert!(service.convert_to_checklist(&n2.id).is_err());
        assert_eq!(service.note(&n2.id).unwrap().content, long_line);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn failed_signed_in_store_load_preserves_guest_notes() {
        let dir = root("signin-corrupt-account");
        let mut service = open(&dir);
        service.create("Guest Keep", "stay safe").unwrap();

        let corrupt_store = Store::open(&dir, "corrupt-user-1", &PlaintextKeyProtectorForTests).unwrap();
        std::fs::write(corrupt_store.state_path(), b"damaged-account-bytes").unwrap();

        let bad_session = Session {
            access_token: "a".into(),
            refresh_token: Some("r".into()),
            user_id: "corrupt-user-1".into(),
            email: Some("u@example.test".into()),
            expires_at_ms: 9_999_999_999_999,
        };
        assert!(service.signed_in(bad_session).is_err());
        assert!(!service.account().signed_in);
        let guest_notes = service.list();
        assert_eq!(guest_notes.len(), 1);
        assert_eq!(guest_notes[0].title, "Guest Keep");
        std::fs::remove_dir_all(dir).unwrap();
    }
}
