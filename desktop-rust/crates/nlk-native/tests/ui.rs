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

fn checkbox_handle(ui: &AppWindow, label: &str) -> Option<ElementHandle> {
    ElementHandle::find_by_accessible_label(ui, label).find(|e| e.accessible_checkable() == Some(true))
}

/// Whether the checkbox labelled `label` is ticked (`None` when there is none).
fn checkbox(ui: &AppWindow, label: &str) -> Option<bool> {
    checkbox_handle(ui, label).and_then(|e| e.accessible_checked())
}

fn tick(ui: &AppWindow, label: &str) {
    checkbox_handle(ui, label)
        .unwrap_or_else(|| panic!("no checkbox labelled {label:?}"))
        .invoke_accessible_default_action();
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
    archive_trash_restore_and_empty();
    colours_and_labels();
    checklists();
}

fn creating_editing_and_reopening() {
    let dir = temp_dir("create");
    let (ui, _controller) = open(&dir);
    assert_eq!(ui.get_note_count(), 0, "a new install starts empty");

    press(&ui, "New note");
    assert!(ui.get_editor_open(), "New note opens the editor");
    assert!(!has(&ui, "Move to trash"), "a draft that was never saved has nothing to trash");
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

    // Move to trash, then Undo brings it back.
    press(&ui, "Groceries");
    press(&ui, "Move to trash");
    assert!(!ui.get_editor_open());
    assert!(!card_titles(&ui).contains(&"Groceries".to_string()));
    assert_eq!(ui.get_toast_text(), "Note moved to trash");
    assert_eq!(ui.get_count_trash(), 1);
    press(&ui, "Undo");
    assert!(card_titles(&ui).contains(&"Groceries".to_string()), "Undo restores the note");
    assert_eq!(ui.get_toast_text(), "", "and the toast goes");
    assert_eq!(ui.get_count_trash(), 0);
    let mut stored = stored_titles(&dir);
    stored.sort();
    assert_eq!(stored, ["Groceries", "Reading list", "Trip"]);
    std::fs::remove_dir_all(dir).unwrap();
}

fn titles_in(ui: &AppWindow, view: &str) -> Vec<String> {
    press(ui, view);
    card_titles(ui)
}

fn archive_trash_restore_and_empty() {
    let dir = temp_dir("archive-trash");
    let (ui, _controller) = open(&dir);
    for title in ["Old receipts", "Passwords hint", "Draft speech"] {
        press(&ui, "New note");
        type_note(&ui, title, "words");
        press(&ui, "Back to notes");
    }

    // Archive leaves Notes and shows up in Archive, where it can be unarchived.
    press(&ui, "Old receipts");
    press(&ui, "Archive note");
    assert!(!ui.get_editor_open());
    assert_eq!(ui.get_toast_text(), "Note archived");
    assert!(!card_titles(&ui).contains(&"Old receipts".to_string()));
    assert_eq!(titles_in(&ui, "Archive"), ["Old receipts"]);
    assert_eq!((ui.get_count_notes(), ui.get_count_archive()), (2, 1));
    press(&ui, "Old receipts");
    press(&ui, "Unarchive note");
    assert!(card_titles(&ui).is_empty(), "the archive is empty again");
    assert!(titles_in(&ui, "Notes").contains(&"Old receipts".to_string()));

    // A trashed note opens read-only: Restore or Delete forever, nothing else.
    press(&ui, "Passwords hint");
    press(&ui, "Move to trash");
    press(&ui, "Draft speech");
    press(&ui, "Move to trash");
    assert_eq!(ui.get_count_trash(), 2);
    let mut trashed = titles_in(&ui, "Trash");
    trashed.sort();
    assert_eq!(trashed, ["Draft speech", "Passwords hint"]);
    assert!(has(&ui, "Empty trash"), "the trash banner offers Empty trash");
    press(&ui, "Draft speech");
    assert!(has(&ui, "Restore") && has(&ui, "Delete forever"));
    assert!(!has(&ui, "Pin") && !has(&ui, "Note options") && !has(&ui, "Move to trash"));
    press(&ui, "Restore");
    assert_eq!(card_titles(&ui), ["Passwords hint"]);

    // Delete forever asks first; Cancel keeps the note.
    press(&ui, "Passwords hint");
    press(&ui, "Delete forever");
    assert_eq!(ui.get_confirm_title(), "Delete forever?");
    ui.invoke_confirm_cancelled();
    assert_eq!(ui.get_confirm_title(), "");
    assert_eq!(ui.get_count_trash(), 1);
    press(&ui, "Back to notes");

    // Empty trash asks too, then removes every trashed note for good.
    press(&ui, "Empty trash");
    assert_eq!(ui.get_confirm_title(), "Empty trash?");
    assert!(ui.get_confirm_body().starts_with("1 note will be deleted"));
    ui.invoke_confirm_accepted();
    assert_eq!(ui.get_count_trash(), 0);
    assert!(card_titles(&ui).is_empty());
    let mut stored = stored_titles(&dir);
    stored.sort();
    assert_eq!(stored, ["Draft speech", "Old receipts"], "the emptied note is gone from disk");
    std::fs::remove_dir_all(dir).unwrap();
}

fn first_card(ui: &AppWindow) -> nlk_native::NoteCard {
    use slint::Model;
    ui.get_columns().iter().flat_map(|c| c.cards.iter().collect::<Vec<_>>()).next().expect("a card")
}

fn colours_and_labels() {
    let dir = temp_dir("colours-labels");
    let (ui, _controller) = open(&dir);
    press(&ui, "New note");
    type_note(&ui, "Garden", "tomatoes");
    // What the autosave timer does once typing stops (the test has no event loop to run it).
    ui.invoke_autosave_due();
    assert!(ui.get_editor_saved_once());

    // Note options: a colour, then a new label that goes on the note at once.
    press(&ui, "Note options");
    assert!(ui.get_options_open());
    press(&ui, "Green");
    assert!(ui.get_editor_has_color());
    ui.set_new_label("  Home  ".into());
    ui.invoke_label_created(ui.get_new_label());
    assert_eq!(ui.get_new_label(), "", "the field clears");
    use slint::Model;
    assert_eq!(ui.get_editor_labels().iter().map(|l| l.to_string()).collect::<Vec<_>>(), ["Home"]);
    // The name was tidied; its checkbox in the sheet is ticked, and unticking takes it off.
    assert_eq!(checkbox(&ui, "Home"), Some(true));
    tick(&ui, "Home");
    assert_eq!(ui.get_editor_labels().row_count(), 0);
    assert_eq!(checkbox(&ui, "Home"), Some(false));
    // Creating a label that exists under another case reuses it.
    ui.invoke_label_created("HOME".into());
    assert_eq!(ui.get_editor_labels().iter().map(|l| l.to_string()).collect::<Vec<_>>(), ["Home"]);
    press(&ui, "Done");
    press(&ui, "Back to notes");

    let card = first_card(&ui);
    assert!(card.has_color, "the card shows the colour");
    assert_eq!(card.labels.iter().map(|l| l.to_string()).collect::<Vec<_>>(), ["Home"]);

    // Edit labels renames and deletes everywhere; deleting a label keeps its notes.
    press(&ui, "Edit labels");
    assert!(ui.get_labels_open());
    ui.invoke_label_renamed("Home".into(), "House".into());
    assert_eq!(first_card(&ui).labels.iter().map(|l| l.to_string()).collect::<Vec<_>>(), ["House"]);
    press(&ui, "Delete label House");
    assert_eq!(first_card(&ui).labels.row_count(), 0);
    assert_eq!(card_titles(&ui), ["Garden"]);
    ui.set_labels_open(false);

    // A restart keeps the colour.
    drop(ui);
    let (ui, _controller) = open(&dir);
    assert!(first_card(&ui).has_color);
    std::fs::remove_dir_all(dir).unwrap();
}

fn checklists() {
    use slint::Model;
    let dir = temp_dir("checklists");
    let (ui, _controller) = open(&dir);
    press(&ui, "New note");
    type_note(&ui, "Packing", "passport\n\n  charger \n");

    // Text to checklist: each non-empty line becomes an unticked item.
    press(&ui, "Make a checklist");
    assert!(ui.get_editor_is_checklist());
    let rows =
        |ui: &AppWindow| ui.get_editor_rows().iter().map(|r| (r.text.to_string(), r.checked)).collect::<Vec<_>>();
    assert_eq!(rows(&ui), [("passport".into(), false), ("charger".into(), false)]);

    // Ticking moves an item below the unticked ones; adding focuses a new empty row.
    let passport = ui.get_editor_rows().row_data(0).unwrap().position;
    ui.invoke_item_toggled(passport);
    assert_eq!(rows(&ui), [("charger".into(), false), ("passport".into(), true)]);
    press(&ui, "+ List item");
    let added = ui.get_editor_rows().iter().find(|r| r.text.is_empty()).expect("an empty row");
    assert!(added.focus);
    ui.invoke_item_edited(added.position, "socks".into());
    press(&ui, "Back to notes");

    let card = first_card(&ui);
    assert_eq!(card.preview, "", "a checklist card shows items, not text");
    assert_eq!(
        card.checklist.iter().map(|l| (l.text.to_string(), l.checked)).collect::<Vec<_>>(),
        [("charger".into(), false), ("socks".into(), false), ("passport".into(), true)]
    );

    // Remove an item, then back to text: the ticks are dropped.
    press(&ui, "Packing");
    press(&ui, "Remove charger");
    press(&ui, "Convert to text");
    assert!(!ui.get_editor_is_checklist());
    let mut lines = ui.get_editor_body().lines().map(str::to_string).collect::<Vec<_>>();
    lines.sort();
    assert_eq!(lines, ["passport", "socks"]);
    press(&ui, "Back to notes");
    assert_eq!(first_card(&ui).checklist.row_count(), 0);

    // An empty draft turned into a checklist gets one empty item, and is a note from then on.
    press(&ui, "New note");
    press(&ui, "Make a checklist");
    assert_eq!(rows(&ui), [(String::new(), false)]);
    press(&ui, "Back to notes");
    assert_eq!(ui.get_note_count(), 2);
    std::fs::remove_dir_all(dir).unwrap();
}
