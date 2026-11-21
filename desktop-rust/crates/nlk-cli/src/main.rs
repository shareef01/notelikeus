//! `nlk-rust`: drives the experimental core against its own encrypted store, in guest mode.
//!
//!     nlk-rust [--data-dir DIR] new "Title" "Body"
//!     nlk-rust [--data-dir DIR] list
//!     nlk-rust [--data-dir DIR] edit ID "New body"
//!     nlk-rust [--data-dir DIR] delete ID
//!     nlk-rust [--data-dir DIR] where
//!
//! The default data folder is `%APPDATA%\NotelikeusRustExperimental`, never the Kotlin client's
//! `~/.notelikeus` (which the store refuses). Guest notes never leave the device.

use std::path::PathBuf;
use std::process::ExitCode;
use std::time::{SystemTime, UNIX_EPOCH};

use notelikeus_core::crypto::KeyProtector;
use notelikeus_core::store::Store;
use notelikeus_core::sync::{self, IdSource};

const GUEST: &str = "__guest__";

struct SystemIds;

impl IdSource for SystemIds {
    fn now_millis(&mut self) -> i64 {
        SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
    }
    fn random_below_1000(&mut self) -> u32 {
        notelikeus_core::crypto::random_bytes::<4>().map(u32::from_le_bytes).unwrap_or(0) % 1000
    }
}

fn protector() -> Box<dyn KeyProtector> {
    #[cfg(windows)]
    {
        Box::new(notelikeus_core::crypto::DpapiKeyProtector)
    }
    #[cfg(not(windows))]
    {
        eprintln!("warning: no DPAPI on this platform; the data key is stored unwrapped");
        Box::new(notelikeus_core::crypto::PlaintextKeyProtectorForTests)
    }
}

fn default_dir() -> PathBuf {
    std::env::var_os("APPDATA")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."))
        .join("NotelikeusRustExperimental")
}

fn run(args: Vec<String>) -> Result<(), String> {
    let mut args = args.into_iter().peekable();
    let mut dir = default_dir();
    if args.peek().map(String::as_str) == Some("--data-dir") {
        args.next();
        dir = PathBuf::from(args.next().ok_or("--data-dir needs a folder")?);
    }
    let command = args.next().ok_or("usage: nlk-rust [--data-dir DIR] new|list|edit|delete|where")?;
    let protector = protector();
    let store = Store::open(&dir, GUEST, protector.as_ref()).map_err(|e| e.to_string())?;
    let mut state = store.load().map_err(|e| e.to_string())?;
    let mut ids = SystemIds;

    match command.as_str() {
        "where" => println!("{}", store.state_path().display()),
        "list" => {
            for note in state.notes.values() {
                let flag = if state.pending.contains_key(&note.id) { "*" } else { " " };
                println!("{flag} {}  {}  {}", note.id, note.title, note.content.replace('\n', " / "));
            }
        }
        "new" => {
            let title = args.next().unwrap_or_default();
            let body = args.next().unwrap_or_default();
            let id = sync::create_note(&mut state, &mut ids, |n| {
                n.title = title;
                n.content = body;
            });
            store.save(&state).map_err(|e| e.to_string())?;
            println!("{id}");
        }
        "edit" => {
            let id = args.next().ok_or("edit needs a note id")?;
            let body = args.next().unwrap_or_default();
            let now = ids.now_millis();
            sync::edit_note(&mut state, &id, now, |n| n.content = body).map_err(|e| e.to_string())?;
            store.save(&state).map_err(|e| e.to_string())?;
        }
        "delete" => {
            let id = args.next().ok_or("delete needs a note id")?;
            let now = ids.now_millis();
            sync::delete_note(&mut state, &id, now).map_err(|e| e.to_string())?;
            store.save(&state).map_err(|e| e.to_string())?;
        }
        other => return Err(format!("unknown command {other}")),
    }
    Ok(())
}

fn main() -> ExitCode {
    match run(std::env::args().skip(1).collect()) {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("nlk-rust: {message}");
            ExitCode::FAILURE
        }
    }
}
