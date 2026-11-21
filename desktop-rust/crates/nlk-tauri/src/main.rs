//! Tauri 2 shell for the experimental Rust core. Notes live in the encrypted store under
//! `%APPDATA%\NotelikeusRustExperimental`: guest notes stay on the device; signed-in notes sync with
//! the Supabase project in `NLK_SUPABASE_URL` / `NLK_SUPABASE_KEY` (none set: no sync). The Kotlin
//! app's data is never opened (the store refuses `~/.notelikeus`).

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod service;

use std::sync::{Arc, Mutex};

use service::{AccountView, AppInfo, NoteView, NotesService, SyncConfig, SyncSummary, SystemIds};
use tauri::State;

#[derive(Clone)]
struct App(Arc<Mutex<NotesService>>);

/// Runs `f` on a blocking-pool thread: sign-in and sync wait on the network, and a command that ran
/// on the main thread would freeze the window meanwhile.
async fn with<T: Send + 'static>(
    app: State<'_, App>,
    f: impl FnOnce(&mut NotesService) -> Result<T, String> + Send + 'static,
) -> Result<T, String> {
    let shared = app.0.clone();
    tauri::async_runtime::spawn_blocking(move || {
        let mut service = shared.lock().map_err(|_| "The notes store is unavailable; restart the app.".to_string())?;
        f(&mut service)
    })
    .await
    .map_err(|e| format!("The command stopped unexpectedly: {e}"))?
}

#[tauri::command]
async fn list_notes(app: State<'_, App>) -> Result<Vec<NoteView>, String> {
    with(app, |s| Ok(s.list())).await
}

#[tauri::command]
async fn create_note(app: State<'_, App>, title: String, content: String) -> Result<NoteView, String> {
    with(app, move |s| s.create(&title, &content)).await
}

#[tauri::command]
async fn update_note(app: State<'_, App>, id: String, title: String, content: String) -> Result<NoteView, String> {
    with(app, move |s| s.update(&id, &title, &content)).await
}

#[tauri::command]
async fn set_pinned(app: State<'_, App>, id: String, pinned: bool) -> Result<NoteView, String> {
    with(app, move |s| s.set_pinned(&id, pinned)).await
}

#[tauri::command]
async fn delete_note(app: State<'_, App>, id: String) -> Result<NoteView, String> {
    with(app, move |s| s.delete(&id)).await
}

#[tauri::command]
async fn app_info(app: State<'_, App>) -> Result<AppInfo, String> {
    with(app, |s| Ok(s.info())).await
}

#[tauri::command]
async fn account(app: State<'_, App>) -> Result<AccountView, String> {
    with(app, |s| Ok(s.account())).await
}

#[tauri::command]
async fn request_code(app: State<'_, App>, email: String) -> Result<(), String> {
    with(app, move |s| s.request_code(&email)).await
}

#[tauri::command]
async fn verify_code(app: State<'_, App>, email: String, code: String) -> Result<AccountView, String> {
    with(app, move |s| s.verify_code(&email, &code)).await
}

#[tauri::command]
async fn sign_out(app: State<'_, App>) -> Result<AccountView, String> {
    with(app, |s| s.sign_out()).await
}

#[tauri::command]
async fn sync_now(app: State<'_, App>) -> Result<SyncSummary, String> {
    with(app, |s| s.sync_now()).await
}

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
    let service = match NotesService::open(&dir, protector(), Box::new(SystemIds), SyncConfig::from_env()) {
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
        .manage(App(Arc::new(Mutex::new(service))))
        .invoke_handler(tauri::generate_handler![
            list_notes,
            create_note,
            update_note,
            set_pinned,
            delete_note,
            app_info,
            account,
            request_code,
            verify_code,
            sign_out,
            sync_now
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
