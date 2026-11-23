//! Windows high-contrast themes (Settings → Accessibility → Contrast themes).
//!
//! When one is on, the app draws every chrome role from the system colours the user picked instead
//! of its own palette, and note cards drop their pastel tint: a contrast theme is a promise that the
//! user's colours are the only ones on screen. `current` is cheap (two user32 calls), so the
//! controller polls it rather than hooking `WM_SETTINGCHANGE`, which Slint's winit backend does not
//! expose.

/// The system colours the app maps its roles to, as `COLORREF`s (`0x00BBGGRR`).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct SystemColors {
    /// Window background (`COLOR_WINDOW`): app canvas and surfaces.
    pub window: u32,
    /// Window text (`COLOR_WINDOWTEXT`): all text, icons and borders.
    pub window_text: u32,
    /// Button face (`COLOR_BTNFACE`): inputs, chips, raised surfaces.
    pub button_face: u32,
    /// Button text (`COLOR_BTNTEXT`): text on button-face surfaces.
    pub button_text: u32,
    /// Selected background (`COLOR_HIGHLIGHT`): focus rings, selection, the accent.
    pub highlight: u32,
    /// Selected text (`COLOR_HIGHLIGHTTEXT`): text on a highlight fill.
    pub highlight_text: u32,
    /// Disabled text (`COLOR_GRAYTEXT`).
    pub gray_text: u32,
    /// Hyperlink (`COLOR_HOTLIGHT`): links and the danger role, so it stays distinct from body text.
    pub hotlight: u32,
}

/// A `COLORREF` as `(r, g, b)`.
pub fn rgb(colorref: u32) -> (u8, u8, u8) {
    ((colorref & 0xFF) as u8, ((colorref >> 8) & 0xFF) as u8, ((colorref >> 16) & 0xFF) as u8)
}

/// Whether a background is dark, by WCAG relative luminance: decides the few places that still pick
/// between a light and a dark variant (the system "dark" flag says nothing about a contrast theme).
pub fn is_dark(colorref: u32) -> bool {
    let (r, g, b) = rgb(colorref);
    let linear = |c: u8| {
        let c = f64::from(c) / 255.0;
        if c <= 0.03928 { c / 12.92 } else { ((c + 0.055) / 1.055).powf(2.4) }
    };
    0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b) < 0.18
}

/// The active contrast theme's colours, or `None` when no contrast theme is on.
#[cfg(windows)]
pub fn current() -> Option<SystemColors> {
    use std::mem::size_of;
    use windows_sys::Win32::Graphics::Gdi::{
        COLOR_BTNFACE, COLOR_BTNTEXT, COLOR_GRAYTEXT, COLOR_HIGHLIGHT, COLOR_HIGHLIGHTTEXT, COLOR_HOTLIGHT,
        COLOR_WINDOW, COLOR_WINDOWTEXT, GetSysColor,
    };
    use windows_sys::Win32::UI::Accessibility::{HCF_HIGHCONTRASTON, HIGHCONTRASTW};
    use windows_sys::Win32::UI::WindowsAndMessaging::{SPI_GETHIGHCONTRAST, SystemParametersInfoW};

    let mut info = HIGHCONTRASTW {
        cbSize: size_of::<HIGHCONTRASTW>() as u32,
        dwFlags: 0,
        lpszDefaultScheme: std::ptr::null_mut(),
    };
    // SAFETY: `info` is a correctly sized HIGHCONTRASTW whose cbSize is set, as SPI_GETHIGHCONTRAST requires;
    // the call only writes into it.
    let ok =
        unsafe { SystemParametersInfoW(SPI_GETHIGHCONTRAST, info.cbSize, (&mut info as *mut HIGHCONTRASTW).cast(), 0) };
    if ok == 0 || info.dwFlags & HCF_HIGHCONTRASTON == 0 {
        return None;
    }
    // SAFETY: GetSysColor takes a colour index and has no other preconditions.
    let sys = |index| unsafe { GetSysColor(index) };
    Some(SystemColors {
        window: sys(COLOR_WINDOW),
        window_text: sys(COLOR_WINDOWTEXT),
        button_face: sys(COLOR_BTNFACE),
        button_text: sys(COLOR_BTNTEXT),
        highlight: sys(COLOR_HIGHLIGHT),
        highlight_text: sys(COLOR_HIGHLIGHTTEXT),
        gray_text: sys(COLOR_GRAYTEXT),
        hotlight: sys(COLOR_HOTLIGHT),
    })
}

#[cfg(not(windows))]
pub fn current() -> Option<SystemColors> {
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn colorrefs_are_bgr() {
        assert_eq!(rgb(0x00FF_8000), (0x00, 0x80, 0xFF));
        assert_eq!(rgb(0x0000_00FF), (0xFF, 0x00, 0x00));
    }

    #[test]
    fn darkness_follows_luminance_of_the_contrast_themes() {
        // Window backgrounds of dark and light contrast themes.
        assert!(is_dark(0x0020_2020), "a dark window #202020");
        assert!(is_dark(0x0000_0000), "a black window");
        assert!(!is_dark(0x00EF_FFFF), "a light window #FFFFEF");
        assert!(!is_dark(0x00FF_FFFF));
    }
}
