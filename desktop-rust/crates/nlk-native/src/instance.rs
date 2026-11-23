//! Single-instance lock and activation hand-off for the desktop app.
//!
//! Only one window opens each data folder at a time: an advisory lock on `instance.lock` claims
//! primary ownership for its lifetime, while a second launch writes `activate.request` so the
//! running instance brings itself to the foreground before the second launch exits.

use std::fs::{self, File, OpenOptions, TryLockError};
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

const LOCK_FILE: &str = "instance.lock";
const ACTIVATE_FILE: &str = "activate.request";

pub struct InstanceGuard {
    _file: File,
    activate_path: PathBuf,
}

pub enum AcquireOutcome {
    Primary(InstanceGuard),
    AlreadyRunning,
}

impl InstanceGuard {
    /// Returns true when another launch dropped an activation signal for this running instance,
    /// consuming the signal file in the process.
    pub fn consume_activation_request(&self) -> bool {
        if self.activate_path.exists() {
            let _ = fs::remove_file(&self.activate_path);
            true
        } else {
            false
        }
    }
}

impl Drop for InstanceGuard {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.activate_path);
    }
}

pub fn acquire(root: &Path) -> Result<AcquireOutcome, String> {
    fs::create_dir_all(root).map_err(|e| format!("Could not prepare instance folder: {e}"))?;
    let lock_path = root.join(LOCK_FILE);
    let activate_path = root.join(ACTIVATE_FILE);
    let file = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .open(&lock_path)
        .map_err(|e| format!("Could not open instance lock: {e}"))?;

    match file.try_lock() {
        Ok(()) => {
            let _ = fs::remove_file(&activate_path);
            Ok(AcquireOutcome::Primary(InstanceGuard { _file: file, activate_path }))
        }
        Err(TryLockError::WouldBlock) => {
            let stamp = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0).to_string();
            let _ = fs::write(&activate_path, stamp.as_bytes());
            Ok(AcquireOutcome::AlreadyRunning)
        }
        Err(TryLockError::Error(e)) => Err(format!("Could not lock instance file: {e}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn second_instance_yields_and_signals_activation() {
        let dir = std::env::temp_dir().join(format!("nlk-instance-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let first = match acquire(&dir).unwrap() {
            AcquireOutcome::Primary(guard) => guard,
            AcquireOutcome::AlreadyRunning => panic!("first instance must be primary"),
        };
        assert!(!first.consume_activation_request());
        assert!(matches!(acquire(&dir).unwrap(), AcquireOutcome::AlreadyRunning));
        assert!(first.consume_activation_request());
        assert!(!first.consume_activation_request(), "activation signal is consumed once");
        drop(first);
        assert!(matches!(acquire(&dir).unwrap(), AcquireOutcome::Primary(_)));
        let _ = fs::remove_dir_all(&dir);
    }
}
