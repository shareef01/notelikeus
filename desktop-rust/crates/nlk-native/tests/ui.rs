//! The native window driven headlessly (Slint's testing backend), through the same accessible labels a
//! screen reader uses: the controller, the Slint UI and the encrypted store together.

use std::path::{Path, PathBuf};

use i_slint_backend_testing::ElementHandle;
use nlk_native::AppWindow;
use nlk_native::controller::{Controller, Mode};
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
    // Tall enough that the Filters sheet needs no scrolling: the element search skips what is scrolled out
    // of view, as it is not visible.
    slint::ComponentHandle::window(&ui).set_size(slint::LogicalSize::new(1280.0, 1600.0));
    // Pictures load in the background; with no event loop here, jobs run inline instead.
    let controller = Controller::attach_with(&ui, service, Mode::Inline);
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

fn all_cards(ui: &AppWindow) -> Vec<nlk_native::NoteCard> {
    use slint::Model;
    ui.get_pinned_columns()
        .iter()
        .chain(ui.get_columns().iter())
        .flat_map(|column| column.cards.iter().collect::<Vec<_>>())
        .collect()
}

fn card_titles(ui: &AppWindow) -> Vec<String> {
    all_cards(ui).into_iter().map(|card| card.title.to_string()).collect()
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
    search_views_and_saved_filters();
    reviewing_a_conflicted_copy();
    pictures_on_notes();
    appearance_density_sort_and_sidebar_persistence();
    pinned_others_sections_and_sort_orders();
    markdown_formatting_and_safe_href_validation();
    dual_content_and_checklist_preserved_across_ui_edits();
    diagnostics_dialog_and_non_leak_check();
    cloud_wipe_banner_and_modal_shortcut_gating();
}

fn appearance_density_sort_and_sidebar_persistence() {
    let dir = temp_dir("settings-persist");
    let (ui, _c) = open(&dir);
    press(&ui, "Settings");
    assert!(ui.get_settings_open());
    press(&ui, "Dark");
    press(&ui, "Blue");
    tick(&ui, "AMOLED black");
    press(&ui, "Done");
    assert!(!ui.get_settings_open());
    assert_eq!((ui.get_theme_mode(), ui.get_theme_accent(), ui.get_theme_amoled()), (2, 1, true));

    press(&ui, "Collapse sidebar");
    assert!(ui.get_sidebar_collapsed());
    press(&ui, "List view");
    assert_eq!(ui.get_view_density(), 1);
    press(&ui, "Compact view");
    assert_eq!(ui.get_view_density(), 2);
    press(&ui, "Oldest");
    assert_eq!(ui.get_sort_order(), 2);

    drop(ui);
    let (ui, _c) = open(&dir);
    assert_eq!(ui.get_theme_mode(), 2);
    assert_eq!(ui.get_theme_accent(), 1);
    assert!(ui.get_theme_amoled());
    assert!(ui.get_sidebar_collapsed());
    assert_eq!(ui.get_view_density(), 2);
    assert_eq!(ui.get_sort_order(), 2);
    std::fs::remove_dir_all(dir).unwrap();
}

fn pinned_others_sections_and_sort_orders() {
    use slint::Model;
    let dir = temp_dir("pinned-sections");
    let (ui, _c) = open(&dir);
    for title in ["First", "Second", "Third"] {
        press(&ui, "New note");
        type_note(&ui, title, "body");
        press(&ui, "Back to notes");
    }
    press(&ui, "First");
    press(&ui, "Pin");
    press(&ui, "Back to notes");

    assert!(ui.get_has_pinned_section());
    assert!(ui.get_has_other_section());
    let pinned: Vec<String> = ui
        .get_pinned_columns()
        .iter()
        .flat_map(|c| c.cards.iter().map(|card| card.title.to_string()).collect::<Vec<_>>())
        .collect();
    assert_eq!(pinned, ["First"]);

    press(&ui, "Newest");
    let others_newest: Vec<String> = ui
        .get_columns()
        .iter()
        .flat_map(|c| c.cards.iter().map(|card| card.title.to_string()).collect::<Vec<_>>())
        .collect();
    assert_eq!(others_newest, ["Third", "Second"]);

    press(&ui, "Oldest");
    let others_oldest: Vec<String> = ui
        .get_columns()
        .iter()
        .flat_map(|c| c.cards.iter().map(|card| card.title.to_string()).collect::<Vec<_>>())
        .collect();
    assert_eq!(others_oldest, ["Second", "Third"]);
    std::fs::remove_dir_all(dir).unwrap();
}

fn markdown_formatting_and_safe_href_validation() {
    let dir = temp_dir("markdown-format");
    let (ui, _c) = open(&dir);
    press(&ui, "New note");
    type_note(&ui, "Formatted", "hello world");

    let hello_len = "hello ".len() as i32;
    let world_end = "hello world".len() as i32;
    ui.invoke_format_bold(hello_len, world_end);
    assert_eq!(ui.get_editor_body(), "hello **world**");
    ui.invoke_format_italic(0, "hello".len() as i32);
    assert_eq!(ui.get_editor_body(), "_hello_ **world**");

    ui.invoke_open_link_dialog(0, 0);
    assert!(ui.get_link_dialog_open());
    ui.set_link_label_text("Bad".into());
    ui.set_link_url_text("javascript:alert(1)".into());
    ui.invoke_insert_link_submitted();
    assert!(ui.get_link_dialog_open(), "unsafe scheme keeps the dialog open");
    assert!(!ui.get_link_error().is_empty());

    ui.set_link_label_text("Docs".into());
    ui.set_link_url_text("example.com".into());
    ui.invoke_insert_link_submitted();
    assert!(!ui.get_link_dialog_open());
    assert!(ui.get_editor_body().contains("[Docs](https://example.com)"));

    ui.invoke_format_bullet(0, 0);
    assert!(ui.get_editor_body().starts_with("• "));
    press(&ui, "Back to notes");

    let card = first_card(&ui);
    assert!(!card.preview.contains("**"));
    assert!(!card.preview.contains("https://example.com"));
    assert!(card.preview.contains("Docs"));
    assert!(card.preview.contains("hello"));
    assert!(card.preview.contains("world"));
    std::fs::remove_dir_all(dir).unwrap();
}

fn dual_content_and_checklist_preserved_across_ui_edits() {
    let dir = temp_dir("dual-content");
    {
        let mut service =
            NotesService::open(&dir, Box::new(PlaintextKeyProtectorForTests), Box::new(SystemIds), None).unwrap();
        let backup = serde_json::json!({
            "version": 3,
            "exportedAt": 1_767_225_600_000_i64,
            "app": "Notelikeus",
            "appVersion": "1.0.0",
            "labels": [],
            "notes": [{
                "id": 1,
                "title": "Dual Note",
                "content": "preserved underlying prose",
                "timestamp": 1_767_225_000_000_i64,
                "color": 0,
                "isPinned": false,
                "isArchived": false,
                "isTrashed": false,
                "position": 0,
                "labels": [],
                "checklist": [{ "text": "item one", "isChecked": false, "position": 0 }]
            }]
        });
        service.import_backup_v3(backup.to_string().as_bytes()).unwrap();
    }
    let (ui, _c) = open(&dir);
    press(&ui, "Dual Note");
    assert!(ui.get_editor_is_checklist());
    ui.set_editor_title("Updated dual title".into());
    ui.invoke_editor_edited();
    use slint::Model;
    let first_pos = ui.get_editor_rows().row_data(0).unwrap().position;
    ui.invoke_item_toggled(first_pos);
    press(&ui, "Back to notes");
    drop(ui);

    let service = NotesService::open(&dir, Box::new(PlaintextKeyProtectorForTests), Box::new(SystemIds), None).unwrap();
    let note = service.list().into_iter().find(|n| n.title == "Updated dual title").unwrap();
    assert_eq!(note.content, "preserved underlying prose");
    assert!(note.checklist[0].is_checked);
    std::fs::remove_dir_all(dir).unwrap();
}

fn diagnostics_dialog_and_non_leak_check() {
    let dir = temp_dir("diagnostics-ui");
    let (ui, _c) = open(&dir);
    press(&ui, "New note");
    type_note(&ui, "schemaVersion", "my private secret note");
    press(&ui, "Back to notes");

    press(&ui, "Settings");
    press(&ui, "View diagnostics");
    assert!(ui.get_diagnostics_open());
    let report = ui.get_diagnostics_report().to_string();
    let parsed: serde_json::Value = serde_json::from_str(&report).expect("valid diagnostics json");
    assert_eq!(parsed["schemaVersion"], 1);
    assert!(parsed["sync"]["wipeGeneration"].is_number());
    assert!(!report.contains("my private secret note"));
    ui.invoke_diagnostics_copied();
    assert_eq!(ui.get_toast_text(), "Diagnostics copied to clipboard");
    std::fs::remove_dir_all(dir).unwrap();
}

fn send_escape(ui: &AppWindow) {
    let window = slint::ComponentHandle::window(ui);
    window.dispatch_event(slint::platform::WindowEvent::KeyPressed { text: slint::platform::Key::Escape.into() });
    window.dispatch_event(slint::platform::WindowEvent::KeyReleased { text: slint::platform::Key::Escape.into() });
}

fn send_ctrl_n(ui: &AppWindow) {
    let window = slint::ComponentHandle::window(ui);
    window.dispatch_event(slint::platform::WindowEvent::KeyPressed { text: slint::platform::Key::Control.into() });
    window.dispatch_event(slint::platform::WindowEvent::KeyPressed { text: "n".into() });
    window.dispatch_event(slint::platform::WindowEvent::KeyReleased { text: "n".into() });
    window.dispatch_event(slint::platform::WindowEvent::KeyReleased { text: slint::platform::Key::Control.into() });
}

fn cloud_wipe_banner_and_modal_shortcut_gating() {
    let dir = temp_dir("wipe-banner-modals");
    let (ui, _c) = open(&dir);
    ui.set_account_mode(2);
    ui.set_uploads_blocked_after_wipe(true);
    assert!(has(&ui, "Re-upload local notes"));

    press(&ui, "Settings");
    assert!(ui.get_settings_open());
    send_ctrl_n(&ui);
    assert!(!ui.get_editor_open(), "Ctrl+N must be gated while a modal is open");

    press(&ui, "View diagnostics");
    assert!(ui.get_diagnostics_open());
    send_escape(&ui);
    assert!(!ui.get_diagnostics_open(), "Escape dismisses topmost diagnostics dialog first");
    assert!(ui.get_settings_open(), "underlying settings dialog remains open");
    send_escape(&ui);
    assert!(!ui.get_settings_open());

    ui.invoke_focus_main();
    send_ctrl_n(&ui);
    assert!(ui.get_editor_open(), "Ctrl+N works when no modal is open");
    std::fs::remove_dir_all(dir).unwrap();
}

fn reviewing_a_conflicted_copy() {
    use slint::Model;
    let dir = temp_dir("review");
    let (ui, _controller) = open(&dir);
    assert_eq!(ui.get_account_mode(), 0, "a build without sync says notes stay on this device");
    for (title, body) in [("Groceries", "milk\neggs"), ("Groceries (conflicted copy)", "milk\nbread")] {
        press(&ui, "New note");
        type_note(&ui, title, body);
        press(&ui, "Back to notes");
    }

    // An ordinary note has no banner; the copy has one, and its comparison.
    press(&ui, "Groceries");
    assert!(!ui.get_editor_conflict());
    press(&ui, "Back to notes");
    press(&ui, "Groceries (conflicted copy)");
    assert!(ui.get_editor_conflict());
    press(&ui, "Compare with the current note");
    assert!(ui.get_review_open());
    assert!(ui.get_review_has_original());
    let lines = |rows: slint::ModelRc<nlk_native::ReviewRow>| {
        rows.iter().map(|r| (r.text.to_string(), r.changed)).collect::<Vec<_>>()
    };
    assert_eq!(lines(ui.get_review_original_lines()), [("milk".to_string(), false), ("eggs".to_string(), true)]);
    assert_eq!(lines(ui.get_review_copy_lines()), [("milk".to_string(), false), ("bread".to_string(), true)]);

    // "Decide later" changes nothing; keeping the copy trashes the original and takes its title.
    press(&ui, "Decide later");
    assert!(!ui.get_review_open() && ui.get_editor_open());
    press(&ui, "Compare with the current note");
    press(&ui, "Keep conflicted copy");
    assert!(!ui.get_editor_open());
    assert_eq!(ui.get_toast_text(), "Kept the copy. The other version is in Trash.");
    assert_eq!(card_titles(&ui), ["Groceries"]);
    assert_eq!(ui.get_count_trash(), 1, "the other version can still be restored");
    press(&ui, "Groceries");
    assert_eq!(ui.get_editor_body(), "milk\nbread");
    std::fs::remove_dir_all(dir).unwrap();
}

fn search(ui: &AppWindow, text: &str) {
    ui.set_query(text.into());
    ui.invoke_query_edited(text.into());
}

fn sorted_titles(ui: &AppWindow) -> Vec<String> {
    let mut titles = card_titles(ui);
    titles.sort();
    titles
}

fn search_views_and_saved_filters() {
    use slint::Model;
    let dir = temp_dir("search");
    let (ui, _controller) = open(&dir);
    press(&ui, "New note");
    type_note(&ui, "Quarterly report", "slides for monday");
    ui.invoke_autosave_due();
    press(&ui, "Note options");
    ui.invoke_label_created("Work".into());
    press(&ui, "Done");
    press(&ui, "Back to notes");
    press(&ui, "New note");
    type_note(&ui, "Packing", "passport\ncharger");
    press(&ui, "Make a checklist");
    press(&ui, "Back to notes");
    press(&ui, "New note");
    type_note(&ui, "Receipts", "scanned");
    ui.invoke_autosave_due();
    press(&ui, "Archive note");

    // Operators narrow; what the box cannot use is named; a typo shows near matches and says so.
    search(&ui, "label:work");
    assert_eq!(card_titles(&ui), ["Quarterly report"]);
    search(&ui, "in:all");
    assert_eq!(sorted_titles(&ui), ["Packing", "Quarterly report", "Receipts"]);
    search(&ui, "slides is:nonsense");
    assert_eq!(card_titles(&ui), ["Quarterly report"]);
    assert_eq!(ui.get_notice_ignored(), "Ignored: is:nonsense");
    search(&ui, "quartrly");
    assert_eq!(card_titles(&ui), ["Quarterly report"]);
    assert!(ui.get_notice_fuzzy().starts_with("No exact match for “quartrly”"));
    search(&ui, "");
    assert_eq!(ui.get_notice_fuzzy(), "");

    // A smart view is its search, typed into the box; Notes leaves it.
    let views: Vec<(String, i32)> = ui.get_smart_views().iter().map(|r| (r.label.to_string(), r.count)).collect();
    assert_eq!(views, [("Reminders".into(), 0), ("Unfinished".into(), 1), ("Unlabelled".into(), 1)]);
    press(&ui, "Unfinished");
    assert_eq!(ui.get_query(), "has:unchecked");
    assert_eq!(card_titles(&ui), ["Packing"]);
    assert!(!ui.get_library_current(), "the view, not Notes, is what is on screen");
    press(&ui, "Notes");
    assert_eq!(ui.get_query(), "");
    assert!(ui.get_library_current());

    // The Filters sheet: choices apply at once, and can be saved by name.
    press(&ui, "Filters");
    assert!(ui.get_filters_open());
    tick(&ui, "Has checklist");
    assert_eq!(card_titles(&ui), ["Packing"]);
    assert_eq!(ui.get_filter_count(), 1);
    press(&ui, "Save these filters");
    assert!(ui.get_naming());
    ui.set_filter_name("Lists".into());
    press(&ui, "Save");
    assert!(!ui.get_naming());
    assert_eq!(ui.get_saved_filters().iter().map(|r| r.label.to_string()).collect::<Vec<_>>(), ["Lists"]);
    assert!(ui.get_saved_filters().row_data(0).unwrap().current, "what is on screen is the saved filter");
    // Saving under a taken name says it replaces it.
    press(&ui, "Save these filters");
    ui.invoke_filter_name_edited("LISTS".into());
    assert_eq!(ui.get_filter_name_hint(), "Replaces the filter already saved as “Lists”");
    ui.set_naming(false);

    press(&ui, "Clear all");
    assert_eq!(ui.get_filter_count(), 0);
    assert_eq!(sorted_titles(&ui), ["Packing", "Quarterly report"]);
    tick(&ui, "Today");
    assert_eq!(ui.get_note_count(), 2, "everything was edited today");
    tick(&ui, "Any time");
    tick(&ui, "Work");
    assert_eq!(card_titles(&ui), ["Quarterly report"]);
    press(&ui, "Show 1 note");
    assert!(!ui.get_filters_open());
    press(&ui, "Clear filters");
    assert_eq!(ui.get_note_count(), 2);

    // A saved filter comes back from the sidebar after a restart, and can be deleted there.
    drop(ui);
    let (ui, _controller) = open(&dir);
    press(&ui, "Lists");
    assert_eq!(card_titles(&ui), ["Packing"]);
    press(&ui, "Delete saved filter Lists");
    assert_eq!(ui.get_saved_filters().row_count(), 0);
    std::fs::remove_dir_all(dir).unwrap();
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
    // Narrator hears the pin as words, not only as an icon, and the accessible name stays the title.
    let card_description = |title: &str| {
        ElementHandle::find_by_accessible_label(&ui, title)
            .find(|e| e.accessible_description().is_some_and(|d| !d.is_empty()))
            .and_then(|e| e.accessible_description())
            .map(|d| d.to_string())
    };
    assert_eq!(card_description("Trip").as_deref(), Some("Pinned. pack"));
    assert_eq!(card_description("Groceries").as_deref(), Some("milk"));

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
    all_cards(ui).into_iter().next().expect("a card")
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

fn png(width: u32, height: u32) -> Vec<u8> {
    let picture = image::RgbaImage::from_pixel(width, height, image::Rgba([200, 80, 40, 255]));
    let mut bytes = Vec::new();
    picture.write_to(&mut std::io::Cursor::new(&mut bytes), image::ImageFormat::Png).unwrap();
    bytes
}

fn pictures_on_notes() {
    use slint::Model;
    let dir = temp_dir("pictures");
    let (ui, controller) = open(&dir);

    // A draft gets a picture: it becomes a note, and the picture shows in the strip and on its card.
    press(&ui, "New note");
    controller.add_image_bytes(&png(1200, 800));
    assert!(ui.get_editor_saved_once(), "the draft was saved so the picture has a note");
    let strip = ui.get_editor_pictures();
    assert_eq!(strip.row_count(), 1);
    assert!(strip.row_data(0).unwrap().ready, "decoded and shown");
    controller.add_image_bytes(b"%PDF-1.7 not a picture");
    assert!(ui.get_toast_text().contains("PNG, JPEG, GIF or WebP"), "{}", ui.get_toast_text());
    controller.add_image_bytes(&png(300, 300));
    assert_eq!(ui.get_editor_pictures().row_count(), 2);
    type_note(&ui, "Garden plan", "raised beds");
    press(&ui, "Back to notes");
    let card = first_card(&ui);
    assert!(card.has_cover && card.cover.ready, "the first picture leads the card");
    assert_eq!(card.more_images, 1);

    // The viewer: both pictures, one step apart; removing one asks first.
    press(&ui, "Garden plan");
    press(&ui, "Picture 1 of 2");
    assert!(ui.get_viewer_open());
    assert!(ui.get_viewer_picture().ready);
    assert_eq!((ui.get_viewer_index(), ui.get_viewer_count()), (0, 2));
    press(&ui, "Next picture");
    assert_eq!(ui.get_viewer_index(), 1);
    press(&ui, "Next picture");
    assert_eq!(ui.get_viewer_index(), 0, "round to the first");
    press(&ui, "Remove image");
    assert_eq!(ui.get_confirm_title(), "Remove this image?");
    ui.invoke_confirm_accepted();
    assert!(!ui.get_viewer_open());
    assert_eq!(ui.get_toast_text(), "Image removed");
    assert_eq!(ui.get_editor_pictures().row_count(), 1);
    press(&ui, "Back to notes");

    // A restart keeps the picture; a trashed note's pictures can be looked at, not changed.
    drop(ui);
    let (ui, _controller) = open(&dir);
    press(&ui, "Garden plan");
    assert_eq!(ui.get_editor_pictures().row_count(), 1);
    press(&ui, "Move to trash");
    press(&ui, "Trash");
    press(&ui, "Garden plan");
    assert!(!has(&ui, "Add image"));
    press(&ui, "Picture 1 of 1");
    assert!(ui.get_viewer_open() && ui.get_viewer_read_only());
    assert!(!has(&ui, "Remove image"));
    std::fs::remove_dir_all(dir).unwrap();
}
