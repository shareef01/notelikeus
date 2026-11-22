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
