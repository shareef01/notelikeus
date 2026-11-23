use std::fs::{self, File};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

use notelikeus_app::{SortOrder, data_dir_named};
use serde_json::{Value, json};

const SETTINGS_FILE: &str = "settings.json";
static SETTINGS_TEMP_SEQ: AtomicU64 = AtomicU64::new(0);

pub const THEME_SYSTEM: i32 = 0;
pub const THEME_LIGHT: i32 = 1;
pub const THEME_DARK: i32 = 2;

pub const ACCENT_NEUTRAL: i32 = 0;
pub const ACCENT_BLUE: i32 = 1;
pub const ACCENT_GREEN: i32 = 2;

pub const DENSITY_GRID: i32 = 0;
pub const DENSITY_LIST: i32 = 1;
pub const DENSITY_COMPACT: i32 = 2;

pub const SORT_MANUAL: i32 = 0;
pub const SORT_NEWEST: i32 = 1;
pub const SORT_OLDEST: i32 = 2;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AppSettings {
    /// 0 = Follow Windows, 1 = Light, 2 = Dark. Combined with `accent` and `amoled` to select
    /// one of the 7 canonical palettes in `contracts/design/theme-colors.json`.
    pub theme_mode: i32,
    /// 0 = Neutral, 1 = Blue, 2 = Green.
    pub accent: i32,
    pub amoled: bool,
    pub sort_order: i32,
    pub view_density: i32,
    pub sidebar_collapsed: bool,
}

impl Default for AppSettings {
    fn default() -> Self {
        Self {
            theme_mode: THEME_SYSTEM,
            accent: ACCENT_NEUTRAL,
            amoled: false,
            sort_order: SORT_MANUAL,
            view_density: DENSITY_GRID,
            sidebar_collapsed: false,
        }
    }
}

impl AppSettings {
    pub fn to_sort_order(&self) -> SortOrder {
        match self.sort_order {
            SORT_NEWEST => SortOrder::Newest,
            SORT_OLDEST => SortOrder::Oldest,
            _ => SortOrder::Manual,
        }
    }
}

fn settings_path_in(root: &Path) -> PathBuf {
    root.join(SETTINGS_FILE)
}

pub fn load_settings(root: &Path) -> AppSettings {
    let Ok(bytes) = fs::read(settings_path_in(root)) else {
        return AppSettings::default();
    };
    let Ok(settings) = serde_json::from_slice::<Value>(&bytes) else {
        return AppSettings::default();
    };
    let theme_mode = match settings.get("themeMode").and_then(Value::as_str) {
        Some("light") => THEME_LIGHT,
        Some("dark") => THEME_DARK,
        _ => THEME_SYSTEM,
    };
    let accent = match settings.get("accent").and_then(Value::as_str) {
        Some("blue") => ACCENT_BLUE,
        Some("green") => ACCENT_GREEN,
        _ => ACCENT_NEUTRAL,
    };
    let sort_order = match settings.get("sortOrder").and_then(Value::as_str) {
        Some("newest") => SORT_NEWEST,
        Some("oldest") => SORT_OLDEST,
        _ => SORT_MANUAL,
    };
    let view_density = match settings.get("viewDensity").and_then(Value::as_str) {
        Some("list") => DENSITY_LIST,
        Some("compact") => DENSITY_COMPACT,
        _ => DENSITY_GRID,
    };
    AppSettings {
        theme_mode,
        accent,
        amoled: settings.get("amoled").and_then(Value::as_bool).unwrap_or(false),
        sort_order,
        view_density,
        sidebar_collapsed: settings.get("sidebarCollapsed").and_then(Value::as_bool).unwrap_or(false),
    }
}

pub fn save_settings(root: &Path, settings: &AppSettings) -> Result<(), String> {
    let theme_mode = match settings.theme_mode {
        THEME_LIGHT => "light",
        THEME_DARK => "dark",
        _ => "system",
    };
    let accent = match settings.accent {
        ACCENT_BLUE => "blue",
        ACCENT_GREEN => "green",
        _ => "neutral",
    };
    let sort_order = match settings.sort_order {
        SORT_NEWEST => "newest",
        SORT_OLDEST => "oldest",
        _ => "manual",
    };
    let view_density = match settings.view_density {
        DENSITY_LIST => "list",
        DENSITY_COMPACT => "compact",
        _ => "grid",
    };
    fs::create_dir_all(root).map_err(|error| format!("Couldn't save settings: {error}"))?;
    let bytes = serde_json::to_vec_pretty(&json!({
        "themeMode": theme_mode,
        "accent": accent,
        "amoled": settings.amoled,
        "sortOrder": sort_order,
        "viewDensity": view_density,
        "sidebarCollapsed": settings.sidebar_collapsed,
    }))
    .map_err(|error| format!("Couldn't save settings: {error}"))?;
    let path = settings_path_in(root);
    let seq = SETTINGS_TEMP_SEQ.fetch_add(1, Ordering::Relaxed);
    let temp = root.join(format!("{SETTINGS_FILE}.{}.{seq}.tmp", std::process::id()));
    let mut file = File::create(&temp).map_err(|error| format!("Couldn't save settings: {error}"))?;
    if let Err(error) = file.write_all(&bytes).and_then(|()| file.sync_all()) {
        drop(file);
        let _ = fs::remove_file(&temp);
        return Err(format!("Couldn't save settings: {error}"));
    }
    drop(file);
    if let Err(error) = fs::rename(&temp, &path) {
        let _ = fs::remove_file(&temp);
        return Err(format!("Couldn't save settings: {error}"));
    }
    Ok(())
}

pub fn load_theme_mode() -> i32 {
    load_settings(&data_dir_named(crate::DATA_FOLDER)).theme_mode
}

pub fn save_theme_mode(mode: i32) -> Result<(), String> {
    let root = data_dir_named(crate::DATA_FOLDER);
    let mut current = load_settings(&root);
    current.theme_mode = mode;
    save_settings(&root, &current)
}
