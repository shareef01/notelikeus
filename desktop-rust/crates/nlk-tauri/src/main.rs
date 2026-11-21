//! Tauri 2 shell for the experimental Rust core. Guest mode only: notes live in the encrypted store
//! under `%APPDATA%\NotelikeusRustExperimental` and never leave the device. The Kotlin app's data is
//! never opened (the store refuses `~/.notelikeus`).

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod service;

use std::sync::Mutex;

use service::{AppInfo, NoteView, NotesService, SystemIds};
use tauri::State;

struct App(Mutex<NotesService>);

fn with<T>(app: &State<App>, f: impl FnOnce(&mut NotesService) -> Result<T, String>) -> Result<T, String> {
    let mut service = app.0.lock().map_err(|_| "The notes store is unavailable; restart the app.".to_string())?;
    f(&mut service)
}

#[tauri::command]
fn list_notes(app: State<App>) -> Result<Vec<NoteView>, String> {
    with(&app, |s| Ok(s.list()))
}

#[tauri::command]
fn create_note(app: State<App>, title: String, content: String) -> Result<NoteView, String> {
    with(&app, |s| s.create(&title, &content))
}

#[tauri::command]
fn update_note(app: State<App>, id: String, title: String, content: String) -> Result<NoteView, String> {
    with(&app, |s| s.update(&id, &title, &content))
}

#[tauri::command]
fn set_pinned(app: State<App>, id: String, pinned: bool) -> Result<NoteView, String> {
    with(&app, |s| s.set_pinned(&id, pinned))
}

#[tauri::command]
fn delete_note(app: State<App>, id: String) -> Result<NoteView, String> {
    with(&app, |s| s.delete(&id))
}

#[tauri::command]
fn app_info(app: State<App>) -> Result<AppInfo, String> {
    with(&app, |s| Ok(s.info()))
}

fn protector() -> Box<dyn notelikeus_core::crypto::KeyProtector> {
    #[cfg(windows)]
    {
        Box::new(notelikeus_core::crypto::DpapiKeyProtector)
    }
    #[cfg(not(windows))]
    {
        Box::new(notelikeus_core::crypto::PlaintextKeyProtectorForTests)
    }
}

/// A release build has no console, so a startup failure needs a dialog to be seen at all.
fn show_fatal_error(text: &str) {
    #[cfg(windows)]
    {
        use windows_sys::Win32::UI::WindowsAndMessaging::{MB_ICONERROR, MB_OK, MessageBoxW};
        let wide = |s: &str| s.encode_utf16().chain(std::iter::once(0)).collect::<Vec<u16>>();
        let (body, caption) = (wide(text), wide("Notelikeus (Rust experiment)"));
        // SAFETY: both buffers are NUL-terminated UTF-16 and outlive the call.
        unsafe { MessageBoxW(std::ptr::null_mut(), body.as_ptr(), caption.as_ptr(), MB_OK | MB_ICONERROR) };
    }
    #[cfg(not(windows))]
    let _ = text;
}

fn main() {
    let dir = service::default_data_dir();
    let service = match NotesService::open(&dir, protector().as_ref(), Box::new(SystemIds)) {
        Ok(service) => service,
        Err(message) => {
            // Never show an empty library over data that could not be read: say why, and stop.
            let text =
                format!("Notelikeus (Rust experiment) could not open its notes in {}.\n\n{message}", dir.display());
            eprintln!("{text}");
            show_fatal_error(&text);
            std::process::exit(1);
        }
    };
    tauri::Builder::default()
        .manage(App(Mutex::new(service)))
        .invoke_handler(tauri::generate_handler![
            list_notes,
            create_note,
            update_note,
            set_pinned,
            delete_note,
            app_info
        ])
        .setup(|app| {
            let builder = tauri::WebviewWindowBuilder::new(app, "main", tauri::WebviewUrl::default())
                .title("Notelikeus (Rust experiment)")
                .inner_size(1100.0, 720.0)
                .min_inner_size(480.0, 400.0);
            // Debug builds only: NLK_RUST_DEVTOOLS_PORT opens WebView2's DevTools protocol on loopback so
            // the UI can be driven end to end in tests. A release build ignores it.
            #[cfg(debug_assertions)]
            let builder = match std::env::var("NLK_RUST_DEVTOOLS_PORT").ok().and_then(|p| p.parse::<u16>().ok()) {
                Some(port) => builder.additional_browser_args(&format!(
                    "--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection --remote-debugging-port={port}"
                )),
                None => builder,
            };
            builder.build()?;
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running the Tauri application");
}
