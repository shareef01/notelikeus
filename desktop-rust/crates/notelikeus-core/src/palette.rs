//! The note colour palette, pinned by `contracts/design/note-colors.json` (tested against it), and the
//! rule every client uses for it: a note stores one ARGB, the light or the dark variant of a palette
//! entry (whichever the picker showed), and is displayed in the active theme's counterpart.

/// One palette entry. `light` and `dark` are signed ARGB, as notes store them.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct NoteColor {
    pub keyword: &'static str,
    pub name: &'static str,
    pub light: i32,
    pub dark: i32,
}

const fn argb(value: u32) -> i32 {
    value as i32
}

/// In picker order; index 0 is no colour (ARGB 0, shown as the theme's surface).
pub const NOTE_COLORS: [NoteColor; 9] = [
    NoteColor { keyword: "none", name: "No Color", light: 0, dark: 0 },
    NoteColor { keyword: "red", name: "Red", light: argb(0xFFFFCDD2), dark: argb(0xFF6D2B2B) },
    NoteColor { keyword: "orange", name: "Orange", light: argb(0xFFFFE0B2), dark: argb(0xFF6B4520) },
    NoteColor { keyword: "yellow", name: "Yellow", light: argb(0xFFFFF59D), dark: argb(0xFF6B5C18) },
    NoteColor { keyword: "green", name: "Green", light: argb(0xFFC8E6C9), dark: argb(0xFF2E5A32) },
    NoteColor { keyword: "teal", name: "Teal", light: argb(0xFFB2DFDB), dark: argb(0xFF1E5650) },
    NoteColor { keyword: "blue", name: "Blue", light: argb(0xFFBBDEFB), dark: argb(0xFF2A4A6E) },
    NoteColor { keyword: "purple", name: "Purple", light: argb(0xFFE1BEE7), dark: argb(0xFF4A2D62) },
    NoteColor { keyword: "pink", name: "Pink", light: argb(0xFFF8BBD0), dark: argb(0xFF6B2D48) },
];

/// The palette index a stored colour belongs to (either variant), or None for a colour not in the
/// palette. 0 for no colour.
pub fn index_of(stored: i32) -> Option<usize> {
    if stored == 0 {
        return Some(0);
    }
    NOTE_COLORS.iter().position(|c| c.light == stored || c.dark == stored)
}

/// What to show for a stored colour in the active theme: its counterpart when it is a palette colour,
/// itself otherwise (a colour some future client stored), 0 for none.
pub fn for_theme(stored: i32, dark: bool) -> i32 {
    match index_of(stored) {
        Some(index) => {
            let color = NOTE_COLORS[index];
            if dark { color.dark } else { color.light }
        }
        None => stored,
    }
}

/// What to store when the picker chose `index` while showing the `dark` theme: the variant on screen,
/// as the web app's picker stores it.
pub fn to_store(index: usize, dark: bool) -> i32 {
    let color = NOTE_COLORS[index.min(NOTE_COLORS.len() - 1)];
    if dark { color.dark } else { color.light }
}

/// Canonical chrome palette for one of the 7 themes in `contracts/design/theme-colors.json`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ThemePalette {
    pub key: &'static str,
    pub dark: bool,
    pub amoled: bool,
    pub accent_key: &'static str,
    pub background: u32,
    pub surface: u32,
    pub surface_variant: u32,
    pub on_surface: u32,
    pub secondary: u32,
    pub muted: u32,
    pub outline: u32,
}

pub const THEME_PALETTES: [ThemePalette; 7] = [
    ThemePalette {
        key: "light",
        dark: false,
        amoled: false,
        accent_key: "neutral",
        background: 0xFFF4F4F2,
        surface: 0xFFFFFFFF,
        surface_variant: 0xFFECECEA,
        on_surface: 0xFF151515,
        secondary: 0xFF4D4D4B,
        muted: 0xFF666663,
        outline: 0xFFD6D6D3,
    },
    ThemePalette {
        key: "dark",
        dark: true,
        amoled: false,
        accent_key: "neutral",
        background: 0xFF101010,
        surface: 0xFF1B1B1B,
        surface_variant: 0xFF272727,
        on_surface: 0xFFEDEDED,
        secondary: 0xFFB0B0B0,
        muted: 0xFF949494,
        outline: 0xFF323232,
    },
    ThemePalette {
        key: "amoled",
        dark: true,
        amoled: true,
        accent_key: "neutral",
        background: 0xFF000000,
        surface: 0xFF000000,
        surface_variant: 0xFF121212,
        on_surface: 0xFFFFFFFF,
        secondary: 0xFFA8A8A8,
        muted: 0xFF999999,
        outline: 0xFF262626,
    },
    ThemePalette {
        key: "midnight",
        dark: true,
        amoled: false,
        accent_key: "blue",
        background: 0xFF070A12,
        surface: 0xFF0C111C,
        surface_variant: 0xFF141B2A,
        on_surface: 0xFFDCE8FF,
        secondary: 0xFF9AACCC,
        muted: 0xFF8A9CB8,
        outline: 0xFF283448,
    },
    ThemePalette {
        key: "midnightAmoled",
        dark: true,
        amoled: true,
        accent_key: "blue",
        background: 0xFF000000,
        surface: 0xFF000000,
        surface_variant: 0xFF141B2A,
        on_surface: 0xFFDCE8FF,
        secondary: 0xFF9AACCC,
        muted: 0xFF8A9CB8,
        outline: 0xFF283448,
    },
    ThemePalette {
        key: "forest",
        dark: true,
        amoled: false,
        accent_key: "green",
        background: 0xFF090E0A,
        surface: 0xFF0F1610,
        surface_variant: 0xFF182119,
        on_surface: 0xFFD6F0D6,
        secondary: 0xFF9AB89E,
        muted: 0xFF8AA48E,
        outline: 0xFF2A382C,
    },
    ThemePalette {
        key: "forestAmoled",
        dark: true,
        amoled: true,
        accent_key: "green",
        background: 0xFF000000,
        surface: 0xFF000000,
        surface_variant: 0xFF182119,
        on_surface: 0xFFD6F0D6,
        secondary: 0xFF9AB89E,
        muted: 0xFF8AA48E,
        outline: 0xFF2A382C,
    },
];

pub const LIGHT_ACCENT_BLUE: u32 = 0xFF0B57D0;
pub const LIGHT_ACCENT_GREEN: u32 = 0xFF1B6B2E;
