#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::time::Duration;

use nlk_native::instance::{self, AcquireOutcome};
use slint::{ComponentHandle, Timer, TimerMode};

fn main() {
    let dir = notelikeus_app::data_dir_named(nlk_native::DATA_FOLDER);
    let instance_guard = match instance::acquire(&dir) {
        Ok(AcquireOutcome::Primary(guard)) => guard,
        Ok(AcquireOutcome::AlreadyRunning) => return,
        Err(message) => {
            nlk_native::show_fatal_error(&message);
            std::process::exit(1);
        }
    };

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
                        ui.window().set_minimized(false);
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
                        ui.window().set_minimized(false);
                        let _ = ui.window().show();
                        ui.invoke_focus_main();
                        controller.new_note();
                    }
                }
            });
            tray.on_exit_requested({
                let controller = controller.clone();
                move || {
                    controller.save_now();
                    controller.cleanup_notifications();
                    let _ = slint::quit_event_loop();
                }
            });

            let activation_timer = Timer::default();
            activation_timer.start(TimerMode::Repeated, Duration::from_millis(400), move || {
                if instance_guard.consume_activation_request()
                    && let Some(ui) = weak_ui.upgrade()
                {
                    ui.window().set_minimized(false);
                    let _ = ui.window().show();
                    ui.invoke_focus_main();
                }
            });

            // The tray component owns the native icon and activation timer; keep both alive on the loop.
            let _tray = tray;
            let _activation_timer = activation_timer;
            if let Err(error) = slint::ComponentHandle::run(&ui) {
                nlk_native::show_fatal_error(&format!("Notelikeus stopped unexpectedly: {error}"));
            }
            controller.save_now();
            controller.cleanup_notifications();
        }
        Err(message) => {
            nlk_native::show_fatal_error(&message);
            std::process::exit(1);
        }
    }
}
