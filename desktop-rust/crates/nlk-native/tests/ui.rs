//! The native window driven headlessly (Slint's testing backend), through the same accessible labels a
//! screen reader uses: the controller, the Slint UI and the encrypted store together.

use std::path::{Path, PathBuf};

use i_slint_backend_testing::ElementHandle;
use nlk_native::AppWindow;
use nlk_native::controller::Controller;
use notelikeus_app::{NotesService, SystemIds};
use notelikeus_core::crypto::PlaintextKeyProtectorForTests;

fn temp_dir(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("nlk-native-ui-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    dir
}

fn open(dir: &Path) -> (AppWindow, std::rc::Rc<Controller>) {
    let service = NotesService::open(dir, Box::new(PlaintextKeyProtectorForTests), Box::new(SystemIds), None).unwrap();
    let ui = AppWindow::new().unwrap();
    let controller = Controller::attach(&ui, service);
    (ui, controller)
}

fn press(ui: &AppWindow, label: &str) {
    let element = ElementHandle::find_by_accessible_label(ui, label)
        .next()
        .unwrap_or_else(|| panic!("no element labelled {label:?}"));
    element.invoke_accessible_default_action();
}

fn has(ui: &AppWindow, label: &str) -> bool {
    ElementHandle::find_by_accessible_label(ui, label).next().is_some()
}

/// Types into the editor the way the window does: the text, then its `edited` callback.
fn type_note(ui: &AppWindow, title: &str, body: &str) {
    ui.set_editor_title(title.into());
    ui.set_editor_body(body.into());
    ui.invoke_editor_edited();
}

fn card_titles(ui: &AppWindow) -> Vec<String> {
    use slint::Model;
    let mut titles = Vec::new();
    for column in ui.get_columns().iter() {
        for card in column.cards.iter() {
            titles.push(card.title.to_string());
        }
    }
    titles
}

fn stored_titles(dir: &Path) -> Vec<String> {
    let service = NotesService::open(dir, Box::new(PlaintextKeyProtectorForTests), Box::new(SystemIds), None).unwrap();
    service.list().into_iter().map(|n| n.title).collect()
}

/// One test function: Slint's testing platform is set once per process.
#[test]
fn the_native_window_end_to_end() {
    i_slint_backend_testing::init_no_event_loop();
    creating_editing_and_reopening();
    a_draft_left_empty_is_not_a_note();
    pin_search_delete_and_undo();
}

fn creating_editing_and_reopening() {
    let dir = temp_dir("create");
    let (ui, _controller) = open(&dir);
    assert_eq!(ui.get_note_count(), 0, "a new install starts empty");

    press(&ui, "New note");
    assert!(ui.get_editor_open(), "New note opens the editor");
    assert!(!has(&ui, "Delete note"), "a draft that was never saved has nothing to delete");
    type_note(&ui, "Groceries", "Oat milk, eggs");
    press(&ui, "Back to notes");
    assert!(!ui.get_editor_open());
    assert_eq!(card_titles(&ui), ["Groceries"], "closing the editor saves the draft");
    assert_eq!(stored_titles(&dir), ["Groceries"], "and it is on disk");

    // Open it from its card, change it, and the change is saved too.
    press(&ui, "Groceries");
    assert!(ui.get_editor_open());
    assert_eq!(ui.get_editor_body(), "Oat milk, eggs");
    assert!(ui.get_editor_footer().starts_with("Saved on this device"));
    type_note(&ui, "Groceries", "Oat milk, eggs, sourdough");
    press(&ui, "Back to notes");

    // A restart shows the same notes.
    drop(ui);
    let (ui, _controller) = open(&dir);
    assert_eq!(card_titles(&ui), ["Groceries"]);
    press(&ui, "Groceries");
    assert_eq!(ui.get_editor_body(), "Oat milk, eggs, sourdough");
    std::fs::remove_dir_all(dir).unwrap();
}

fn a_draft_left_empty_is_not_a_note() {
    let dir = temp_dir("empty-draft");
    let (ui, _controller) = open(&dir);
    press(&ui, "New note");
    type_note(&ui, "", "");
    press(&ui, "Back to notes");
    assert_eq!(ui.get_note_count(), 0);
    assert!(stored_titles(&dir).is_empty());
    std::fs::remove_dir_all(dir).unwrap();
}

fn pin_search_delete_and_undo() {
    let dir = temp_dir("pin-delete");
    let (ui, _controller) = open(&dir);
    for (title, body) in [("Trip", "pack"), ("Groceries", "milk"), ("Reading list", "books")] {
        press(&ui, "New note");
        type_note(&ui, title, body);
        press(&ui, "Back to notes");
    }
    assert_eq!(ui.get_note_count(), 3);

    // Pinning the oldest note puts it first.
    press(&ui, "Trip");
    press(&ui, "Pin");
    assert!(ui.get_editor_pinned());
    assert!(has(&ui, "Unpin"), "the button now offers the opposite");
    press(&ui, "Back to notes");
    assert_eq!(card_titles(&ui).first().map(String::as_str), Some("Trip"));

    // Search narrows the grid, and says so when nothing matches.
    ui.set_query("milk".into());
    ui.invoke_query_edited("milk".into());
    assert_eq!(card_titles(&ui), ["Groceries"]);
    ui.set_query("nothing like this".into());
    ui.invoke_query_edited("nothing like this".into());
    assert_eq!(ui.get_note_count(), 0);
    assert!(ui.get_searching());
    ui.set_query("".into());
    ui.invoke_query_edited("".into());

    // Delete, then Undo brings the words back.
    press(&ui, "Groceries");
    press(&ui, "Delete note");
    assert!(!ui.get_editor_open());
    assert!(!card_titles(&ui).contains(&"Groceries".to_string()));
    assert_eq!(ui.get_toast_text(), "Deleted \"Groceries\"");
    press(&ui, "Undo");
    assert!(card_titles(&ui).contains(&"Groceries".to_string()), "Undo restores the note");
    assert_eq!(ui.get_toast_text(), "", "and the toast goes");
    let mut stored = stored_titles(&dir);
    stored.sort();
    assert_eq!(stored, ["Groceries", "Reading list", "Trip"]);
    std::fs::remove_dir_all(dir).unwrap();
}
