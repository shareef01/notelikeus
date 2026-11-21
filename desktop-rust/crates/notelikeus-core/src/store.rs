//! The encrypted local store: one sealed file per account, written atomically.
//!
//! Deliberately its own format in its own folder. It never opens, migrates or writes the Kotlin
//! client's Room/SQLCipher database (`~/.notelikeus`), and [`Store::open`] refuses a data folder
//! inside it, so running this experiment cannot touch the data of the client that ships today.

use std::collections::BTreeMap;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::Error;
use crate::crypto::{self, KEY_LEN, KeyProtector};
use crate::model::Note;

const FORMAT: &str = "nlkr-state-v1";
const STATE_FILE: &str = "state.nlkr";
const PREVIOUS_FILE: &str = "state.nlkr.prev";
const TEMP_FILE: &str = "state.nlkr.tmp";
const KEY_FILE: &str = "data-key.protected";

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

fn valid_owner_id(owner: &str) -> bool {
    !owner.is_empty() && owner.len() <= 64 && owner.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

/// The Kotlin desktop client's data folder. Never ours.
pub fn kotlin_client_dir() -> Option<PathBuf> {
    std::env::var_os("USERPROFILE")
        .or_else(|| std::env::var_os("HOME"))
        .map(|home| PathBuf::from(home).join(".notelikeus"))
}

fn is_inside(path: &Path, parent: &Path) -> bool {
    let norm = |p: &Path| p.to_string_lossy().replace('/', "\\").trim_end_matches('\\').to_lowercase();
    let (p, q) = (norm(path), norm(parent));
    p == q || p.starts_with(&(q + "\\"))
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
        let key = if key_path.exists() {
            protector.unprotect(&fs::read(&key_path)?)?
        } else {
            if has_account_data(root)? {
                return Err(Error::KeyMissing(root.display().to_string()));
            }
            let key = crypto::random_bytes::<KEY_LEN>()?;
            write_atomically(root, &key_path, &protector.protect(&key)?)?;
            key
        };
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
        let plain = crypto::open(&self.key, &self.aad(), &fs::read(path)?)?;
        let envelope: Envelope = serde_json::from_slice(&plain)?;
        if envelope.format != FORMAT || envelope.state.owner_id != self.owner_id {
            return Err(Error::Corrupt("state file belongs to another format or account".into()));
        }
        Ok(Some(envelope.state))
    }

    /// The account's state. Falls back to the previous version when the current file is missing or
    /// damaged (a crash between the two renames in [`Store::save`]); never to an empty library while
    /// a file exists that could not be read.
    pub fn load(&self) -> Result<LocalState, Error> {
        match self.read_file(STATE_FILE) {
            Ok(Some(state)) => Ok(state),
            Ok(None) => match self.read_file(PREVIOUS_FILE)? {
                Some(state) => Ok(state),
                None => Ok(LocalState { owner_id: self.owner_id.clone(), ..LocalState::default() }),
            },
            Err(current_error) => match self.read_file(PREVIOUS_FILE) {
                Ok(Some(state)) => Ok(state),
                _ => Err(current_error),
            },
        }
    }

    /// Seals and writes `state`: temp file, flush to disk, keep the old file as `.prev`, rename.
    pub fn save(&self, state: &LocalState) -> Result<(), Error> {
        if state.owner_id != self.owner_id {
            return Err(Error::InvalidInput("state belongs to another account".into()));
        }
        let envelope = Envelope { format: FORMAT.into(), state: state.clone() };
        let sealed = crypto::seal(&self.key, &self.aad(), &serde_json::to_vec(&envelope)?)?;
        let current = self.dir.join(STATE_FILE);
        let temp = self.dir.join(TEMP_FILE);
        {
            let mut file = fs::File::create(&temp)?;
            file.write_all(&sealed)?;
            file.sync_all()?;
        }
        if current.exists() {
            fs::copy(&current, self.dir.join(PREVIOUS_FILE))?;
        }
        fs::rename(&temp, &current)?;
        Ok(())
    }

    pub fn state_path(&self) -> PathBuf {
        self.dir.join(STATE_FILE)
    }
}

fn has_account_data(root: &Path) -> Result<bool, Error> {
    let accounts = root.join("accounts");
    if !accounts.exists() {
        return Ok(false);
    }
    for entry in fs::read_dir(accounts)? {
        let dir = entry?.path();
        if dir.join(STATE_FILE).exists() || dir.join(PREVIOUS_FILE).exists() {
            return Ok(true);
        }
    }
    Ok(false)
}

fn write_atomically(dir: &Path, target: &Path, bytes: &[u8]) -> Result<(), Error> {
    let temp = dir.join(format!("{}.tmp", target.file_name().and_then(|n| n.to_str()).unwrap_or("file")));
    {
        let mut file = fs::File::create(&temp)?;
        file.write_all(bytes)?;
        file.sync_all()?;
    }
    fs::rename(temp, target)?;
    Ok(())
}
