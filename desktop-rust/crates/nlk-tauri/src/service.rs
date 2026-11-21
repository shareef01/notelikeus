//! What the window's commands do, without Tauri: open the guest store, list, create, update and
//! delete notes, saving after every change. Kept apart from `main.rs` so it is unit-tested directly.

use std::path::{Path, PathBuf};

use notelikeus_core::crypto::KeyProtector;
use notelikeus_core::model::Note;
use notelikeus_core::store::{LocalState, Store};
use notelikeus_core::sync::{self, IdSource};
use serde::Serialize;

pub const GUEST: &str = "__guest__";
/// `validate_note_payload` limits (Postgres `char_length`, so Unicode scalar values).
pub const MAX_TITLE_CHARS: usize = 2_000;
pub const MAX_CONTENT_CHARS: usize = 100_000;

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
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppInfo {
    pub data_file: String,
    pub note_count: usize,
    pub mode: &'static str,
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
    store: Store,
    state: LocalState,
    ids: Box<dyn IdSource + Send>,
}

/// `%APPDATA%\NotelikeusRustExperimental`, or `NLK_RUST_DATA_DIR` when set (tests, side-by-side runs).
pub fn default_data_dir() -> PathBuf {
    if let Some(dir) = std::env::var_os("NLK_RUST_DATA_DIR") {
        return PathBuf::from(dir);
    }
    std::env::var_os("APPDATA")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."))
        .join("NotelikeusRustExperimental")
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

impl NotesService {
    pub fn open(root: &Path, protector: &dyn KeyProtector, ids: Box<dyn IdSource + Send>) -> Result<Self, String> {
        let store = Store::open(root, GUEST, protector).map_err(|e| e.to_string())?;
        let state = store.load().map_err(|e| e.to_string())?;
        Ok(NotesService { store, state, ids })
    }

    fn view(&self, note: &Note) -> NoteView {
        NoteView {
            id: note.id.clone(),
            title: note.title.clone(),
            content: note.content.clone(),
            timestamp: note.timestamp,
            is_pinned: note.is_pinned,
            unsynced: self.state.pending.contains_key(&note.id),
        }
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
            mode: "guest",
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

    fn open(dir: &Path) -> NotesService {
        NotesService::open(dir, &PlaintextKeyProtectorForTests, Box::new(TestIds(1_000))).unwrap()
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
        let new = service.create("new", "").unwrap();
        service.set_pinned(&old.id, true).unwrap();
        let titles: Vec<String> = service.list().into_iter().map(|n| n.title).collect();
        assert_eq!(titles, ["old", "new", "mid"]);
        let _ = new;
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
}
