//! Experimental Rust core for a Notelikeus Windows client.
//!
//! - [`model`]: the note and its Supabase wire shapes, pinned by `contracts/cloud/`.
//! - [`conflict`]: the inbound-resolution rules, pinned by `contracts/sync/inbound-resolution.json`.
//! - [`crypto`] and [`store`]: AES-256-GCM at rest, the key wrapped by DPAPI, one sealed file per account.
//! - [`sync`]: offline queue, revision-checked push, pull, conflicted copies, tombstones.
//! - `http` (feature `http`): the Supabase RPC transport.
//!
//! See `desktop-rust/README.md` for what is and is not done.

pub mod attachments;
pub mod conflict;
pub mod crypto;
pub mod model;
pub mod palette;
pub mod query;
pub mod store;
pub mod sync;

#[cfg(feature = "http")]
pub mod auth;
#[cfg(feature = "http")]
pub mod captcha;
#[cfg(feature = "http")]
pub mod http;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
    #[error("serialization error: {0}")]
    Json(#[from] serde_json::Error),
    #[error("encryption error: {0}")]
    Crypto(String),
    #[error("invalid input: {0}")]
    InvalidInput(String),
    #[error("local data is damaged: {0}")]
    Corrupt(String),
    #[error(
        "the data key is missing from {0} but account data is still there; refusing to create a new key, which would make that data unreadable"
    )]
    KeyMissing(String),
}
