//! The encrypted store on a real filesystem, with the real DPAPI on Windows.

use std::fs;
use std::path::PathBuf;

use notelikeus_core::Error;
use notelikeus_core::crypto::{KeyProtector, PlaintextKeyProtectorForTests};
use notelikeus_core::model::Note;
use notelikeus_core::store::{LocalState, Store};

fn temp_root(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("nlkr-test-{name}-{}", std::process::id()));
    let _ = fs::remove_dir_all(&dir);
    dir
}

fn protector() -> Box<dyn KeyProtector> {
    #[cfg(windows)]
    {
        Box::new(notelikeus_core::crypto::DpapiKeyProtector)
    }
    #[cfg(not(windows))]
    {
        Box::new(PlaintextKeyProtectorForTests)
    }
}

fn state_with(owner: &str, words: &str) -> LocalState {
    let mut note = Note::new(1, 100);
    note.content = words.into();
    let mut state = LocalState { owner_id: owner.into(), ..Default::default() };
    state.notes.insert(note.id.clone(), note);
    state
}

#[test]
fn notes_round_trip_and_are_not_on_disk_in_plain_text() {
    let root = temp_root("roundtrip");
    let protector = protector();
    let store = Store::open(&root, "owner-a", protector.as_ref()).unwrap();
    store.save(&state_with("owner-a", "secret words 12345")).unwrap();

    let reopened = Store::open(&root, "owner-a", protector.as_ref()).unwrap();
    assert_eq!(reopened.load().unwrap().notes["1"].content, "secret words 12345");
    let raw = fs::read(store.state_path()).unwrap();
    assert!(!raw.windows(12).any(|w| w == b"secret words"), "plaintext leaked to disk");
    let key = fs::read(root.join("data-key.protected")).unwrap();
    #[cfg(windows)]
    assert!(key.len() > 32, "the key is wrapped by DPAPI, not stored raw");
    let _ = key;
    fs::remove_dir_all(root).unwrap();
}

#[test]
fn one_accounts_file_does_not_open_as_another_account() {
    let root = temp_root("owner-binding");
    let p = PlaintextKeyProtectorForTests;
    let a = Store::open(&root, "owner-a", &p).unwrap();
    let b = Store::open(&root, "owner-b", &p).unwrap();
    a.save(&state_with("owner-a", "a's words")).unwrap();
    fs::copy(a.state_path(), b.state_path()).unwrap();
    assert!(matches!(b.load(), Err(Error::Crypto(_))));
    assert!(matches!(b.save(&state_with("owner-a", "x")), Err(Error::InvalidInput(_))));
    fs::remove_dir_all(root).unwrap();
}

#[test]
fn a_damaged_current_file_falls_back_to_the_previous_version() {
    let root = temp_root("fallback");
    let p = PlaintextKeyProtectorForTests;
    let store = Store::open(&root, "owner", &p).unwrap();
    store.save(&state_with("owner", "first")).unwrap();
    store.save(&state_with("owner", "second")).unwrap();
    // A torn write: the current file is garbage, the previous one is intact.
    fs::write(store.state_path(), b"torn").unwrap();
    assert_eq!(store.load().unwrap().notes["1"].content, "first");
    // Both damaged: an error, never an empty library.
    fs::write(store.state_path().with_extension("nlkr.prev"), b"torn").unwrap();
    assert!(store.load().is_err());
    fs::remove_dir_all(root).unwrap();
}

#[test]
fn a_missing_key_next_to_existing_data_is_refused_not_replaced() {
    let root = temp_root("key-loss");
    let p = PlaintextKeyProtectorForTests;
    Store::open(&root, "owner", &p).unwrap().save(&state_with("owner", "keep")).unwrap();
    fs::remove_file(root.join("data-key.protected")).unwrap();
    assert!(matches!(Store::open(&root, "owner", &p), Err(Error::KeyMissing(_))));
    assert!(!root.join("data-key.protected").exists(), "no replacement key was written");
    fs::remove_dir_all(root).unwrap();
}

#[test]
fn the_kotlin_clients_folder_is_refused() {
    let Some(kotlin) = notelikeus_core::store::kotlin_client_dir() else { return };
    let p = PlaintextKeyProtectorForTests;
    for dir in [kotlin.clone(), kotlin.join("rust")] {
        assert!(matches!(Store::open(&dir, "owner", &p), Err(Error::InvalidInput(_))), "{}", dir.display());
    }
}

#[test]
fn owner_ids_cannot_escape_the_accounts_folder() {
    let root = temp_root("owner-id");
    let p = PlaintextKeyProtectorForTests;
    for bad in ["", "..", "../other", "a/b", "a\\b", "c:", &"x".repeat(65)] {
        assert!(matches!(Store::open(&root, bad, &p), Err(Error::InvalidInput(_))), "{bad:?}");
    }
    assert!(Store::open(&root, "__guest__", &p).is_ok());
    assert!(Store::open(&root, "9c1f7a3e-2b44-4d1a-9f6e-0c8b2d4e6a10", &p).is_ok());
    fs::remove_dir_all(root).unwrap();
}

#[cfg(windows)]
#[test]
fn dpapi_unwraps_what_it_wrapped_and_rejects_tampering() {
    let p = notelikeus_core::crypto::DpapiKeyProtector;
    let key = [7u8; 32];
    let mut blob = p.protect(&key).unwrap();
    assert_eq!(p.unprotect(&blob).unwrap(), key);
    let last = blob.len() - 1;
    blob[last] ^= 0xff;
    assert!(p.unprotect(&blob).is_err());
}

#[test]
fn a_session_round_trips_sealed_and_cannot_be_read_as_account_data() {
    use notelikeus_core::store::{Session, SessionVault};
    let root = temp_root("session");
    let p = protector();
    let vault = SessionVault::open(&root, p.as_ref()).unwrap();
    assert_eq!(vault.load().unwrap(), None);
    let session = Session {
        access_token: "access-token-abcdef".into(),
        refresh_token: Some("refresh-token-123456".into()),
        user_id: "9c1f7a3e-2b44-4d1a-9f6e-0c8b2d4e6a10".into(),
        email: Some("someone@example.test".into()),
        expires_at_ms: 1_000,
    };
    vault.save(&session).unwrap();
    assert_eq!(SessionVault::open(&root, p.as_ref()).unwrap().load().unwrap(), Some(session.clone()));
    let raw = fs::read(root.join("session.nlkr")).unwrap();
    for secret in ["access-token", "refresh-token", "someone@example"] {
        assert!(!raw.windows(secret.len()).any(|w| w == secret.as_bytes()), "{secret} on disk in plaintext");
    }
    assert!(!format!("{session:?}").contains("token"), "Debug must not print tokens");
    // The same bytes placed where an account's state lives are refused, not parsed.
    let store = Store::open(&root, "owner", p.as_ref()).unwrap();
    fs::copy(root.join("session.nlkr"), store.state_path()).unwrap();
    assert!(store.load().is_err());
    vault.clear().unwrap();
    vault.clear().unwrap();
    assert_eq!(vault.load().unwrap(), None);
    fs::remove_dir_all(root).unwrap();
}

#[test]
fn a_missing_key_next_to_a_saved_session_is_refused() {
    use notelikeus_core::store::{Session, SessionVault};
    let root = temp_root("session-key-loss");
    let p = PlaintextKeyProtectorForTests;
    let session = Session {
        access_token: "a".into(),
        refresh_token: None,
        user_id: "user-1".into(),
        email: None,
        expires_at_ms: 0,
    };
    SessionVault::open(&root, &p).unwrap().save(&session).unwrap();
    fs::remove_file(root.join("data-key.protected")).unwrap();
    assert!(matches!(SessionVault::open(&root, &p), Err(Error::KeyMissing(_))));
    fs::remove_dir_all(root).unwrap();
}
