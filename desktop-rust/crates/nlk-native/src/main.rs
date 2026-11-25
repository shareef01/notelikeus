#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::time::Duration;

use nlk_native::instance::{self, AcquireOutcome};
use slint::{ComponentHandle, Timer, TimerMode};

#[cfg(windows)]
fn fit_initial_window_to_work_area(window: &slint::Window) {
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};
    use windows_sys::Win32::Foundation::RECT;
    use windows_sys::Win32::Graphics::Gdi::{
        GetMonitorInfoW, MONITOR_DEFAULTTONEAREST, MONITORINFO, MonitorFromWindow,
    };
    use windows_sys::Win32::UI::WindowsAndMessaging::{GetWindowRect, SWP_NOACTIVATE, SWP_NOZORDER, SetWindowPos};

    let handle = window.window_handle();
    let Ok(handle) = handle.window_handle() else { return };
    let RawWindowHandle::Win32(handle) = handle.as_raw() else { return };
    let hwnd = handle.hwnd.get() as _;
    let mut rect = RECT { left: 0, top: 0, right: 0, bottom: 0 };
    let mut monitor_info =
        MONITORINFO { cbSize: size_of::<MONITORINFO>() as u32, rcMonitor: rect, rcWork: rect, dwFlags: 0 };
    // The HWND belongs to the live Slint window. The Win32 output pointers are valid for the calls.
    unsafe {
        let monitor = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
        if monitor.is_null() || GetMonitorInfoW(monitor, &mut monitor_info) == 0 || GetWindowRect(hwnd, &mut rect) == 0
        {
            return;
        }
    }
    let work = monitor_info.rcWork;
    let width = rect.right - rect.left;
    let height = rect.bottom - rect.top;
    let fit_width = width.min(work.right - work.left);
    let fit_height = height.min(work.bottom - work.top);
    if fit_width <= 0 || fit_height <= 0 || (fit_width == width && fit_height == height) {
        return;
    }
    let x = rect.left.clamp(work.left, work.right - fit_width);
    let y = rect.top.clamp(work.top, work.bottom - fit_height);
    // Win32 measures the outer window and monitor work area in display pixels, including the frame.
    unsafe { SetWindowPos(hwnd, std::ptr::null_mut(), x, y, fit_width, fit_height, SWP_NOACTIVATE | SWP_NOZORDER) };
}

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
            if let Err(error) = ui.show() {
                nlk_native::show_fatal_error(&format!("Notelikeus could not show its window: {error}"));
                return;
            }
            let weak_ui = ui.as_weak();
            #[cfg(windows)]
            {
                // Slint creates the native window when the event loop starts.
                let weak_ui = weak_ui.clone();
                let _ = slint::invoke_from_event_loop(move || {
                    if let Some(ui) = weak_ui.upgrade() {
                        fit_initial_window_to_work_area(ui.window());
                    }
                });
            }
            let tray = match nlk_native::TrayIcon::new() {
                Ok(tray) => {
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
                            if !controller.save_now() {
                                return;
                            }
                            controller.cleanup_notifications();
                            let _ = slint::quit_event_loop();
                        }
                    });
                    Some(tray)
                }
                Err(error) => {
                    eprintln!("Notelikeus tray icon unavailable: {error}");
                    None
                }
            };
            controller.set_tray_available(tray.is_some());

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
            if let Err(error) = slint::run_event_loop_until_quit() {
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
