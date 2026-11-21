//! Tauri 2 shell for the experimental Rust core. Notes live in the encrypted store under
//! `%APPDATA%\NotelikeusRustExperimental`: guest notes stay on the device; signed-in notes sync with
//! the Supabase project in `NLK_SUPABASE_URL` / `NLK_SUPABASE_KEY` (none set: no sync). The Kotlin
//! app's data is never opened (the store refuses `~/.notelikeus`).

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::sync::{Arc, Mutex};

use notelikeus_app::{AccountView, AppInfo, NoteView, NotesService, SyncConfig, SyncSummary, SystemIds};
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
async fn request_code(handle: tauri::AppHandle, app: State<'_, App>, email: String) -> Result<(), String> {
    let page = with(app.clone(), |s| Ok(s.captcha_page_url())).await?;
    let token = match page {
        Some(page) => Some(security_check(&handle, &page).await?),
        None => None,
    };
    with(app, move |s| s.request_code(&email, token.as_deref())).await
}

const SECURITY_CHECK_CANCELLED: &str = "Sign-in needs a quick security check before we can email a code. Try again.";

/// Opens the web app's Turnstile page in its own window and waits for the token it hands back.
///
/// The window gets no app permissions (the capability file grants commands to `main` only), may only
/// navigate within the page's own site, and its final `notelikeus-captcha:` navigation is blocked and
/// read instead: the token counts only with the random state this call put in the URL. Closing the
/// window cancels; after five minutes it gives up.
async fn security_check(handle: &tauri::AppHandle, page: &str) -> Result<String, String> {
    use notelikeus_core::captcha;
    use tauri::{Manager, WebviewUrl, WebviewWindowBuilder, WindowEvent};

    let page = captcha::checked_page_url(page)?;
    let state = captcha::new_state().map_err(|e| e.to_string())?;
    let url: tauri::Url =
        captcha::challenge_url(&page, &state).parse().map_err(|e| format!("bad captcha page URL: {e}"))?;
    let origin = url.origin();
    let label = "security-check";
    if let Some(open) = handle.get_webview_window(label) {
        let _ = open.set_focus();
        return Err("The security check is already open.".into());
    }

    let (tx, rx) = tokio::sync::oneshot::channel::<Result<String, String>>();
    let tx = Arc::new(Mutex::new(Some(tx)));
    let send = {
        let tx = tx.clone();
        move |result: Result<String, String>| {
            if let Some(tx) = tx.lock().ok().and_then(|mut slot| slot.take()) {
                let _ = tx.send(result);
            }
        }
    };
    let on_return = send.clone();
    let builder = WebviewWindowBuilder::new(handle, label, WebviewUrl::External(url))
        .title("Security check · Notelikeus")
        .inner_size(460.0, 620.0)
        .resizable(false);
    let builder = match debug_browser_args() {
        Some(args) => builder.additional_browser_args(&args),
        None => builder,
    };
    let window = builder
        .on_navigation(move |next| {
            if captcha::is_return_url(next.as_str()) {
                on_return(
                    captcha::token_from_return_url(next.as_str(), &state)
                        .ok_or_else(|| SECURITY_CHECK_CANCELLED.to_string()),
                );
                return false;
            }
            // Only the challenge page's own site; Turnstile itself runs in a frame, not a navigation.
            next.origin() == origin
        })
        .build()
        .map_err(|e| format!("Could not open the security check: {e}"))?;
    let on_close = send.clone();
    window.on_window_event(move |event| {
        if matches!(event, WindowEvent::Destroyed) {
            on_close(Err(SECURITY_CHECK_CANCELLED.into()));
        }
    });

    let outcome = tokio::time::timeout(std::time::Duration::from_secs(300), rx).await;
    let _ = window.close();
    match outcome {
        Ok(Ok(result)) => result,
        Ok(Err(_)) | Err(_) => Err(SECURITY_CHECK_CANCELLED.into()),
    }
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

/// Debug builds only: `NLK_RUST_DEVTOOLS_PORT` opens WebView2's DevTools protocol on loopback so the
/// UI can be driven end to end in tests. A release build always answers None.
///
/// Every window must be built with these same arguments: WebView2 refuses to create a second window
/// whose browser arguments differ from the first's in the same data folder (0x8007139F).
fn debug_browser_args() -> Option<String> {
    #[cfg(debug_assertions)]
    {
        let port = std::env::var("NLK_RUST_DEVTOOLS_PORT").ok()?.parse::<u16>().ok()?;
        Some(format!("--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection --remote-debugging-port={port}"))
    }
    #[cfg(not(debug_assertions))]
    None
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
    let dir = notelikeus_app::default_data_dir();
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
            let builder = match debug_browser_args() {
                Some(args) => builder.additional_browser_args(&args),
                None => builder,
            };
            builder.build()?;
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running the Tauri application");
}
