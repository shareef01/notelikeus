//! What the window's commands do, without Tauri: open the right account's store, list, create, update
//! and delete notes (saving after every change), sign in with an emailed code, sync, and sign out.
//! Kept apart from `main.rs` so it is unit-tested directly.
//!
//! Each account has its own sealed store (`accounts/<owner>`); guest notes live under `__guest__`.
//! Signing in switches to the account's store and signing out switches back: notes written as a guest
//! are not merged into an account, and an account's notes stay sealed on disk after sign-out.

use std::path::{Path, PathBuf};

use notelikeus_core::auth::{AuthClient, AuthError};
use notelikeus_core::conflict;
use notelikeus_core::crypto::KeyProtector;
use notelikeus_core::http::SupabaseTransport;
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
}

impl SyncConfig {
    /// `NLK_SUPABASE_URL` and `NLK_SUPABASE_KEY`, read at run time, falling back to the values a
    /// build was compiled with. Unset in both places means no sync, so this experiment never reaches
    /// a project by accident.
    pub fn from_env() -> Option<SyncConfig> {
        let url = std::env::var("NLK_SUPABASE_URL").ok().or(option_env!("NLK_SUPABASE_URL").map(str::to_string))?;
        let publishable_key =
            std::env::var("NLK_SUPABASE_KEY").ok().or(option_env!("NLK_SUPABASE_KEY").map(str::to_string))?;
        let captcha_page_url = std::env::var("NLK_CAPTCHA_PAGE_URL")
            .ok()
            .or(option_env!("NLK_CAPTCHA_PAGE_URL").map(str::to_string))
            .filter(|page| !page.trim().is_empty());
        (!url.trim().is_empty() && !publishable_key.trim().is_empty()).then_some(SyncConfig {
            url,
            publishable_key,
            captcha_page_url,
        })
    }
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

/// `validate_note_payload`'s limits on labels and checklists.
pub const MAX_LABELS_PER_NOTE: usize = 100;
pub const MAX_CHECKLIST_ITEMS: usize = 500;
pub const MAX_LABEL_CHARS: usize = 2_000;

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
    /// Opens the signed-in account's notes, or the guest notes when nobody is signed in. A saved
    /// session that cannot be read is forgotten (with a message) rather than hiding the notes.
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
            Err(error) => {
                let _ = vault.clear();
                last_error = Some(format!("The saved sign-in could not be read ({error}); sign in again."));
                None
            }
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
        })
    }

    fn switch_to(&mut self, owner: &str) -> Result<(), String> {
        let store = Store::open(&self.root, owner, self.protector.as_ref()).map_err(|e| e.to_string())?;
        self.state = store.load().map_err(|e| e.to_string())?;
        self.store = store;
        self.last_synced_at = None;
        Ok(())
    }

    fn view(&self, note: &Note) -> NoteView {
        NoteView {
            id: note.id.clone(),
            title: note.title.clone(),
            content: note.content.clone(),
            timestamp: note.timestamp,
            is_pinned: note.is_pinned,
            unsynced: self.state.pending.contains_key(&note.id),
            color: note.color,
            is_archived: note.is_archived,
            is_trashed: note.is_trashed,
            labels: note.labels.clone(),
            checklist: note.checklist.clone(),
        }
    }

    fn in_scope(note: &Note, scope: Scope) -> bool {
        match scope {
            Scope::Notes => !note.is_trashed && !note.is_archived,
            Scope::Archive => !note.is_trashed && note.is_archived,
            Scope::Trash => note.is_trashed,
        }
    }

    /// The notes one view shows: in Notes, pinned first; then most recently edited.
    pub fn list_in(&self, scope: Scope) -> Vec<NoteView> {
        let mut notes: Vec<&Note> = self.state.notes.values().filter(|n| Self::in_scope(n, scope)).collect();
        notes.sort_by(|a, b| {
            let pin = if scope == Scope::Notes { b.is_pinned.cmp(&a.is_pinned) } else { std::cmp::Ordering::Equal };
            pin.then(b.timestamp.cmp(&a.timestamp)).then(b.id.cmp(&a.id))
        });
        notes.into_iter().map(|n| self.view(n)).collect()
    }

    /// Every note in the order a list shows them before any search ranks them: active pinned notes
    /// first, then most recently edited.
    fn in_list_order(&self) -> Vec<&Note> {
        let mut notes: Vec<&Note> = self.state.notes.values().collect();
        let leads = |n: &Note| n.is_pinned && !n.is_archived && !n.is_trashed;
        notes.sort_by(|a, b| leads(b).cmp(&leads(a)).then(b.timestamp.cmp(&a.timestamp)).then(b.id.cmp(&a.id)));
        notes
    }

    /// The notes `filters` picks out, search box operators included, as of `now` (epoch millis).
    pub fn search(&self, filters: &Filters, now: i64) -> SearchView {
        let labels = self.label_names();
        let outcome = query::search(self.in_list_order(), filters, &labels, now, &LocalDays::at(now));
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
        let title = self
            .state
            .notes
            .get(copy_id)
            .map(|n| conflict::original_title_of(&n.title).unwrap_or_default().to_string())
            .ok_or("That note no longer exists.")?;
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
        for id in &trashed {
            sync::delete_note(&mut self.state, id, now).map_err(|e| e.to_string())?;
        }
        self.commit(before)?;
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
        if let Some(item) = items.iter().find(|i| i.text.chars().count() > MAX_CONTENT_CHARS) {
            let start: String = item.text.chars().take(20).collect();
            return Err(format!("A checklist item is longer than {MAX_CONTENT_CHARS} characters ({start}…)."));
        }
        match self.state.notes.get(id) {
            Some(note) if note.checklist == items => Ok(self.view(note)),
            _ => self.modify(id, |n| n.checklist = items),
        }
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
        lines.truncate(MAX_CHECKLIST_ITEMS);
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
        self.commit(before)?;
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
        }
    }

    fn auth(&self) -> Result<AuthClient, String> {
        let config = self.config.as_ref().ok_or("Sync isn't set up in this build.")?;
        AuthClient::new(&config.url, &config.publishable_key).map_err(auth_message)
    }

    /// Emails a sign-in code. The hosted project's verification check is not supported yet (see auth.rs).
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
        self.vault.save(&session).map_err(|e| format!("Could not save the sign-in: {e}"))?;
        let owner = session.user_id.clone();
        self.session = Some(session);
        self.last_error = None;
        self.switch_to(&owner)?;
        Ok(self.account())
    }

    /// Forgets the session and shows the guest notes. The account's notes stay sealed on disk.
    pub fn sign_out(&mut self) -> Result<AccountView, String> {
        self.vault.clear().map_err(|e| e.to_string())?;
        self.session = None;
        self.last_error = None;
        self.switch_to(GUEST)?;
        Ok(self.account())
    }

    fn refreshed(&mut self, auth: &AuthClient) -> Result<Session, String> {
        let current = self.session.clone().ok_or("Sign in to sync.")?;
        let token = current.refresh_token.as_deref().ok_or("The sign-in has ended; sign in again.")?;
        match auth.refresh(token) {
            Ok(session) => {
                self.vault.save(&session).map_err(|e| e.to_string())?;
                self.session = Some(session.clone());
                Ok(session)
            }
            Err(AuthError::SessionExpired | AuthError::Refused { .. }) => {
                // The account's notes stay on disk; only the sign-in goes.
                let _ = self.sign_out();
                Err("The sign-in has ended; sign in again. Your notes are still on this device.".into())
            }
            Err(other) => Err(auth_message(other)),
        }
    }

    /// One full sync of the signed-in account. Progress made before a failure is kept on disk, so
    /// the next sync carries on from there.
    pub fn sync_now(&mut self) -> Result<SyncSummary, String> {
        let config = self.config.clone().ok_or("Sync isn't set up in this build.")?;
        let auth = self.auth()?;
        let mut session = self.session.clone().ok_or("Sign in to sync.")?;
        if session.needs_refresh(self.ids.now_millis()) {
            session = self.refreshed(&auth)?;
        }
        let mut transport = SupabaseTransport::new(&config.url, &config.publishable_key, &session.access_token)
            .map_err(|e| e.to_string())?;
        let mut result = sync::sync(&mut self.state, &mut transport, self.ids.as_mut());
        if matches!(result, Err(SyncError::Transport(TransportError::Unauthorized))) {
            session = self.refreshed(&auth)?;
            transport.set_access_token(&session.access_token);
            result = sync::sync(&mut self.state, &mut transport, self.ids.as_mut());
        }
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
                    other => format!("Sync failed: {other}"),
                };
                self.last_error = Some(message.clone());
                Err(message)
            }
        }
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
        // Make the account folder unwritable for the temp file by putting a directory in its way.
        let temp = service.store.state_path().with_extension("nlkr.tmp");
        std::fs::create_dir_all(&temp).unwrap();
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
        Some(SyncConfig { url, publishable_key: std::env::var("NLK_TEST_SUPABASE_KEY").ok()?, captcha_page_url: None })
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
}
