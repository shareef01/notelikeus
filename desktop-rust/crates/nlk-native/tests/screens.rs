//! Rendered-window checks for the native app: the real `AppWindow`, drawn by Slint's software renderer
//! in the testing backend, at the window sizes and scale factors the design review checks by eye.
//!
//! What it asserts (no reference images, so a font or anti-aliasing change cannot fail it):
//! - every size renders a frame of the expected pixel size;
//! - the controls that must never be lost — "New note" and the search field — lie inside the window;
//! - under a Windows contrast theme the canvas is drawn in the system window colour, not the app palette.
//!
//! Set `NLK_SCREENSHOT_DIR` to also write each frame as a PNG for review (docs/audits/…).

use std::path::{Path, PathBuf};

use i_slint_backend_testing::ElementHandle;
use nlk_native::AppWindow;
use nlk_native::controller::{Controller, Mode};
use notelikeus_app::{NotesService, SystemIds};
use notelikeus_core::crypto::PlaintextKeyProtectorForTests;
use slint::ComponentHandle;

fn temp_dir(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("nlk-native-screens-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    dir
}

fn press(ui: &AppWindow, label: &str) {
    let element = ElementHandle::find_by_accessible_label(ui, label)
        .next()
        .unwrap_or_else(|| panic!("no element labelled {label:?}"));
    element.invoke_accessible_default_action();
}

fn open(dir: &Path) -> (AppWindow, std::rc::Rc<Controller>) {
    let service = NotesService::open(dir, Box::new(PlaintextKeyProtectorForTests), Box::new(SystemIds), None).unwrap();
    let ui = AppWindow::new().unwrap();
    let controller = Controller::attach_with(&ui, service, Mode::Inline);
    (ui, controller)
}

fn add_note(ui: &AppWindow, title: &str, body: &str, colour: Option<&str>, pin: bool) {
    press(ui, "New note");
    ui.set_editor_title(title.into());
    ui.set_editor_body(body.into());
    ui.invoke_editor_edited();
    ui.invoke_autosave_due();
    if let Some(colour) = colour {
        press(ui, "Note options");
        press(ui, colour);
        ui.set_options_open(false);
    }
    if pin {
        press(ui, "Pin");
    }
    press(ui, "Back to notes");
}

/// Sets the logical window size, then renders a frame at 1x.
///
/// Display scaling is covered through the logical size it produces (a 1920x1080 screen at 200% is a
/// 960x540 window), which is what decides layout. The testing backend does not apply a changed scale
/// factor to its snapshot buffer consistently, so 2x pixel density itself (text crispness) is not drawn.
fn render(ui: &AppWindow, width: u32, height: u32) -> slint::SharedPixelBuffer<slint::Rgba8Pixel> {
    let window = ui.window();
    window.set_size(slint::LogicalSize::new(width as f32, height as f32));
    let frame = window.take_snapshot().expect("the software renderer draws a frame");
    assert_eq!((frame.width(), frame.height()), (width, height), "frame size at {width}x{height}");
    frame
}

fn inside(ui: &AppWindow, label: &str, width: u32, height: u32) {
    let element = ElementHandle::find_by_accessible_label(ui, label)
        .next()
        .unwrap_or_else(|| panic!("no element labelled {label:?}"));
    let (p, s) = (element.absolute_position(), element.size());
    assert!(
        p.x >= 0.0 && p.y >= 0.0 && p.x + s.width <= width as f32 + 0.5 && p.y + s.height <= height as f32 + 0.5,
        "{label:?} at ({}, {}) size {}x{} is outside the {width}x{height} window",
        p.x,
        p.y,
        s.width,
        s.height
    );
}

fn save(frame: &slint::SharedPixelBuffer<slint::Rgba8Pixel>, name: &str) {
    let Some(dir) = std::env::var_os("NLK_SCREENSHOT_DIR") else { return };
    let dir = PathBuf::from(dir);
    std::fs::create_dir_all(&dir).unwrap();
    let image = image::RgbaImage::from_raw(frame.width(), frame.height(), frame.as_bytes().to_vec()).unwrap();
    image.save(dir.join(format!("{name}.png"))).unwrap();
}

#[test]
fn the_window_renders_at_every_reviewed_size_scale_and_theme() {
    slint::platform::set_platform(Box::new(i_slint_backend_testing::TestingBackend::new(
        i_slint_backend_testing::TestingBackendOptions {
            mock_time: true,
            threading: false,
            renderer_name: Some("software".into()),
        },
    )))
    .unwrap();

    let dir = temp_dir("matrix");
    let (ui, _controller) = open(&dir);
    ui.window().set_size(slint::LogicalSize::new(1280.0, 900.0));
    add_note(&ui, "Weekend groceries", "Oat milk, sourdough, lemons\nSomething for Sunday lunch", Some("Yellow"), true);
    add_note(
        &ui,
        "Reading notes on everyday design",
        "Affordances vs signifiers. A flat plate says push; a handle says pull, and people pull it anyway.",
        None,
        false,
    );
    add_note(&ui, "Packing list", "- [ ] Passport\n- [x] Phone charger\n- [ ] Rain jacket", Some("Blue"), false);
    add_note(&ui, "Groceries", "Short title test", Some("Green"), false);

    for theme in [1, 2] {
        ui.set_theme_mode(theme);
        let theme_name = if theme == 1 { "light" } else { "dark" };
        // 800x600 … 1920x1080 at 100%; 960x540 = 1920x1080 at 200%; 853x480 = 1280x720 at 150%;
        // 640x480 = the window's minimum size.
        for (w, h) in [(800, 600), (1024, 768), (1280, 720), (1920, 1080), (960, 540), (853, 480), (640, 480)] {
            let frame = render(&ui, w, h);
            // Saved before the checks, so a failing size leaves its frame to look at.
            save(&frame, &format!("windows-{theme_name}-{w}x{h}"));
            inside(&ui, "New note", w, h);
            inside(&ui, "Search notes", w, h);
        }
    }

    // A dark contrast theme: the canvas must be the system window colour, and the controls still fit.
    let colors = nlk_native::contrast::SystemColors {
        window: 0x0000_0000,
        window_text: 0x00FF_FFFF,
        button_face: 0x0000_0000,
        button_text: 0x00FF_FFFF,
        highlight: 0x00FF_FF00,
        highlight_text: 0x0000_0000,
        gray_text: 0x0080_8080,
        hotlight: 0x0000_FFFF,
    };
    nlk_native::controller::apply_contrast(&ui, Some(colors));
    let frame = render(&ui, 1280, 720);
    inside(&ui, "New note", 1280, 720);
    let corner = frame.as_slice()[(frame.height() as usize - 2) * frame.width() as usize + frame.width() as usize - 2];
    assert_eq!((corner.r, corner.g, corner.b), (0, 0, 0), "the canvas uses the contrast theme's window colour");
    save(&frame, "windows-contrast-1280x720");
    nlk_native::controller::apply_contrast(&ui, None);

    std::fs::remove_dir_all(dir).unwrap();
}
