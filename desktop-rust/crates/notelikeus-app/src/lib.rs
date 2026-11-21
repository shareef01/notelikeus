//! App logic shared by the Rust client's front ends (the native Slint app and the Tauri experiment):
//! which account's notes are open, saving every change, sign-in, and sync. No UI code here.

pub mod service;

pub use service::{
    AccountView, AppInfo, GUEST, MAX_CONTENT_CHARS, MAX_TITLE_CHARS, NoteView, NotesService, SyncConfig, SyncSummary,
    SystemIds, data_dir_named, default_data_dir,
};
