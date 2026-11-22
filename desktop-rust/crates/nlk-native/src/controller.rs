//! Connects the window to the app logic: the views (Notes, Archive, Trash), the grid, the editor (autosave,
//! pin, colour, labels, checklists, archive and trash with Undo, delete forever), Edit labels, and search.
//! It saves before anything that would lose an edit: closing the editor or the window, opening another
//! note, converting between text and checklist.

use std::cell::RefCell;
use std::rc::{Rc, Weak};
use std::time::Duration;

use notelikeus_app::clock::LocalDays;
use notelikeus_app::query::{self, DateField, DatePreset, Filters, LabelMatch, NoteFlag, NoteScope, SMART_VIEWS};
use notelikeus_app::{ChecklistItem, NoteView, NotesService, label_key};
use notelikeus_core::palette;
use slint::{Color, ComponentHandle, ModelRc, SharedString, Timer, TimerMode, VecModel};

use crate::view;
use crate::{
    AppWindow, CardColumn, ChecklistLine, Choice, ChoiceRow, EditRow, LabelChoice, NoteCard, SidebarRow, Swatch, View,
};

const AUTOSAVE_AFTER: Duration = Duration::from_millis(400);
const TOAST_FOR: Duration = Duration::from_secs(6);
const CARD_LABELS: usize = 3;
const CARD_ITEMS: usize = 6;

/// The note in the editor: `id` is `None` until a draft's first save gives it one.
#[derive(Default)]
struct Editing {
    id: Option<String>,
    dirty: bool,
    /// The checklist as edited, for a checklist note (empty for text).
    checklist: Vec<ChecklistItem>,
    is_checklist: bool,
}

/// What the toast's Undo does.
enum Undo {
    Restore(String),
    Unarchive(String),
    Rearchive(String),
}

/// What the confirmation dialog will do when accepted.
enum Confirm {
    EmptyTrash,
    DeleteForever(String),
}

#[derive(Default)]
struct State {
    editing: Option<Editing>,
    undo: Option<Undo>,
    confirm: Option<Confirm>,
    /// What the grid shows: the sidebar's view, the search box and the Filters sheet, in one place.
    filters: Filters,
}

pub struct Controller {
    ui: slint::Weak<AppWindow>,
    service: RefCell<NotesService>,
    state: RefCell<State>,
    autosave: Timer,
    toast: Timer,
}

fn scope_of(view: View) -> NoteScope {
    match view {
        View::Notes => NoteScope::Active,
        View::Archive => NoteScope::Archive,
        View::Trash => NoteScope::Trash,
    }
}

fn view_of_scope(scope: NoteScope) -> View {
    match scope {
        NoteScope::Archive => View::Archive,
        NoteScope::Trash => View::Trash,
        NoteScope::Active | NoteScope::All => View::Notes,
    }
}

fn now_millis() -> i64 {
    chrono::Local::now().timestamp_millis()
}

/// Chips wrapped into rows that fit the Filters sheet; `index` is each chip's place in `items`.
fn chip_rows(items: Vec<(String, bool)>) -> Vec<ChoiceRow> {
    view::wrap_chips(&items.iter().map(|(t, _)| t.as_str()).collect::<Vec<_>>())
        .into_iter()
        .map(|range| ChoiceRow {
            items: model(
                range.map(|i| Choice { text: items[i].0.as_str().into(), on: items[i].1, index: i as i32 }).collect(),
            ),
        })
        .collect()
}

fn view_of(note: &NoteView) -> View {
    if note.is_trashed {
        View::Trash
    } else if note.is_archived {
        View::Archive
    } else {
        View::Notes
    }
}

fn argb(value: i32) -> Color {
    Color::from_argb_encoded(value as u32)
}

fn model<T: Clone + 'static>(items: Vec<T>) -> ModelRc<T> {
    ModelRc::new(VecModel::from(items))
}

/// Labels sorted as the web app shows them: by name, ignoring case.
fn sorted_labels(labels: &[String]) -> Vec<String> {
    let mut labels = labels.to_vec();
    labels.sort_by_key(|l| label_key(l));
    labels
}

/// Checklist items in the order the web app shows them: unticked first, then by position.
fn sorted_items(items: &[ChecklistItem]) -> Vec<ChecklistItem> {
    let mut items = items.to_vec();
    items.sort_by_key(|i| (i.is_checked, i.position));
    items
}

impl Controller {
    /// Wires every callback of `ui` and shows the notes. The window holds the controller alive.
    pub fn attach(ui: &AppWindow, service: NotesService) -> Rc<Controller> {
        let controller = Rc::new(Controller {
            ui: ui.as_weak(),
            service: RefCell::new(service),
            state: RefCell::default(),
            autosave: Timer::default(),
            toast: Timer::default(),
        });
        ui.set_swatches(model(
            palette::NOTE_COLORS
                .iter()
                .map(|c| Swatch { name: c.name.into(), none: c.light == 0, light: argb(c.light), dark: argb(c.dark) })
                .collect(),
        ));

        macro_rules! wire {
            ($setter:ident, |$c:ident $(, $arg:ident)*| $body:expr) => {{
                let weak: Weak<Controller> = Rc::downgrade(&controller);
                ui.$setter(move |$($arg),*| {
                    if let Some($c) = weak.upgrade() {
                        $body;
                    }
                });
            }};
        }
        wire!(on_new_note, |c| c.new_note());
        wire!(on_open_note, |c, id| c.open_note(&id));
        wire!(on_editor_edited, |c| c.edited());
        wire!(on_close_editor, |c| c.close_editor());
        wire!(on_toggle_pin, |c| c.toggle_pin());
        wire!(on_archive_toggled, |c| c.toggle_archive());
        wire!(on_trash_note, |c| c.trash_note());
        wire!(on_restore_note, |c| c.restore_note());
        wire!(on_delete_forever, |c| c.ask_delete_forever());
        wire!(on_empty_trash, |c| c.ask_empty_trash());
        wire!(on_confirm_accepted, |c| c.confirm_accepted());
        wire!(on_confirm_cancelled, |c| c.confirm_cancelled());
        wire!(on_view_selected, |c, view| c.select_view(view));
        wire!(on_open_options, |c| c.open_options());
        wire!(on_color_picked, |c, index, dark| c.pick_color(index as usize, dark));
        wire!(on_label_toggled, |c, name| c.toggle_label(&name));
        wire!(on_label_created, |c, name| c.create_label_for_note(&name));
        wire!(on_item_edited, |c, position, text| c.item_edited(position as i64, &text));
        wire!(on_item_toggled, |c, position| c.item_toggled(position as i64));
        wire!(on_item_removed, |c, position| c.item_removed(position as i64));
        wire!(on_item_added, |c| c.item_added());
        wire!(on_to_checklist, |c| c.to_checklist());
        wire!(on_to_text, |c| c.to_text());
        wire!(on_open_labels, |c| c.open_labels());
        wire!(on_labels_created, |c, name| c.labels_created(&name));
        wire!(on_label_renamed, |c, old, new| c.label_renamed(&old, &new));
        wire!(on_label_deleted, |c, name| c.label_deleted(&name));
        wire!(on_toast_acted, |c| c.undo());
        wire!(on_autosave_due, |c| c.save_now());
        wire!(on_toast_expired, |c| c.toast_expired());
        wire!(on_columns_changed, |c, _count| c.refresh());
        wire!(on_query_edited, |c, query| c.search_edited(&query));
        wire!(on_smart_view_picked, |c, index| c.smart_view(index as usize));
        wire!(on_saved_applied, |c, name| c.apply_saved(&name));
        wire!(on_saved_deleted, |c, name| c.delete_saved(&name));
        wire!(on_filter_color_toggled, |c, index| c.change_filters(|f| f.toggle_color(index as usize)));
        wire!(on_filter_label_toggled, |c, name| c.change_filters(|f| f.toggle_label(&name)));
        wire!(on_filter_label_match_toggled, |c| c.change_filters(|f| {
            f.label_match = if f.label_match == LabelMatch::All { LabelMatch::Any } else { LabelMatch::All }
        }));
        wire!(on_filter_flag_toggled, |c, index| c.change_filters(|f| {
            if let Some(flag) = NoteFlag::ALL.get(index as usize) {
                f.toggle_flag(*flag);
            }
        }));
        wire!(on_filter_date_field_picked, |c, index| c.change_filters(|f| {
            if let Some(field) = DateField::ALL.get(index as usize) {
                f.date_field = *field;
            }
        }));
        wire!(on_filter_preset_picked, |c, index| c.pick_preset(index as usize));
        wire!(on_filters_cleared, |c| c.clear_filters());
        wire!(on_filter_name_edited, |c, name| c.filter_name_edited(&name));
        wire!(on_filter_saved, |c, name| c.save_filter(&name));
        {
            let weak = Rc::downgrade(&controller);
            ui.window().on_close_requested(move || {
                if let Some(c) = weak.upgrade() {
                    c.save_now();
                }
                slint::CloseRequestResponse::HideWindow
            });
        }

        controller.refresh();
        controller
    }

    fn ui(&self) -> Option<AppWindow> {
        self.ui.upgrade()
    }

    fn editing_id(&self) -> Option<String> {
        self.state.borrow().editing.as_ref().and_then(|e| e.id.clone())
    }

    fn card(note: &NoteView, now: &chrono::DateTime<chrono::Local>) -> NoteCard {
        let labels = sorted_labels(&note.labels);
        let items = sorted_items(&note.checklist);
        NoteCard {
            id: note.id.as_str().into(),
            title: note.title.trim().into(),
            preview: if note.checklist.is_empty() { view::preview(&note.content).into() } else { SharedString::new() },
            time: view::card_time(note.timestamp, now).into(),
            pinned: note.is_pinned,
            has_color: note.color != 0,
            color_light: argb(palette::for_theme(note.color, false)),
            color_dark: argb(palette::for_theme(note.color, true)),
            labels: model(labels.iter().take(CARD_LABELS).map(|l| SharedString::from(l.as_str())).collect()),
            more_labels: labels.len().saturating_sub(CARD_LABELS) as i32,
            checklist: model(
                items
                    .iter()
                    .take(CARD_ITEMS)
                    .map(|i| ChecklistLine { text: i.text.as_str().into(), checked: i.is_checked })
                    .collect(),
            ),
            more_items: items.len().saturating_sub(CARD_ITEMS) as i32,
        }
    }

    /// Rebuilds everything the filters decide: the grid (laid out in as many columns as fit), the notices,
    /// the sidebar's counts, views and saved filters, and the Filters sheet's choices.
    pub fn refresh(&self) {
        let Some(ui) = self.ui() else { return };
        let filters = self.state.borrow().filters.clone();
        let now = now_millis();
        let service = self.service.borrow();
        let counts = service.counts();
        let found = service.search(&filters, now);
        let view_counts = service.smart_view_counts(now);
        let saved = service.saved_filters().to_vec();
        let labels = service.label_names();
        drop(service);

        ui.set_count_notes(counts.notes as i32);
        ui.set_count_archive(counts.archive as i32);
        ui.set_count_trash(counts.trash as i32);
        let local_now = chrono::Local::now();
        let shown = found.notes.len();
        let columns = view::masonry(found.notes, ui.get_column_count().max(1) as usize, view::card_height)
            .into_iter()
            .map(|column| CardColumn { cards: model(column.iter().map(|n| Self::card(n, &local_now)).collect()) })
            .collect::<Vec<_>>();
        ui.set_note_count(shown as i32);
        ui.set_searching(filters.has_active());
        ui.set_columns(model(columns));
        ui.set_notice_ignored(if found.unknown.is_empty() {
            SharedString::new()
        } else {
            format!("Ignored: {}", found.unknown.join(", ")).into()
        });
        ui.set_notice_fuzzy(if found.is_fuzzy {
            format!("No exact match for “{}” — showing near matches", found.search_text).into()
        } else {
            SharedString::new()
        });

        // The sidebar: which row is what is on screen.
        let current_view = query::active_smart_view(&filters);
        let current_saved = query::current_saved(&saved, &filters).map(|f| f.name.clone());
        ui.set_library_current(current_view.is_none() && current_saved.is_none());
        ui.set_smart_views(model(
            SMART_VIEWS
                .iter()
                .enumerate()
                .map(|(i, v)| SidebarRow {
                    label: v.label.into(),
                    count: view_counts[i] as i32,
                    current: current_view == Some(i),
                })
                .collect(),
        ));
        ui.set_saved_filters(model(
            saved
                .iter()
                .map(|f| SidebarRow {
                    label: f.name.as_str().into(),
                    count: 0,
                    current: current_saved.as_deref() == Some(f.name.as_str()),
                })
                .collect(),
        ));

        // The Filters sheet.
        let days = LocalDays::at(now);
        ui.set_filters_active(filters.has_active());
        ui.set_filter_count(filters.sheet_count() as i32);
        ui.set_filter_colors(model((0..palette::NOTE_COLORS.len()).map(|i| filters.colors.contains(&i)).collect()));
        ui.set_filter_label_rows(model(chip_rows(labels.iter().map(|l| (l.clone(), filters.has_label(l))).collect())));
        ui.set_filter_label_count(filters.label_names.len() as i32);
        ui.set_filter_label_all(filters.label_match == LabelMatch::All);
        ui.set_filter_flag_rows(model(chip_rows(
            NoteFlag::ALL.iter().map(|f| (f.label().to_string(), filters.flags.contains(f))).collect(),
        )));
        ui.set_filter_date_fields(model(
            DateField::ALL
                .iter()
                .enumerate()
                .map(|(i, f)| Choice { text: f.label().into(), on: filters.date_field == *f, index: i as i32 })
                .collect(),
        ));
        let preset = DatePreset::of(filters.date_range, &days);
        ui.set_filter_presets(model(
            DatePreset::ALL
                .iter()
                .enumerate()
                .map(|(i, p)| Choice { text: p.label().into(), on: preset == Some(*p), index: i as i32 })
                .collect(),
        ));
    }

    /// Puts `filters` on screen: the sidebar's view and the search box follow them.
    fn set_filters(&self, filters: Filters) {
        if let Some(ui) = self.ui() {
            ui.set_view(view_of_scope(filters.scope));
            if ui.get_query().as_str() != filters.search {
                ui.set_query(filters.search.as_str().into());
            }
        }
        self.state.borrow_mut().filters = filters;
        self.refresh();
    }

    fn change_filters(&self, change: impl FnOnce(&mut Filters)) {
        let mut filters = self.state.borrow().filters.clone();
        change(&mut filters);
        self.set_filters(filters);
    }

    /// Notes, Archive or Trash, keeping the other filters as the web app does; leaving a smart view leaves
    /// its search behind too.
    pub fn select_view(&self, view: View) {
        self.change_filters(|f| {
            if query::active_smart_view(f).is_some() {
                f.search.clear();
            }
            f.scope = scope_of(view);
        });
    }

    pub fn search_edited(&self, text: &str) {
        self.change_filters(|f| f.search = text.to_string());
    }

    /// A smart view is a destination, not a refinement: it replaces the other filters.
    pub fn smart_view(&self, index: usize) {
        let Some(view) = SMART_VIEWS.get(index) else { return };
        self.change_filters(|f| *f = view.filters(f));
    }

    pub fn apply_saved(&self, name: &str) {
        let saved = self.service.borrow().saved_filters().iter().find(|f| f.name == name).cloned();
        if let Some(saved) = saved {
            self.set_filters(saved.filters);
        }
    }

    pub fn delete_saved(&self, name: &str) {
        let deleted = self.service.borrow_mut().delete_saved_filter(name);
        match deleted {
            Ok(()) => self.show_toast(&format!("Deleted saved filter “{name}”"), None, None),
            Err(message) => self.show_toast(&message, None, None),
        }
        self.refresh();
    }

    pub fn pick_preset(&self, index: usize) {
        let Some(preset) = DatePreset::ALL.get(index).copied() else { return };
        let range = preset.range(&LocalDays::at(now_millis()));
        self.change_filters(|f| f.date_range = range);
    }

    pub fn clear_filters(&self) {
        self.change_filters(|f| *f = f.cleared());
    }

    /// What saving under `name` will do, said before it is done.
    pub fn filter_name_edited(&self, name: &str) {
        let Some(ui) = self.ui() else { return };
        let service = self.service.borrow();
        let saved = service.saved_filters();
        let hint = match query::find_saved(saved, name) {
            Some(existing) if !name.trim().is_empty() => {
                format!("Replaces the filter already saved as “{}”", existing.name)
            }
            _ if saved.len() >= query::SAVED_FILTER_MAX => {
                format!("{} saved already. Saving replaces the oldest.", saved.len())
            }
            _ => String::new(),
        };
        ui.set_filter_name_hint(hint.into());
    }

    pub fn save_filter(&self, name: &str) {
        let Some(ui) = self.ui() else { return };
        let filters = self.state.borrow().filters.clone();
        let saved = self.service.borrow_mut().save_filter(name, &filters);
        match saved {
            Ok(()) => {
                ui.set_naming(false);
                ui.set_filter_name("".into());
                self.show_toast(&format!("Saved “{}”", name.trim()), None, None);
            }
            Err(message) => self.show_toast(&message, None, None),
        }
        self.refresh();
    }

    fn saved_footer(timestamp: i64) -> SharedString {
        format!("Saved on this device · {}", view::card_time(timestamp, &chrono::Local::now())).into()
    }

    /// Shows the editor's colour, labels, checklist and flags for `note` (everything but the title and
    /// body text, which [`Controller::show_editor`] sets once so typing is never overwritten).
    fn show_note_details(&self, ui: &AppWindow, note: &NoteView) {
        ui.set_editor_mode(view_of(note));
        ui.set_editor_pinned(note.is_pinned);
        ui.set_editor_has_color(note.color != 0);
        ui.set_editor_color_light(argb(palette::for_theme(note.color, false)));
        ui.set_editor_color_dark(argb(palette::for_theme(note.color, true)));
        ui.set_editor_color_index(palette::index_of(note.color).map_or(-1, |i| i as i32));
        ui.set_editor_labels(model(
            sorted_labels(&note.labels).iter().map(|l| SharedString::from(l.as_str())).collect(),
        ));
        ui.set_editor_footer(Self::saved_footer(note.timestamp));
    }

    fn show_rows(&self, ui: &AppWindow, focus: Option<i64>) {
        let state = self.state.borrow();
        let Some(editing) = state.editing.as_ref() else { return };
        ui.set_editor_is_checklist(editing.is_checklist);
        ui.set_editor_rows(model(
            sorted_items(&editing.checklist)
                .iter()
                .map(|i| EditRow {
                    position: i.position as i32,
                    text: i.text.as_str().into(),
                    checked: i.is_checked,
                    focus: focus == Some(i.position),
                })
                .collect(),
        ));
    }

    fn show_editor(&self, ui: &AppWindow, note: Option<&NoteView>) {
        ui.set_editor_title(note.map(|n| n.title.as_str()).unwrap_or_default().into());
        ui.set_editor_body(note.map(|n| n.content.as_str()).unwrap_or_default().into());
        ui.set_editor_saved_once(note.is_some());
        match note {
            Some(n) => self.show_note_details(ui, n),
            None => {
                ui.set_editor_mode(View::Notes);
                ui.set_editor_pinned(false);
                ui.set_editor_has_color(false);
                ui.set_editor_color_index(0);
                ui.set_editor_labels(model(Vec::new()));
                ui.set_editor_footer("Not saved yet".into());
            }
        }
        self.show_rows(ui, None);
        ui.set_options_open(false);
        ui.set_editor_open(true);
    }

    pub fn new_note(&self) {
        self.save_now();
        let Some(ui) = self.ui() else { return };
        if self.state.borrow().filters.scope != NoteScope::Active {
            // A new note lands in Notes, so show it there.
            self.change_filters(|f| f.scope = NoteScope::Active);
        }
        self.state.borrow_mut().editing = Some(Editing::default());
        self.show_editor(&ui, None);
    }

    pub fn open_note(&self, id: &str) {
        self.save_now();
        let Some(ui) = self.ui() else { return };
        let Some(note) = self.service.borrow().note(id) else { return };
        self.state.borrow_mut().editing = Some(Editing {
            id: Some(note.id.clone()),
            dirty: false,
            is_checklist: !note.checklist.is_empty(),
            checklist: note.checklist.clone(),
        });
        self.show_editor(&ui, Some(&note));
    }

    /// Typing in the editor: save shortly after the typing stops.
    pub fn edited(&self) {
        if let Some(editing) = self.state.borrow_mut().editing.as_mut() {
            editing.dirty = true;
        }
        if let Some(ui) = self.ui() {
            ui.set_editor_footer("Saving…".into());
        }
        let weak = self.ui.clone();
        // The window owns the controller; reach it back through the window's callback rather than an Rc.
        self.autosave.start(TimerMode::SingleShot, AUTOSAVE_AFTER, move || {
            if let Some(ui) = weak.upgrade() {
                ui.invoke_autosave_due();
            }
        });
    }

    /// Saves what the editor shows, if it changed. A draft becomes a note on its first words (or when
    /// `force`, for a draft turned into a checklist before anything was typed).
    fn save_editor(&self, force: bool) -> Option<NoteView> {
        self.autosave.stop();
        let ui = self.ui()?;
        let (id, dirty, is_checklist, checklist) = {
            let state = self.state.borrow();
            let e = state.editing.as_ref()?;
            (e.id.clone(), e.dirty, e.is_checklist, e.checklist.clone())
        };
        if !dirty && !force {
            return id.and_then(|id| self.service.borrow().note(&id));
        }
        let title = ui.get_editor_title().to_string();
        let body = if is_checklist { String::new() } else { ui.get_editor_body().to_string() };
        let result = match &id {
            None if !force && title.trim().is_empty() && body.trim().is_empty() => {
                if let Some(e) = self.state.borrow_mut().editing.as_mut() {
                    e.dirty = false;
                }
                ui.set_editor_footer("Not saved yet".into());
                return None;
            }
            None => self.service.borrow_mut().create(&title, &body),
            Some(id) => {
                let updated = self.service.borrow_mut().update(id, &title, &body);
                match updated {
                    Ok(_) if is_checklist => self.service.borrow_mut().set_checklist(id, checklist),
                    other => other,
                }
            }
        };
        match result {
            Ok(saved) => {
                if let Some(e) = self.state.borrow_mut().editing.as_mut() {
                    e.id = Some(saved.id.clone());
                    e.dirty = false;
                }
                ui.set_editor_saved_once(true);
                ui.set_editor_footer(Self::saved_footer(saved.timestamp));
                self.refresh();
                Some(saved)
            }
            Err(message) => {
                ui.set_editor_footer(format!("Not saved: {message}").into());
                self.show_toast(&format!("Not saved: {message}"), None, None);
                None
            }
        }
    }

    pub fn save_now(&self) {
        self.save_editor(false);
    }

    fn close_sheet(&self) {
        self.state.borrow_mut().editing = None;
        if let Some(ui) = self.ui() {
            ui.set_options_open(false);
            ui.set_editor_open(false);
            ui.invoke_focus_main();
        }
        self.refresh();
    }

    pub fn close_editor(&self) {
        self.save_now();
        self.close_sheet();
    }

    /// Applies a change to the open note and shows the result in the editor and the grid.
    fn change_open_note(
        &self,
        change: impl FnOnce(&mut NotesService, &str) -> Result<NoteView, String>,
    ) -> Option<NoteView> {
        self.save_now();
        let ui = self.ui()?;
        let id = self.editing_id()?;
        let result = change(&mut self.service.borrow_mut(), &id);
        match result {
            Ok(note) => {
                self.show_note_details(&ui, &note);
                self.refresh();
                Some(note)
            }
            Err(message) => {
                self.show_toast(&message, None, None);
                None
            }
        }
    }

    pub fn toggle_pin(&self) {
        let pinned = self.ui().is_some_and(|ui| ui.get_editor_pinned());
        self.change_open_note(|s, id| s.set_pinned(id, !pinned));
    }

    /// Archive or unarchive, close the editor, and offer Undo, as the web app does.
    pub fn toggle_archive(&self) {
        let Some(ui) = self.ui() else { return };
        let archiving = ui.get_editor_mode() != View::Archive;
        let Some(note) = self.change_open_note(|s, id| s.set_archived(id, archiving)) else { return };
        self.close_sheet();
        if archiving {
            self.show_toast("Note archived", Some("Undo"), Some(Undo::Unarchive(note.id)));
        } else {
            self.show_toast("Note unarchived", Some("Undo"), Some(Undo::Rearchive(note.id)));
        }
    }

    pub fn trash_note(&self) {
        let Some(note) = self.change_open_note(|s, id| s.move_to_trash(id)) else { return };
        self.close_sheet();
        self.show_toast("Note moved to trash", Some("Undo"), Some(Undo::Restore(note.id)));
    }

    pub fn restore_note(&self) {
        if self.change_open_note(|s, id| s.restore_from_trash(id)).is_some() {
            self.close_sheet();
            self.show_toast("Note restored", None, None);
        }
    }

    fn ask(&self, title: &str, body: &str, action: &str, confirm: Confirm) {
        let Some(ui) = self.ui() else { return };
        self.state.borrow_mut().confirm = Some(confirm);
        ui.set_confirm_body(body.into());
        ui.set_confirm_action(action.into());
        ui.set_confirm_title(title.into());
    }

    pub fn ask_delete_forever(&self) {
        let Some(id) = self.editing_id() else { return };
        self.ask(
            "Delete forever?",
            "This note will be deleted for good. This can't be undone.",
            "Delete forever",
            Confirm::DeleteForever(id),
        );
    }

    pub fn ask_empty_trash(&self) {
        let count = self.service.borrow().counts().trash;
        let body = if count == 1 {
            "1 note will be deleted for good. This can't be undone.".to_string()
        } else {
            format!("{count} notes will be deleted for good. This can't be undone.")
        };
        self.ask("Empty trash?", &body, "Empty trash", Confirm::EmptyTrash);
    }

    pub fn confirm_cancelled(&self) {
        self.state.borrow_mut().confirm = None;
        if let Some(ui) = self.ui() {
            ui.set_confirm_title("".into());
        }
    }

    pub fn confirm_accepted(&self) {
        let confirm = self.state.borrow_mut().confirm.take();
        if let Some(ui) = self.ui() {
            ui.set_confirm_title("".into());
        }
        match confirm {
            Some(Confirm::DeleteForever(id)) => {
                let result = self.service.borrow_mut().delete(&id);
                match result {
                    Ok(_) => {
                        self.close_sheet();
                        self.show_toast("Note deleted forever", None, None);
                    }
                    Err(message) => self.show_toast(&message, None, None),
                }
            }
            Some(Confirm::EmptyTrash) => {
                let result = self.service.borrow_mut().empty_trash();
                match result {
                    Ok(0) => {}
                    Ok(n) => self.show_toast(&format!("Trash emptied ({n})"), None, None),
                    Err(message) => self.show_toast(&message, None, None),
                }
                self.refresh();
            }
            None => {}
        }
    }

    // --- Note options: colour and labels --------------------------------------------------------------

    fn show_label_choices(&self, ui: &AppWindow) {
        let service = self.service.borrow();
        let on: Vec<String> = self
            .editing_id()
            .and_then(|id| service.note(&id))
            .map(|n| n.labels.iter().map(|l| label_key(l)).collect())
            .unwrap_or_default();
        ui.set_label_choices(model(
            service
                .label_names()
                .into_iter()
                .map(|name| LabelChoice { on: on.contains(&label_key(&name)), name: name.into() })
                .collect(),
        ));
    }

    pub fn open_options(&self) {
        self.save_now();
        let Some(ui) = self.ui() else { return };
        self.show_label_choices(&ui);
        ui.set_new_label("".into());
        ui.set_options_open(true);
    }

    /// The picker stores the colour variant it showed, as the web app's picker does.
    pub fn pick_color(&self, index: usize, dark: bool) {
        self.change_open_note(|s, id| s.set_color(id, palette::to_store(index, dark)));
    }

    pub fn toggle_label(&self, name: &str) {
        let on = self
            .editing_id()
            .and_then(|id| self.service.borrow().note(&id))
            .is_some_and(|n| n.labels.iter().any(|l| label_key(l) == label_key(name)));
        self.change_open_note(|s, id| s.set_note_label(id, name, !on));
        if let Some(ui) = self.ui() {
            self.show_label_choices(&ui);
        }
    }

    /// A label created in the options sheet is put on the note too.
    pub fn create_label_for_note(&self, name: &str) {
        let Some(ui) = self.ui() else { return };
        let created = self.service.borrow_mut().create_label(name);
        match created {
            Ok(name) => {
                self.change_open_note(|s, id| s.set_note_label(id, &name, true));
                ui.set_new_label("".into());
                self.show_label_choices(&ui);
            }
            Err(message) => self.show_toast(&message, None, None),
        }
    }

    // --- Checklists -----------------------------------------------------------------------------------

    pub fn item_edited(&self, position: i64, text: &str) {
        if let Some(editing) = self.state.borrow_mut().editing.as_mut()
            && let Some(item) = editing.checklist.iter_mut().find(|i| i.position == position)
        {
            item.text = text.to_string();
        }
        // The row already shows the text: no model rebuild while typing, so the caret stays put.
        self.edited();
    }

    fn change_items(&self, focus: Option<i64>, change: impl FnOnce(&mut Vec<ChecklistItem>)) {
        {
            let mut state = self.state.borrow_mut();
            let Some(editing) = state.editing.as_mut() else { return };
            change(&mut editing.checklist);
            editing.dirty = true;
        }
        self.save_now();
        if let Some(ui) = self.ui() {
            self.show_rows(&ui, focus);
        }
    }

    pub fn item_toggled(&self, position: i64) {
        self.change_items(None, |items| {
            if let Some(item) = items.iter_mut().find(|i| i.position == position) {
                item.is_checked = !item.is_checked;
            }
        });
    }

    pub fn item_removed(&self, position: i64) {
        self.change_items(None, |items| items.retain(|i| i.position != position));
    }

    pub fn item_added(&self) {
        let next = self
            .state
            .borrow()
            .editing
            .as_ref()
            .map_or(0, |e| e.checklist.iter().map(|i| i.position + 1).max().unwrap_or(0));
        if next as usize >= notelikeus_app::MAX_CHECKLIST_ITEMS {
            self.show_toast("A checklist can't hold any more items.", None, None);
            return;
        }
        self.change_items(Some(next), |items| {
            items.push(ChecklistItem { text: String::new(), is_checked: false, position: next });
        });
    }

    pub fn to_checklist(&self) {
        let Some(ui) = self.ui() else { return };
        // A draft becomes a note first, so there is something to convert.
        if self.save_editor(true).is_none() && self.editing_id().is_none() {
            return;
        }
        let Some(id) = self.editing_id() else { return };
        let converted = self.service.borrow_mut().convert_to_checklist(&id);
        match converted {
            Ok(note) => {
                if let Some(e) = self.state.borrow_mut().editing.as_mut() {
                    e.is_checklist = true;
                    e.checklist = note.checklist.clone();
                }
                ui.set_editor_body("".into());
                self.show_note_details(&ui, &note);
                self.show_rows(&ui, note.checklist.first().map(|i| i.position));
                self.refresh();
            }
            Err(message) => self.show_toast(&message, None, None),
        }
    }

    pub fn to_text(&self) {
        self.save_now();
        let Some(ui) = self.ui() else { return };
        let Some(id) = self.editing_id() else { return };
        let converted = self.service.borrow_mut().convert_to_text(&id);
        match converted {
            Ok(note) => {
                if let Some(e) = self.state.borrow_mut().editing.as_mut() {
                    e.is_checklist = false;
                    e.checklist.clear();
                }
                ui.set_editor_body(note.content.as_str().into());
                self.show_note_details(&ui, &note);
                self.show_rows(&ui, None);
                self.refresh();
            }
            Err(message) => self.show_toast(&message, None, None),
        }
    }

    // --- Edit labels ----------------------------------------------------------------------------------

    fn show_all_labels(&self, ui: &AppWindow) {
        let names = self.service.borrow().label_names();
        ui.set_all_labels(model(names.iter().map(|n| SharedString::from(n.as_str())).collect()));
    }

    /// After a label changed everywhere: the grid, and the open note's chips if any.
    fn labels_changed(&self, ui: &AppWindow) {
        self.show_all_labels(ui);
        if let Some(note) = self.editing_id().and_then(|id| self.service.borrow().note(&id)) {
            self.show_note_details(ui, &note);
        }
        self.refresh();
    }

    pub fn open_labels(&self) {
        let Some(ui) = self.ui() else { return };
        self.show_all_labels(&ui);
        ui.set_labels_new("".into());
        ui.set_labels_open(true);
    }

    pub fn labels_created(&self, name: &str) {
        let Some(ui) = self.ui() else { return };
        let created = self.service.borrow_mut().create_label(name);
        match created {
            Ok(_) => {
                ui.set_labels_new("".into());
                self.show_all_labels(&ui);
            }
            Err(message) => self.show_toast(&message, None, None),
        }
    }

    pub fn label_renamed(&self, old: &str, new: &str) {
        let Some(ui) = self.ui() else { return };
        let renamed = self.service.borrow_mut().rename_label(old, new);
        match renamed {
            Ok(()) => self.labels_changed(&ui),
            Err(message) => self.show_toast(&message, None, None),
        }
    }

    pub fn label_deleted(&self, name: &str) {
        let Some(ui) = self.ui() else { return };
        let deleted = self.service.borrow_mut().delete_label(name);
        match deleted {
            Ok(()) => {
                self.labels_changed(&ui);
                self.show_toast(&format!("Label \"{name}\" deleted"), None, None);
            }
            Err(message) => self.show_toast(&message, None, None),
        }
    }

    // --- The toast ------------------------------------------------------------------------------------

    fn show_toast(&self, text: &str, action: Option<&str>, undo: Option<Undo>) {
        let Some(ui) = self.ui() else { return };
        self.state.borrow_mut().undo = undo;
        ui.set_toast_text(text.into());
        ui.set_toast_action(action.unwrap_or_default().into());
        let weak = self.ui.clone();
        self.toast.start(TimerMode::SingleShot, TOAST_FOR, move || {
            if let Some(ui) = weak.upgrade() {
                ui.invoke_toast_expired();
            }
        });
    }

    fn hide_toast(&self) {
        self.toast.stop();
        if let Some(ui) = self.ui() {
            ui.set_toast_text("".into());
            ui.set_toast_action("".into());
        }
    }

    /// The toast's Undo: the archive or trash move it announced is reversed.
    pub fn undo(&self) {
        let Some(undo) = self.state.borrow_mut().undo.take() else { return };
        self.hide_toast();
        let result = {
            let mut service = self.service.borrow_mut();
            match undo {
                Undo::Restore(id) => service.restore_from_trash(&id),
                Undo::Unarchive(id) => service.set_archived(&id, false),
                Undo::Rearchive(id) => service.set_archived(&id, true),
            }
        };
        if let Err(message) = result {
            self.show_toast(&message, None, None);
        }
        self.refresh();
    }

    /// The toast timed out: its Undo is no longer offered.
    pub fn toast_expired(&self) {
        self.state.borrow_mut().undo = None;
        self.hide_toast();
    }
}
