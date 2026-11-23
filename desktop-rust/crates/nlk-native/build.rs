use std::path::{Path, PathBuf};

fn find_rc_exe() -> Option<PathBuf> {
    if let Ok(path) = std::env::var("RC")
        && Path::new(&path).is_file()
    {
        return Some(PathBuf::from(path));
    }
    if let Ok(paths) = std::env::var("PATH") {
        for dir in std::env::split_paths(&paths) {
            let candidate = dir.join("rc.exe");
            if candidate.is_file() {
                return Some(candidate);
            }
        }
    }
    let arch = match std::env::var("CARGO_CFG_TARGET_ARCH").as_deref() {
        Ok("aarch64") => "arm64",
        Ok("x86") => "x86",
        _ => "x64",
    };
    for root_var in ["ProgramFiles(x86)", "ProgramFiles"] {
        let Ok(root) = std::env::var(root_var) else { continue };
        let kits_bin = Path::new(&root).join("Windows Kits").join("10").join("bin");
        let Ok(entries) = std::fs::read_dir(&kits_bin) else { continue };
        let mut versions: Vec<PathBuf> = entries
            .filter_map(Result::ok)
            .map(|e| e.path().join(arch).join("rc.exe"))
            .filter(|p| p.is_file())
            .collect();
        versions.sort();
        if let Some(latest) = versions.pop() {
            return Some(latest);
        }
    }
    None
}

fn embed_windows_resources() {
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("windows") {
        return;
    }
    let icon_path = Path::new("ui/images/icon.ico");
    println!("cargo:rerun-if-changed={}", icon_path.display());
    if !icon_path.is_file() {
        println!("cargo:warning=icon.ico missing; skipping Windows resource embedding");
        return;
    }
    let Some(rc_exe) = find_rc_exe() else {
        println!("cargo:warning=Windows SDK rc.exe not found; binary will omit embedded icon/version resource");
        return;
    };
    let out_dir = PathBuf::from(std::env::var_os("OUT_DIR").expect("OUT_DIR set"));
    let version = std::env::var("CARGO_PKG_VERSION").unwrap_or_else(|_| "0.1.0".into());
    let parts: Vec<u16> = version.split('.').filter_map(|p| p.parse().ok()).collect();
    let (v0, v1, v2) = (*parts.first().unwrap_or(&0), *parts.get(1).unwrap_or(&1), *parts.get(2).unwrap_or(&0));
    let abs_icon = std::fs::canonicalize(icon_path).unwrap_or_else(|_| icon_path.to_path_buf());
    let icon_rc_str = abs_icon.to_string_lossy().trim_start_matches(r"\\?\").replace('\\', "\\\\");
    let rc_content = format!(
        "1 ICON \"{icon_rc_str}\"\n\
         1 VERSIONINFO\n\
         FILEVERSION {v0},{v1},{v2},0\n\
         PRODUCTVERSION {v0},{v1},{v2},0\n\
         FILEOS 0x40004L\n\
         FILETYPE 0x1L\n\
         BEGIN\n\
           BLOCK \"StringFileInfo\"\n\
           BEGIN\n\
             BLOCK \"040904b0\"\n\
             BEGIN\n\
               VALUE \"CompanyName\", \"Notelikeus\"\n\
               VALUE \"FileDescription\", \"Notelikeus Desktop\"\n\
               VALUE \"FileVersion\", \"{version}\"\n\
               VALUE \"InternalName\", \"notelikeus\"\n\
               VALUE \"OriginalFilename\", \"notelikeus.exe\"\n\
               VALUE \"ProductName\", \"Notelikeus\"\n\
               VALUE \"ProductVersion\", \"{version}\"\n\
             END\n\
           END\n\
           BLOCK \"VarFileInfo\"\n\
           BEGIN\n\
             VALUE \"Translation\", 0x409, 1200\n\
           END\n\
         END\n"
    );
    let rc_file = out_dir.join("notelikeus.rc");
    let res_file = out_dir.join("notelikeus.res");
    if std::fs::write(&rc_file, rc_content).is_err() {
        return;
    }
    let status = std::process::Command::new(rc_exe).args(["/nologo", "/fo"]).arg(&res_file).arg(&rc_file).status();
    if status.is_ok_and(|s| s.success()) && res_file.is_file() {
        println!("cargo:rustc-link-arg-bin=notelikeus={}", res_file.display());
    } else {
        println!("cargo:warning=rc.exe failed to compile Windows resource file");
    }
}

fn main() {
    embed_windows_resources();
    // Debug builds carry element debug info: the headless UI tests (tests/ui.rs) find elements by their
    // accessible labels, which needs it. Release builds leave it out.
    let debug = std::env::var("PROFILE").as_deref() == Ok("debug");
    let config = slint_build::CompilerConfiguration::new().with_debug_info(debug);
    slint_build::compile_with_config("ui/app.slint", config).expect("the Slint UI compiles");
}
