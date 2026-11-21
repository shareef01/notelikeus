#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    match nlk_native::open() {
        Ok((ui, _controller)) => {
            if let Err(error) = slint::ComponentHandle::run(&ui) {
                nlk_native::show_fatal_error(&format!("Notelikeus stopped unexpectedly: {error}"));
            }
        }
        Err(message) => {
            nlk_native::show_fatal_error(&message);
            std::process::exit(1);
        }
    }
}
