//! App logic shared by the Rust client's front ends (the native Slint app and the Tauri experiment):
//! which account's notes are open, saving every change, sign-in, and sync. No UI code here.

pub mod clock;
pub mod service;

pub use notelikeus_core::model::ChecklistItem;
pub use notelikeus_core::query;
pub use service::{
    AccountView, AppInfo, BackupAttachmentSource, BackupImportReport, ConflictReview, DeleteAccountReport, GUEST,
    ImageSource, ImageView, MAX_CHECKLIST_ITEMS, MAX_CONTENT_CHARS, MAX_LABEL_CHARS, MAX_LABELS_PER_NOTE,
    MAX_TITLE_CHARS, NoteView, NotesService, Scope, ScopeCounts, SearchView, SyncConfig, SyncSummary, SystemIds,
    data_dir_named, default_data_dir, label_key,
};
