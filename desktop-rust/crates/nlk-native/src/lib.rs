//! The native Notelikeus app for Windows: a Slint window (`ui/app.slint`) over the Rust client's app
//! logic (`notelikeus-app`). Its own logic and data: it never opens the Kotlin app's data, and its
//! notes live in `%APPDATA%\NotelikeusRust`.

slint::include_modules!();

pub mod controller;
pub mod view;

use std::rc::Rc;

use controller::Controller;
use notelikeus_app::{NotesService, SyncConfig, SystemIds};

/// The folder under `%APPDATA%` this app keeps its notes in.
pub const DATA_FOLDER: &str = "NotelikeusRust";

fn protector() -> Box<dyn notelikeus_core::crypto::KeyProtector + Send + Sync> {
    #[cfg(windows)]
    {
        Box::new(notelikeus_core::crypto::DpapiKeyProtector)
    }
    #[cfg(not(windows))]
    {
        Box::new(notelikeus_core::crypto::PlaintextKeyProtectorForTests)
    }
}

/// Opens the notes and the window. Errors are for the user: a store that cannot be read is never shown
/// as an empty library.
pub fn open() -> Result<(AppWindow, Rc<Controller>), String> {
    let dir = notelikeus_app::data_dir_named(DATA_FOLDER);
    let service = NotesService::open(&dir, protector(), Box::new(SystemIds), SyncConfig::from_env())
        .map_err(|e| format!("Notelikeus could not open its notes in {}.\n\n{e}", dir.display()))?;
    let ui = AppWindow::new().map_err(|e| format!("Notelikeus could not open its window: {e}"))?;
    let controller = Controller::attach(&ui, service);
    Ok((ui, controller))
}

/// A release build has no console, so a startup failure needs a dialog to be seen at all.
pub fn show_fatal_error(text: &str) {
    eprintln!("{text}");
    #[cfg(windows)]
    {
        use windows_sys::Win32::UI::WindowsAndMessaging::{MB_ICONERROR, MB_OK, MessageBoxW};
        let wide = |s: &str| s.encode_utf16().chain(std::iter::once(0)).collect::<Vec<u16>>();
        let (body, caption) = (wide(text), wide("Notelikeus"));
        // SAFETY: both buffers are NUL-terminated UTF-16 and outlive the call.
        unsafe { MessageBoxW(std::ptr::null_mut(), body.as_ptr(), caption.as_ptr(), MB_OK | MB_ICONERROR) };
    }
}
