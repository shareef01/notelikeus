#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use slint::ComponentHandle;

fn main() {
    match nlk_native::open() {
        Ok((ui, controller)) => {
            let tray = match nlk_native::TrayIcon::new() {
                Ok(tray) => tray,
                Err(error) => {
                    nlk_native::show_fatal_error(&format!("Notelikeus could not create its tray icon: {error}"));
                    return;
                }
            };
            let weak_ui = ui.as_weak();
            tray.on_open_requested({
                let weak_ui = weak_ui.clone();
                move || {
                    if let Some(ui) = weak_ui.upgrade() {
                        let _ = ui.window().show();
                        ui.invoke_focus_main();
                    }
                }
            });
            tray.on_new_note_requested({
                let weak_ui = weak_ui.clone();
                let controller = controller.clone();
                move || {
                    if let Some(ui) = weak_ui.upgrade() {
                        let _ = ui.window().show();
                        ui.invoke_focus_main();
                        controller.new_note();
                    }
                }
            });
            tray.on_exit_requested(move || {
                controller.save_now();
                let _ = slint::quit_event_loop();
            });

            // The tray component owns the native icon; keep it alive while the event loop runs.
            let _tray = tray;
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
