//! The native Notelikeus app for Windows: a Slint window (`ui/app.slint`) over the Rust client's app
//! logic (`notelikeus-app`). Its own logic and data: it never opens the Kotlin app's data, and its
//! notes live in `%APPDATA%\NotelikeusRust`.

slint::include_modules!();

pub mod backup;
pub mod contrast;
pub mod controller;
mod document;
pub mod images;
pub mod instance;
pub mod settings;
pub mod view;
#[cfg(windows)]
mod win_notify;

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

/// Packs an RGB color into a Win32 `COLORREF` (`0x00BBGGRR`).
#[cfg_attr(not(windows), allow(dead_code))]
fn rgb_to_colorref(r: u8, g: u8, b: u8) -> u32 {
    u32::from(r) | (u32::from(g) << 8) | (u32::from(b) << 16)
}

/// Tints the native Windows 11 title bar (caption, text, and window border) to match the active
/// app theme, accent, and AMOLED setting, while deferring to system colors in high-contrast mode.
pub fn sync_window_chrome(ui: &AppWindow) {
    #[cfg(windows)]
    {
        use raw_window_handle::{HasWindowHandle, RawWindowHandle};
        use slint::ComponentHandle;
        use windows_sys::Win32::Graphics::Dwm::{
            DWMWA_BORDER_COLOR, DWMWA_CAPTION_COLOR, DWMWA_COLOR_DEFAULT, DWMWA_TEXT_COLOR,
            DWMWA_USE_IMMERSIVE_DARK_MODE, DwmSetWindowAttribute,
        };

        let handle = ui.window().window_handle();
        let Ok(handle) = handle.window_handle() else { return };
        let RawWindowHandle::Win32(handle) = handle.as_raw() else { return };
        let hwnd = handle.hwnd.get() as _;

        let theme = ui.global::<Theme>();
        let dark: i32 = i32::from(theme.get_dark());
        let (caption, text, border) = if theme.get_high_contrast() {
            (DWMWA_COLOR_DEFAULT, DWMWA_COLOR_DEFAULT, DWMWA_COLOR_DEFAULT)
        } else {
            let bg = theme.get_background().to_argb_u8();
            let fg = theme.get_on_surface().to_argb_u8();
            let outline = theme.get_outline().to_argb_u8();
            (
                rgb_to_colorref(bg.red, bg.green, bg.blue),
                rgb_to_colorref(fg.red, fg.green, fg.blue),
                rgb_to_colorref(outline.red, outline.green, outline.blue),
            )
        };

        // SAFETY: `hwnd` belongs to the live Slint window, and each attribute pointer points to a
        // stack-allocated 32-bit value matching the size passed to `DwmSetWindowAttribute`.
        unsafe {
            let _ = DwmSetWindowAttribute(
                hwnd,
                DWMWA_USE_IMMERSIVE_DARK_MODE as u32,
                (&raw const dark).cast(),
                size_of::<i32>() as u32,
            );
            let _ = DwmSetWindowAttribute(
                hwnd,
                DWMWA_CAPTION_COLOR as u32,
                (&raw const caption).cast(),
                size_of::<u32>() as u32,
            );
            let _ =
                DwmSetWindowAttribute(hwnd, DWMWA_TEXT_COLOR as u32, (&raw const text).cast(), size_of::<u32>() as u32);
            let _ = DwmSetWindowAttribute(
                hwnd,
                DWMWA_BORDER_COLOR as u32,
                (&raw const border).cast(),
                size_of::<u32>() as u32,
            );
        }
    }
    #[cfg(not(windows))]
    {
        let _ = ui;
    }
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

#[cfg(test)]
mod tests {
    use super::rgb_to_colorref;

    #[test]
    fn colorref_packs_rgb_as_bgr() {
        assert_eq!(rgb_to_colorref(0x09, 0x0e, 0x0a), 0x000a_0e09);
        assert_eq!(rgb_to_colorref(0xf4, 0xf4, 0xf2), 0x00f2_f4f4);
    }
}
