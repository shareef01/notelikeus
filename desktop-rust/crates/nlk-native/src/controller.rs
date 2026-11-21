//! Connects the window to the app logic: fills the grid, runs the editor (autosave, pin, delete with
//! Undo), searches, and saves before anything that would lose an edit (closing the editor or the window,
//! opening another note).

use std::cell::RefCell;
use std::rc::{Rc, Weak};
use std::time::Duration;

use notelikeus_app::{NoteView, NotesService};
use slint::{ComponentHandle, ModelRc, SharedString, Timer, TimerMode, VecModel};

use crate::view;
use crate::{AppWindow, CardColumn, NoteCard};

const AUTOSAVE_AFTER: Duration = Duration::from_millis(400);
const TOAST_FOR: Duration = Duration::from_secs(6);

/// The note in the editor: `None` until a draft's first save gives it an id.
#[derive(Default)]
struct Editing {
    id: Option<String>,
    dirty: bool,
}

#[derive(Default)]
struct State {
    editing: Option<Editing>,
    /// The note the toast's Undo brings back.
    undo: Option<NoteView>,
}

pub struct Controller {
    ui: slint::Weak<AppWindow>,
    service: RefCell<NotesService>,
    state: RefCell<State>,
    autosave: Timer,
    toast: Timer,
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

        let on = |f: fn(&Controller)| {
            let weak: Weak<Controller> = Rc::downgrade(&controller);
            move || {
                if let Some(c) = weak.upgrade() {
                    f(&c);
                }
            }
        };
        ui.on_new_note(on(Controller::new_note));
        ui.on_editor_edited(on(Controller::edited));
        ui.on_close_editor(on(Controller::close_editor));
        ui.on_toggle_pin(on(Controller::toggle_pin));
        ui.on_delete_note(on(Controller::delete_note));
        ui.on_toast_acted(on(Controller::undo_delete));
        ui.on_autosave_due(on(Controller::save_now));
        ui.on_toast_expired(on(Controller::toast_expired));
        {
            let weak = Rc::downgrade(&controller);
            ui.on_open_note(move |id| {
                if let Some(c) = weak.upgrade() {
                    c.open_note(&id);
                }
            });
        }
        ui.on_columns_changed({
            let weak = Rc::downgrade(&controller);
            move |_| {
                if let Some(c) = weak.upgrade() {
                    c.refresh();
                }
            }
        });
        ui.on_query_edited({
            let weak = Rc::downgrade(&controller);
            move |_| {
                if let Some(c) = weak.upgrade() {
                    c.refresh();
                }
            }
        });
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

    /// Rebuilds the grid from the store: search applied, pinned first, laid out in as many columns as fit.
    pub fn refresh(&self) {
        let Some(ui) = self.ui() else { return };
        let query = ui.get_query().to_string();
        let notes: Vec<NoteView> =
            self.service.borrow().list().into_iter().filter(|n| view::matches(n, &query)).collect();
        let now = chrono::Local::now();
        let columns = view::masonry(notes, ui.get_column_count().max(1) as usize, view::card_height)
            .into_iter()
            .map(|column| {
                let cards: Vec<NoteCard> = column
                    .into_iter()
                    .map(|n| NoteCard {
                        id: n.id.as_str().into(),
                        title: n.title.trim().into(),
                        preview: view::preview(&n.content).into(),
                        time: view::card_time(n.timestamp, &now).into(),
                        pinned: n.is_pinned,
                    })
                    .collect();
                CardColumn { cards: ModelRc::new(VecModel::from(cards)) }
            })
            .collect::<Vec<_>>();
        let shown: usize = columns.iter().map(|c| slint::Model::row_count(&c.cards)).sum();
        ui.set_note_count(shown as i32);
        ui.set_searching(!query.trim().is_empty());
        ui.set_columns(ModelRc::new(VecModel::from(columns)));
    }

    fn show_editor(&self, ui: &AppWindow, note: Option<&NoteView>) {
        ui.set_editor_title(note.map(|n| n.title.as_str()).unwrap_or_default().into());
        ui.set_editor_body(note.map(|n| n.content.as_str()).unwrap_or_default().into());
        ui.set_editor_pinned(note.is_some_and(|n| n.is_pinned));
        ui.set_editor_saved_once(note.is_some());
        ui.set_editor_footer(match note {
            Some(n) => self.saved_footer(n.timestamp),
            None => "Not saved yet".into(),
        });
        ui.set_editor_open(true);
    }

    fn saved_footer(&self, timestamp: i64) -> SharedString {
        format!("Saved on this device · {}", view::card_time(timestamp, &chrono::Local::now())).into()
    }

    pub fn new_note(&self) {
        self.save_now();
        let Some(ui) = self.ui() else { return };
        self.state.borrow_mut().editing = Some(Editing::default());
        self.show_editor(&ui, None);
    }

    pub fn open_note(&self, id: &str) {
        self.save_now();
        let Some(ui) = self.ui() else { return };
        let Some(note) = self.service.borrow().list().into_iter().find(|n| n.id == id) else { return };
        self.state.borrow_mut().editing = Some(Editing { id: Some(note.id.clone()), dirty: false });
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

    /// Saves what the editor shows, if it changed. A draft becomes a note on its first words.
    pub fn save_now(&self) {
        self.autosave.stop();
        let Some(ui) = self.ui() else { return };
        let (id, dirty) = match self.state.borrow().editing.as_ref() {
            Some(e) => (e.id.clone(), e.dirty),
            None => return,
        };
        if !dirty {
            return;
        }
        let title = ui.get_editor_title().to_string();
        let body = ui.get_editor_body().to_string();
        let result = match &id {
            None if title.trim().is_empty() && body.trim().is_empty() => {
                if let Some(e) = self.state.borrow_mut().editing.as_mut() {
                    e.dirty = false;
                }
                ui.set_editor_footer("Not saved yet".into());
                return;
            }
            None => self.service.borrow_mut().create(&title, &body),
            Some(id) => self.service.borrow_mut().update(id, &title, &body),
        };
        match result {
            Ok(saved) => {
                if let Some(e) = self.state.borrow_mut().editing.as_mut() {
                    e.id = Some(saved.id.clone());
                    e.dirty = false;
                }
                ui.set_editor_saved_once(true);
                ui.set_editor_footer(self.saved_footer(saved.timestamp));
                self.refresh();
            }
            Err(message) => {
                ui.set_editor_footer(format!("Not saved: {message}").into());
                self.show_toast(&format!("Not saved: {message}"), None);
            }
        }
    }

    pub fn close_editor(&self) {
        self.save_now();
        self.state.borrow_mut().editing = None;
        if let Some(ui) = self.ui() {
            ui.set_editor_open(false);
            ui.invoke_focus_main();
        }
        self.refresh();
    }

    pub fn toggle_pin(&self) {
        self.save_now();
        let Some(ui) = self.ui() else { return };
        let Some(id) = self.state.borrow().editing.as_ref().and_then(|e| e.id.clone()) else { return };
        let pinned = !ui.get_editor_pinned();
        match self.service.borrow_mut().set_pinned(&id, pinned) {
            Ok(saved) => {
                ui.set_editor_pinned(saved.is_pinned);
                ui.set_editor_footer(self.saved_footer(saved.timestamp));
            }
            Err(message) => self.show_toast(&message, None),
        }
        self.refresh();
    }

    pub fn delete_note(&self) {
        self.save_now();
        let Some(id) = self.state.borrow().editing.as_ref().and_then(|e| e.id.clone()) else { return };
        match self.service.borrow_mut().delete(&id) {
            Ok(removed) => {
                let name = if removed.title.trim().is_empty() {
                    "Untitled note".to_string()
                } else {
                    removed.title.trim().to_string()
                };
                self.state.borrow_mut().undo = Some(removed);
                self.state.borrow_mut().editing = None;
                if let Some(ui) = self.ui() {
                    ui.set_editor_open(false);
                }
                self.show_toast(&format!("Deleted \"{name}\""), Some("Undo"));
            }
            Err(message) => self.show_toast(&message, None),
        }
        self.refresh();
    }

    /// Undo brings the words back as a new note: the deleted id stays retired, as on the other clients.
    pub fn undo_delete(&self) {
        let Some(removed) = self.state.borrow_mut().undo.take() else { return };
        self.hide_toast();
        let restored = self.service.borrow_mut().create(&removed.title, &removed.content);
        match restored {
            Ok(note) => {
                if removed.is_pinned {
                    let _ = self.service.borrow_mut().set_pinned(&note.id, true);
                }
            }
            Err(message) => self.show_toast(&message, None),
        }
        self.refresh();
    }

    fn show_toast(&self, text: &str, action: Option<&str>) {
        let Some(ui) = self.ui() else { return };
        ui.set_toast_text(text.into());
        ui.set_toast_action(action.unwrap_or_default().into());
        let weak = self.ui.clone();
        self.toast.start(TimerMode::SingleShot, TOAST_FOR, move || {
            if let Some(ui) = weak.upgrade() {
                ui.invoke_toast_expired();
            }
        });
    }

    pub fn hide_toast(&self) {
        self.toast.stop();
        if let Some(ui) = self.ui() {
            ui.set_toast_text("".into());
            ui.set_toast_action("".into());
        }
    }

    /// The toast timed out: its Undo is no longer offered.
    pub fn toast_expired(&self) {
        self.state.borrow_mut().undo = None;
        self.hide_toast();
    }
}
