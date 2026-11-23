//! The encrypted local store: one sealed file per account, written atomically.
//!
//! Deliberately its own format in its own folder. It never opens, migrates or writes the Kotlin
//! client's Room/SQLCipher database (`~/.notelikeus`), and [`Store::open`] refuses a data folder
//! inside it, so running this experiment cannot touch the data of the client that ships today.

use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::io::Write;
use std::path::{Component, Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

use serde::{Deserialize, Serialize};

use crate::Error;
use crate::crypto::{self, KEY_LEN, KeyProtector};
use crate::model::Note;

const FORMAT: &str = "nlkr-state-v1";
const STATE_FILE: &str = "state.nlkr";
const PREVIOUS_FILE: &str = "state.nlkr.prev";
const KEY_FILE: &str = "data-key.protected";
static SAVE_TEMP_SEQ: AtomicU64 = AtomicU64::new(0);

/// Why a note id is locally deleted. The web client once kept no origin, read a tombstone a pull had
/// merged as "deleted on this device", and dropped the unsynced edit under it (AUD-I01).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum TombstoneOrigin {
    /// The user deleted it here; the delete still has to reach the cloud unless `confirmed`.
    Local { confirmed: bool },
    /// Another device deleted it; the cloud told us.
    Cloud,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Tombstone {
    pub deleted_at: i64,
    pub origin: TombstoneOrigin,
    /// The revision the delete was made against, when known.
    pub base_revision: Option<i64>,
    /// Authored content digest when deleted locally before the server revision was known.
    #[serde(default)]
    pub content_digest: Option<String>,
}

/// An edit the cloud has not confirmed. `base_revision` is the revision it was made on and never
/// moves forward on its own: the server decides whether that is still current.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PendingPush {
    pub base_revision: Option<i64>,
    pub seq: u64,
}

/// Everything one account's local data consists of. Serialized whole and sealed.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct LocalState {
    pub owner_id: String,
    pub notes: BTreeMap<String, Note>,
    /// Note id → the last revision the cloud confirmed for it.
    pub revisions: BTreeMap<String, i64>,
    /// Note id → client timestamp of the version last known identical to the cloud's.
    pub synced_timestamps: BTreeMap<String, i64>,
    pub pending: BTreeMap<String, PendingPush>,
    pub tombstones: BTreeMap<String, Tombstone>,
    pub last_remote_revision: i64,
    pub wipe_generation: i64,
    pub push_seq: u64,
    /// Note ids known to be present in the cloud. Defaulted for older stores.
    #[serde(default)]
    pub known_cloud_ids: BTreeSet<String>,
    /// Set when the cloud wipe generation moved ahead of this device; blocks uploads until the user
    /// chooses to re-upload. Defaulted for older stores.
    #[serde(default)]
    pub uploads_blocked_after_wipe: bool,
    /// Labels created on this device, used or not, like the web app's label registry. The cloud only
    /// stores labels on notes, so this list is local; a label in use is known from its notes anyway.
    /// Defaulted, so state written before it existed still opens.
    #[serde(default)]
    pub label_registry: Vec<String>,
    /// Filters the user named, newest first. Device-local like the other clients' copies; defaulted.
    #[serde(default)]
    pub saved_filters: Vec<crate::query::SavedFilter>,
    /// Each note's images (note id → images). Defaulted.
    #[serde(default)]
    pub attachments: BTreeMap<String, Vec<crate::attachments::Attachment>>,
    /// Uploaded images removed here whose remote delete has not gone through yet (note id → ids): kept
    /// out of the next listing until it has. Defaulted.
    #[serde(default)]
    pub attachment_removals: BTreeMap<String, Vec<String>>,
}

#[derive(Serialize, Deserialize)]
struct Envelope {
    format: String,
    state: LocalState,
}

pub struct Store {
    dir: PathBuf,
    owner_id: String,
    key: [u8; KEY_LEN],
}

impl Drop for Store {
    fn drop(&mut self) {
        crypto::wipe(&mut self.key);
    }
}

fn valid_owner_id(owner: &str) -> bool {
    !owner.is_empty() && owner.len() <= 64 && owner.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

/// The Kotlin desktop client's data folder. Never ours.
pub fn kotlin_client_dir() -> Option<PathBuf> {
    std::env::var_os("USERPROFILE")
        .or_else(|| std::env::var_os("HOME"))
        .map(|home| PathBuf::from(home).join(".notelikeus"))
}

fn strip_verbatim(path: PathBuf) -> PathBuf {
    let raw = path.to_string_lossy();
    if let Some(rest) = raw.strip_prefix(r"\\?\UNC\") {
        PathBuf::from(format!(r"\\{rest}"))
    } else if let Some(rest) = raw.strip_prefix(r"\\?\") {
        PathBuf::from(rest)
    } else {
        path
    }
}

fn normalize_path(path: &Path) -> PathBuf {
    let abs = if path.is_absolute() {
        path.to_path_buf()
    } else if let Ok(cwd) = std::env::current_dir() {
        cwd.join(path)
    } else {
        path.to_path_buf()
    };

    // Canonicalize the deepest existing ancestor so symlinks/junctions are resolved too.
    let mut existing = abs.as_path();
    let mut tail = Vec::new();
    while !existing.exists() {
        let Some(name) = existing.file_name() else { break };
        tail.push(name.to_os_string());
        let Some(parent) = existing.parent() else { break };
        existing = parent;
    }
    let base = if existing.exists() {
        fs::canonicalize(existing).map(strip_verbatim).unwrap_or_else(|_| strip_verbatim(existing.to_path_buf()))
    } else {
        strip_verbatim(abs)
    };
    let mut combined = base;
    for part in tail.into_iter().rev() {
        combined.push(part);
    }

    let mut clean = PathBuf::new();
    for component in combined.components() {
        match component {
            Component::CurDir => {}
            Component::ParentDir => {
                if !matches!(clean.components().next_back(), Some(Component::Prefix(_) | Component::RootDir) | None) {
                    clean.pop();
                }
            }
            other => clean.push(other.as_os_str()),
        }
    }
    clean
}

fn is_inside(path: &Path, parent: &Path) -> bool {
    let norm = |p: &Path| {
        let s = normalize_path(p).to_string_lossy().replace('/', "\\").trim_end_matches('\\').to_string();
        if cfg!(windows) { s.to_lowercase() } else { s }
    };
    let (p, q) = (norm(path), norm(parent));
    p == q || p.starts_with(&(q + "\\"))
}

fn now_epoch_ms() -> u128 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0)
}

impl Store {
    /// Opens (or creates) the store for `owner_id` under `root`.
    ///
    /// The data key lives at `root/data-key.protected`, wrapped by `protector`. A missing key next to
    /// existing account data is refused rather than replaced: a new key would make that data
    /// unreadable, and the user should learn that instead of seeing an empty library.
    pub fn open(root: &Path, owner_id: &str, protector: &dyn KeyProtector) -> Result<Store, Error> {
        if !valid_owner_id(owner_id) {
            return Err(Error::InvalidInput("owner id must be 1-64 characters of A-Z, a-z, 0-9, - or _".into()));
        }
        let key = open_data_key(root, protector)?;
        let dir = root.join("accounts").join(owner_id);
        fs::create_dir_all(&dir)?;
        Ok(Store { dir, owner_id: owner_id.to_string(), key })
    }

    fn aad(&self) -> Vec<u8> {
        format!("{FORMAT}|{}", self.owner_id).into_bytes()
    }

    fn read_file(&self, name: &str) -> Result<Option<LocalState>, Error> {
        let path = self.dir.join(name);
        if !path.exists() {
            return Ok(None);
        }
        let mut plain = crypto::open(&self.key, &self.aad(), &fs::read(path)?)?;
        let parsed: Result<Envelope, _> = serde_json::from_slice(&plain);
        crypto::wipe(&mut plain);
        let envelope = parsed?;
        if envelope.format != FORMAT || envelope.state.owner_id != self.owner_id {
            return Err(Error::Corrupt("state file belongs to another format or account".into()));
        }
        Ok(Some(envelope.state))
    }

    /// The account's state. Falls back to the previous version when the current file is missing or
    /// damaged (a crash between the two renames in [`Store::save`]), quarantining a damaged current
    /// file so the next save cannot overwrite the good backup; never falls back to an empty library
    /// while a file exists that could not be read.
    pub fn load(&self) -> Result<LocalState, Error> {
        match self.read_file(STATE_FILE) {
            Ok(Some(state)) => Ok(state),
            Ok(None) => match self.read_file(PREVIOUS_FILE)? {
                Some(state) => Ok(state),
                None => Ok(LocalState { owner_id: self.owner_id.clone(), ..LocalState::default() }),
            },
            Err(current_error) => match self.read_file(PREVIOUS_FILE) {
                Ok(Some(state)) => {
                    let corrupt_name = format!("{STATE_FILE}.corrupt-{}", now_epoch_ms());
                    let _ = fs::rename(self.dir.join(STATE_FILE), self.dir.join(corrupt_name));
                    Ok(state)
                }
                _ => Err(current_error),
            },
        }
    }

    /// Seals and writes `state`: temp file, flush to disk, safely rotate the old file to `.prev`,
    /// then rename into place.
    pub fn save(&self, state: &LocalState) -> Result<(), Error> {
        if state.owner_id != self.owner_id {
            return Err(Error::InvalidInput("state belongs to another account".into()));
        }
        let envelope = Envelope { format: FORMAT.into(), state: state.clone() };
        let mut plain = serde_json::to_vec(&envelope)?;
        let sealed_result = crypto::seal(&self.key, &self.aad(), &plain);
        crypto::wipe(&mut plain);
        let sealed = sealed_result?;

        let current = self.dir.join(STATE_FILE);
        let prev = self.dir.join(PREVIOUS_FILE);
        let seq = SAVE_TEMP_SEQ.fetch_add(1, Ordering::Relaxed);
        let temp = self.dir.join(format!("{STATE_FILE}.{}.{seq}.tmp", std::process::id()));
        write_temp(&temp, &sealed)?;

        if current.exists() {
            let prev_temp = self.dir.join(format!("{PREVIOUS_FILE}.{}.{seq}.tmp", std::process::id()));
            if let Err(err) = fs::copy(&current, &prev_temp) {
                let _ = fs::remove_file(&prev_temp);
                let _ = fs::remove_file(&temp);
                return Err(err.into());
            }
            if let Err(err) = fs::rename(&prev_temp, &prev) {
                let _ = fs::remove_file(&prev_temp);
                let _ = fs::remove_file(&temp);
                return Err(err.into());
            }
        }
        if let Err(err) = fs::rename(&temp, &current) {
            let _ = fs::remove_file(&temp);
            return Err(err.into());
        }
        Ok(())
    }

    /// The account's sealed image files.
    pub fn blobs(&self) -> crate::attachments::Blobs {
        crate::attachments::Blobs::new(self.dir.join("attachments"), &self.owner_id, self.key)
    }

    pub fn state_path(&self) -> PathBuf {
        self.dir.join(STATE_FILE)
    }

    /// Removes this account's sealed notes and image blobs after the server accepted account deletion.
    /// Guest data is never removed by this operation.
    pub fn remove_account_data(&self) -> Result<(), Error> {
        if self.owner_id == "__guest__" {
            return Err(Error::InvalidInput("refusing to remove guest data as an account".into()));
        }
        match fs::remove_dir_all(&self.dir) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(error.into()),
        }
    }
}

/// The install's data key, created on first use. Refuses the Kotlin client's folder, and refuses to
/// replace a missing key while account data or a saved session exists.
fn open_data_key(root: &Path, protector: &dyn KeyProtector) -> Result<[u8; KEY_LEN], Error> {
    if let Some(kotlin) = kotlin_client_dir()
        && is_inside(root, &kotlin)
    {
        return Err(Error::InvalidInput(format!(
            "refusing to use {}: it belongs to the Kotlin desktop client",
            root.display()
        )));
    }
    fs::create_dir_all(root)?;
    let key_path = root.join(KEY_FILE);
    if key_path.exists() {
        return protector.unprotect(&fs::read(&key_path)?);
    }
    if has_account_data(root)? || root.join(SESSION_FILE).exists() {
        return Err(Error::KeyMissing(root.display().to_string()));
    }
    let mut key = crypto::random_bytes::<KEY_LEN>()?;
    let protected = protector.protect(&key);
    let protected = match protected {
        Ok(p) => p,
        Err(err) => {
            crypto::wipe(&mut key);
            return Err(err);
        }
    };
    if let Err(err) = write_atomically(root, &key_path, &protected) {
        crypto::wipe(&mut key);
        return Err(err);
    }
    Ok(key)
}

const SESSION_FILE: &str = "session.nlkr";
const SESSION_FORMAT: &str = "nlkr-session-v1";

/// A signed-in Supabase session. Tokens never leave the sealed file or process memory, and are not
/// printed: `Debug` is written by hand to leave them out.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Session {
    pub access_token: String,
    pub refresh_token: Option<String>,
    pub user_id: String,
    pub email: Option<String>,
    /// Epoch millis.
    pub expires_at_ms: i64,
}

impl Drop for Session {
    fn drop(&mut self) {
        crypto::wipe_string(&mut self.access_token);
        if let Some(refresh) = &mut self.refresh_token {
            crypto::wipe_string(refresh);
        }
    }
}

impl std::fmt::Debug for Session {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Session")
            .field("user_id", &self.user_id)
            .field("expires_at_ms", &self.expires_at_ms)
            .finish_non_exhaustive()
    }
}

impl Session {
    /// Refresh a minute early, so a request never leaves with a token about to lapse.
    pub fn needs_refresh(&self, now_ms: i64) -> bool {
        self.expires_at_ms.saturating_sub(60_000) <= now_ms
    }
}

/// The saved session: one signed-in account per install, sealed with the data key and bound to its
/// purpose by the associated data, so a state file cannot be read as a session or the reverse.
pub struct SessionVault {
    root: PathBuf,
    key: [u8; KEY_LEN],
}

impl Drop for SessionVault {
    fn drop(&mut self) {
        crypto::wipe(&mut self.key);
    }
}

impl SessionVault {
    pub fn open(root: &Path, protector: &dyn KeyProtector) -> Result<SessionVault, Error> {
        Ok(SessionVault { root: root.to_path_buf(), key: open_data_key(root, protector)? })
    }

    pub fn load(&self) -> Result<Option<Session>, Error> {
        let path = self.root.join(SESSION_FILE);
        if !path.exists() {
            return Ok(None);
        }
        let mut plain = crypto::open(&self.key, SESSION_FORMAT.as_bytes(), &fs::read(path)?)?;
        let parsed: Result<Session, _> = serde_json::from_slice(&plain);
        crypto::wipe(&mut plain);
        let session = parsed?;
        if !valid_owner_id(&session.user_id) {
            return Err(Error::Corrupt("saved session names an invalid account".into()));
        }
        Ok(Some(session))
    }

    pub fn save(&self, session: &Session) -> Result<(), Error> {
        if !valid_owner_id(&session.user_id) {
            return Err(Error::InvalidInput("session account id is not a valid owner id".into()));
        }
        let mut plain = serde_json::to_vec(session)?;
        let sealed_result = crypto::seal(&self.key, SESSION_FORMAT.as_bytes(), &plain);
        crypto::wipe(&mut plain);
        let sealed = sealed_result?;
        write_atomically(&self.root, &self.root.join(SESSION_FILE), &sealed)
    }

    /// Signing out forgets the session. The account's notes stay sealed on disk for its next sign-in.
    pub fn clear(&self) -> Result<(), Error> {
        match fs::remove_file(self.root.join(SESSION_FILE)) {
            Ok(()) => Ok(()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(e.into()),
        }
    }
}

fn dir_has_nla(dir: &Path) -> Result<bool, Error> {
    if !dir.is_dir() {
        return Ok(false);
    }
    for entry in fs::read_dir(dir)? {
        let path = entry?.path();
        if path.extension().and_then(|e| e.to_str()) == Some("nla") {
            return Ok(true);
        }
    }
    Ok(false)
}

fn has_account_data(root: &Path) -> Result<bool, Error> {
    let accounts = root.join("accounts");
    if !accounts.exists() {
        return Ok(false);
    }
    for entry in fs::read_dir(accounts)? {
        let dir = entry?.path();
        if dir.join(STATE_FILE).exists()
            || dir.join(PREVIOUS_FILE).exists()
            || dir_has_nla(&dir.join("attachments"))?
            || dir_has_nla(&dir.join("attachments").join("cache"))?
        {
            return Ok(true);
        }
    }
    Ok(false)
}

fn write_temp(temp: &Path, bytes: &[u8]) -> Result<(), Error> {
    let write_res = (|| -> std::io::Result<()> {
        let mut file = fs::OpenOptions::new().write(true).create_new(true).open(temp)?;
        file.write_all(bytes)?;
        file.flush()?;
        Ok(())
    })();
    if let Err(err) = write_res {
        let _ = fs::remove_file(temp);
        return Err(err.into());
    }
    Ok(())
}

fn write_atomically(dir: &Path, target: &Path, bytes: &[u8]) -> Result<(), Error> {
    let seq = SAVE_TEMP_SEQ.fetch_add(1, Ordering::Relaxed);
    let base = target.file_name().and_then(|n| n.to_str()).unwrap_or("file");
    let temp = dir.join(format!("{base}.{}.{seq}.tmp", std::process::id()));
    write_temp(&temp, bytes)?;
    if let Err(err) = fs::rename(&temp, target) {
        let _ = fs::remove_file(&temp);
        return Err(err.into());
    }
    Ok(())
}
