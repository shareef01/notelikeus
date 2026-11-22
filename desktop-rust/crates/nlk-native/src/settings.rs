use std::fs;

use notelikeus_app::data_dir_named;
use serde_json::{Value, json};

const SETTINGS_FILE: &str = "settings.json";

pub const THEME_SYSTEM: i32 = 0;
pub const THEME_LIGHT: i32 = 1;
pub const THEME_DARK: i32 = 2;

fn settings_path() -> std::path::PathBuf {
    data_dir_named(crate::DATA_FOLDER).join(SETTINGS_FILE)
}

pub fn load_theme_mode() -> i32 {
    let Ok(bytes) = fs::read(settings_path()) else { return THEME_SYSTEM };
    let Ok(settings) = serde_json::from_slice::<Value>(&bytes) else { return THEME_SYSTEM };
    match settings.get("themeMode").and_then(Value::as_str) {
        Some("light") => THEME_LIGHT,
        Some("dark") => THEME_DARK,
        _ => THEME_SYSTEM,
    }
}

pub fn save_theme_mode(mode: i32) -> Result<(), String> {
    let theme = match mode {
        THEME_LIGHT => "light",
        THEME_DARK => "dark",
        _ => "system",
    };
    let path = settings_path();
    fs::create_dir_all(path.parent().unwrap_or_else(|| std::path::Path::new(".")))
        .map_err(|error| format!("Couldn't save settings: {error}"))?;
    let bytes = serde_json::to_vec_pretty(&json!({ "themeMode": theme }))
        .map_err(|error| format!("Couldn't save settings: {error}"))?;
    fs::write(path, bytes).map_err(|error| format!("Couldn't save settings: {error}"))
}
