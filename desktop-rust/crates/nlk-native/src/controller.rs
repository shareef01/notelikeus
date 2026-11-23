//! Connects the window to the app logic: the views (Notes, Archive, Trash), the grid, the editor (autosave,
//! pin, colour, labels, checklists, archive and trash with Undo, delete forever), Edit labels, and search.
//! It saves before anything that would lose an edit: closing the editor or the window, opening another
//! note, converting between text and checklist.

mod account;
mod background;
mod pictures;

use std::cell::{Cell, RefCell};
use std::collections::HashSet;
use std::path::PathBuf;
use std::rc::{Rc, Weak};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

use chrono::{TimeZone, Timelike};

pub use background::Mode;

use notelikeus_app::clock::LocalDays;
use notelikeus_app::query::{self, DateField, DatePreset, Filters, LabelMatch, NoteFlag, NoteScope, SMART_VIEWS};
use notelikeus_app::{ChecklistItem, NoteView, NotesService, Scope, label_key};
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
    SignOut,
    RemoveImage(String, String),
    ImportBackup(PathBuf),
    DeleteCloudData,
}

#[derive(Default)]
struct State {
    editing: Option<Editing>,
    undo: Option<Undo>,
    confirm: Option<Confirm>,
    /// What the grid shows: the sidebar's view, the search box and the Filters sheet, in one place.
    filters: Filters,
    /// Saved character selection `[start, end]` when opening the Insert Link dialog.
    link_selection: Option<(usize, usize)>,
}

pub struct Controller {
    ui: slint::Weak<AppWindow>,
    /// Shared with the worker thread that syncs and signs in; the window never waits for it (see
    /// [`background`]).
    service: Arc<Mutex<NotesService>>,
    data_dir: PathBuf,
    state: RefCell<State>,
    settings: RefCell<crate::settings::AppSettings>,
    editor_dirty_hint: Cell<bool>,
    tray_available: Cell<bool>,
    tray_explained: Cell<bool>,
    autosave: Timer,
    toast: Timer,
    reminder_timer: Timer,
    reminder_heartbeat: Timer,
    notification_timer: Timer,
    fired_reminders: RefCell<HashSet<(String, i64)>>,
    reminders_initialized: Cell<bool>,
    scheduled_reminder: Cell<Option<i64>>,
    #[cfg(windows)]
    active_notify_hwnd: Cell<Option<isize>>,
    jobs: background::Jobs,
    account: account::AccountState,
    pictures: pictures::PicturesState,
    /// While the grid is being built (a picture arriving then waits for it to finish).
    refreshing: Cell<bool>,
}

impl Drop for Controller {
    fn drop(&mut self) {
        self.cleanup_notifications();
    }
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

fn reminder_time_input(timestamp: i64) -> String {
    chrono::Local
        .timestamp_millis_opt(timestamp)
        .single()
        .map(|time| time.format("%Y-%m-%d %H:%M").to_string())
        .unwrap_or_default()
}

fn reminder_card_time(timestamp: i64) -> String {
    chrono::Local
        .timestamp_millis_opt(timestamp)
        .single()
        .map(|time| time.format("%b %-d, %-I:%M %p").to_string())
        .unwrap_or_default()
}

/// Resolves local wall time through DST gaps and repeated hours.
fn resolve_local_time(mut local: chrono::NaiveDateTime) -> Option<chrono::DateTime<chrono::Local>> {
    for _ in 0..=180 {
        match chrono::Local.from_local_datetime(&local) {
            chrono::LocalResult::Single(time) => return Some(time),
            chrono::LocalResult::Ambiguous(first, second) => {
                return Some(if first.timestamp_millis() <= second.timestamp_millis() { first } else { second });
            }
            chrono::LocalResult::None => {
                local = local.checked_add_signed(chrono::Duration::minutes(1))?;
            }
        }
    }
    None
}

fn reminder_preset_time(index: usize) -> Option<i64> {
    use chrono::NaiveTime;
    let now = chrono::Local::now();
    let local = match index {
        0 => return Some((now + chrono::Duration::hours(1)).timestamp_millis()),
        1 => now.date_naive().checked_add_days(chrono::Days::new(1))?.and_time(NaiveTime::from_hms_opt(9, 0, 0)?),
        2 => now.date_naive().checked_add_days(chrono::Days::new(7))?.and_hms_opt(now.hour(), now.minute(), 0)?,
        _ => return None,
    };
    resolve_local_time(local).map(|time| time.timestamp_millis())
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
    /// Wires every callback of `ui` and shows the notes, with sign-in and sync on a worker thread.
    /// The window holds the controller alive.
    pub fn attach(ui: &AppWindow, service: NotesService) -> Rc<Controller> {
        Self::attach_with(ui, service, Mode::Threads)
    }

    /// As [`Controller::attach`]; [`Mode::Inline`] runs sign-in and sync on the calling thread, for
    /// tests that have no event loop to hand results back on.
    pub fn attach_with(ui: &AppWindow, service: NotesService, mode: Mode) -> Rc<Controller> {
        let data_dir = service.root_dir().to_path_buf();
        let initial_settings = crate::settings::load_settings(&data_dir);
        let controller = Rc::new(Controller {
            ui: ui.as_weak(),
            service: Arc::new(Mutex::new(service)),
            data_dir,
            state: RefCell::default(),
            settings: RefCell::new(initial_settings.clone()),
            editor_dirty_hint: Cell::new(false),
            tray_available: Cell::new(false),
            tray_explained: Cell::new(false),
            autosave: Timer::default(),
            toast: Timer::default(),
            reminder_timer: Timer::default(),
            reminder_heartbeat: Timer::default(),
            notification_timer: Timer::default(),
            fired_reminders: RefCell::default(),
            reminders_initialized: Cell::new(false),
            scheduled_reminder: Cell::new(None),
            #[cfg(windows)]
            active_notify_hwnd: Cell::new(None),
            jobs: background::Jobs::new(mode),
            account: account::AccountState::default(),
            pictures: pictures::PicturesState::default(),
            refreshing: Cell::new(false),
        });
        ui.set_swatches(model(
            palette::NOTE_COLORS
                .iter()
                .map(|c| Swatch { name: c.name.into(), none: c.light == 0, light: argb(c.light), dark: argb(c.dark) })
                .collect(),
        ));
        ui.set_theme_mode(initial_settings.theme_mode);
        ui.set_theme_accent(initial_settings.accent);
        ui.set_theme_amoled(initial_settings.amoled);
        ui.set_sort_order(initial_settings.sort_order);
        ui.set_view_density(initial_settings.view_density);
        ui.set_sidebar_collapsed(initial_settings.sidebar_collapsed);
        ui.set_app_version(env!("CARGO_PKG_VERSION").into());

        // While a worker holds the notes (a sync, a sign-in), service-touching requests wait in a queue
        // and run when the worker is done so the window never blocks. Pure UI/settings callbacks use `wire_ui!`.
        macro_rules! wire {
            ($setter:ident, |$c:ident $(, $arg:ident)*| $body:expr) => {{
                let weak: Weak<Controller> = Rc::downgrade(&controller);
                ui.$setter(move |$($arg),*| {
                    if let Some($c) = weak.upgrade() {
                        if $c.jobs.holds_notes() {
                            $c.jobs.defer(Box::new(move |$c: &Controller| {
                                $body;
                            }));
                        } else {
                            $body;
                        }
                    }
                });
            }};
        }
        macro_rules! wire_ui {
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
        wire_ui!(on_editor_edited, |c| c.edited());
        wire!(on_close_editor, |c| c.close_editor());
        wire!(on_toggle_pin, |c| c.toggle_pin());
        wire!(on_archive_toggled, |c| c.toggle_archive());
        wire!(on_trash_note, |c| c.trash_note());
        wire!(on_restore_note, |c| c.restore_note());
        wire!(on_delete_forever, |c| c.ask_delete_forever());
        wire!(on_empty_trash, |c| c.ask_empty_trash());
        wire!(on_confirm_accepted, |c| c.confirm_accepted());
        wire_ui!(on_confirm_cancelled, |c| c.confirm_cancelled());
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
        wire_ui!(on_format_bold, |c, anchor, focus| c.format_markdown(anchor, focus, view::toggle_bold));
        wire_ui!(on_format_italic, |c, anchor, focus| c.format_markdown(anchor, focus, view::toggle_italic));
        wire_ui!(on_format_bullet, |c, anchor, focus| c.format_markdown(anchor, focus, view::toggle_bullet_list));
        wire_ui!(on_open_link_dialog, |c, anchor, focus| c.open_link_dialog(anchor, focus));
        wire_ui!(on_insert_link_submitted, |c| c.insert_link_submitted());
        wire!(on_open_labels, |c| c.open_labels());
        wire!(on_labels_created, |c, name| c.labels_created(&name));
        wire!(on_label_renamed, |c, old, new| c.label_renamed(&old, &new));
        wire!(on_label_deleted, |c, name| c.label_deleted(&name));
        wire!(on_toast_acted, |c| c.undo());
        wire!(on_autosave_due, |c| c.save_now());
        wire_ui!(on_toast_expired, |c| c.toast_expired());
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
        wire!(on_sync_requested, |c| c.sync_now());
        wire!(on_sign_out_requested, |c| c.ask_sign_out());
        wire!(on_reupload_after_wipe_requested, |c| c.reupload_after_wipe());
        wire!(on_delete_cloud_data_requested, |c| c.ask_delete_cloud_data());
        wire!(on_backup_export_json_requested, |c| c.export_json_backup());
        wire!(on_backup_export_bundle_requested, |c| c.export_bundle_backup());
        wire!(on_backup_import_requested, |c| c.pick_backup_to_import());
        wire!(on_signin_opened, |c| c.open_sign_in());
        wire!(on_signin_send, |c| c.send_code(false));
        wire!(on_signin_resend, |c| c.send_code(true));
        wire!(on_signin_verify, |c| c.verify_code());
        wire_ui!(on_signin_change_email, |c| c.change_email());
        wire_ui!(on_signin_cancelled, |c| c.cancel_sign_in());
        wire!(on_review_opened, |c| c.open_review());
        wire!(on_review_keep_original, |c| c.review_keep_original());
        wire!(on_review_keep_copy, |c| c.review_keep_copy());
        wire!(on_review_keep_both, |c| c.review_keep_both());
        wire!(on_settings_requested, |c| c.open_settings());
        wire_ui!(on_theme_picked, |c, mode| c.pick_theme(mode));
        wire_ui!(on_accent_picked, |c, accent| c.pick_accent(accent));
        wire_ui!(on_amoled_toggled, |c| c.toggle_amoled());
        wire_ui!(on_sidebar_toggled, |c| c.toggle_sidebar());
        wire_ui!(on_sort_picked, |c, sort| c.pick_sort(sort));
        wire_ui!(on_density_picked, |c, density| c.pick_density(density));
        wire!(on_diagnostics_requested, |c| c.open_diagnostics());
        wire_ui!(on_diagnostics_copied, |c| c.show_toast("Diagnostics copied to clipboard", None, None));
        wire!(on_diagnostics_export_requested, |c| c.export_diagnostics());
        wire!(on_delete_account_opened, |c| c.open_delete_account());
        wire!(on_delete_account_confirmed, |c| c.delete_account_confirmed());
        wire!(on_sync_due, |c| c.sync_now());
        wire_ui!(on_resend_tick, |c| c.resend_tick());
        wire!(on_image_add_requested, |c| c.pick_image());
        wire!(on_picture_opened, |c, index| c.open_picture(index.max(0) as usize));
        wire!(on_viewer_moved, |c, step| c.move_viewer(step));
        wire!(on_picture_remove_requested, |c| c.ask_remove_picture());
        wire!(on_open_reminder, |c| c.open_reminder());
        wire!(on_reminder_preset, |c, index| c.reminder_preset(index as usize));
        wire!(on_reminder_save, |c| c.save_reminder_input());
        wire!(on_reminder_remove, |c| c.remove_reminder());
        wire_ui!(on_reminder_cancel, |c| c.cancel_reminder_dialog());
        wire!(on_reminder_due, |c| c.deliver_due_reminders());
        {
            // Never deferred: this is how a worker's result comes back.
            let weak = Rc::downgrade(&controller);
            ui.on_background_done(move || {
                if let Some(c) = weak.upgrade() {
                    c.background_done();
                }
            });
        }
        {
            let weak = Rc::downgrade(&controller);
            ui.window().on_close_requested(move || {
                if let Some(c) = weak.upgrade() {
                    return c.on_window_close_requested();
                }
                slint::CloseRequestResponse::HideWindow
            });
        }
        if mode == Mode::Threads {
            let weak = controller.ui.clone();
            controller.reminder_heartbeat.start(TimerMode::Repeated, Duration::from_secs(60), move || {
                if let Some(ui) = weak.upgrade() {
                    ui.invoke_reminder_due();
                }
            });
        }

        controller.refresh();
        controller.start_account();
        controller
    }

    pub fn set_tray_available(&self, available: bool) {
        self.tray_available.set(available);
    }

    fn on_window_close_requested(&self) -> slint::CloseRequestResponse {
        // Persist open draft edits cleanly before closing or minimizing to tray.
        self.save_now();
        if !self.tray_available.get() || self.jobs.holds_notes() || !self.svc().has_pending_reminders() {
            self.cleanup_notifications();
            let _ = slint::quit_event_loop();
            return slint::CloseRequestResponse::HideWindow;
        }
        #[cfg(windows)]
        if !self.tray_explained.replace(true)
            && let Some(ui) = self.ui()
        {
            self.replace_active_notice(crate::win_notify::show_notice_for_window(
                &ui,
                "Notelikeus",
                "Notelikeus is still running in the tray for scheduled reminders.",
            ));
        }
        slint::CloseRequestResponse::HideWindow
    }

    fn save_current_settings(&self) {
        let current = self.settings.borrow().clone();
        if let Err(message) = crate::settings::save_settings(&self.data_dir, &current) {
            self.show_toast(&message, None, None);
        }
    }

    /// The notes. Never called while a worker holds them (the `wire!` queue sees to that), so on the
    /// window's thread this does not wait.
    fn svc(&self) -> MutexGuard<'_, NotesService> {
        self.service.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn ui(&self) -> Option<AppWindow> {
        self.ui.upgrade()
    }

    /// Opens Settings after saving any words currently in the editor.
    pub fn open_settings(&self) {
        self.save_now();
        let Some(ui) = self.ui() else { return };
        ui.set_can_delete_account(self.svc().can_delete_account());
        ui.set_deleting_account(false);
        ui.set_settings_open(true);
    }

    /// Applies a theme choice immediately and remembers it for the next launch.
    pub fn pick_theme(&self, mode: i32) {
        let mode = mode.clamp(crate::settings::THEME_SYSTEM, crate::settings::THEME_DARK);
        let Some(ui) = self.ui() else { return };
        ui.set_theme_mode(mode);
        self.settings.borrow_mut().theme_mode = mode;
        self.save_current_settings();
    }

    pub fn pick_accent(&self, accent: i32) {
        let accent = accent.clamp(crate::settings::ACCENT_NEUTRAL, crate::settings::ACCENT_GREEN);
        let Some(ui) = self.ui() else { return };
        ui.set_theme_accent(accent);
        self.settings.borrow_mut().accent = accent;
        self.save_current_settings();
    }

    pub fn toggle_amoled(&self) {
        let Some(ui) = self.ui() else { return };
        let next = !self.settings.borrow().amoled;
        ui.set_theme_amoled(next);
        self.settings.borrow_mut().amoled = next;
        self.save_current_settings();
    }

    pub fn toggle_sidebar(&self) {
        let Some(ui) = self.ui() else { return };
        let next = !self.settings.borrow().sidebar_collapsed;
        ui.set_sidebar_collapsed(next);
        self.settings.borrow_mut().sidebar_collapsed = next;
        self.save_current_settings();
    }

    pub fn pick_sort(&self, sort: i32) {
        let sort = sort.clamp(crate::settings::SORT_MANUAL, crate::settings::SORT_OLDEST);
        let Some(ui) = self.ui() else { return };
        ui.set_sort_order(sort);
        self.settings.borrow_mut().sort_order = sort;
        self.save_current_settings();
        if self.jobs.holds_notes() {
            self.jobs.defer(Box::new(|c| c.refresh()));
        } else {
            self.refresh();
        }
    }

    pub fn pick_density(&self, density: i32) {
        let density = density.clamp(crate::settings::DENSITY_GRID, crate::settings::DENSITY_COMPACT);
        let Some(ui) = self.ui() else { return };
        ui.set_view_density(density);
        self.settings.borrow_mut().view_density = density;
        self.save_current_settings();
        if self.jobs.holds_notes() {
            self.jobs.defer(Box::new(|c| c.refresh()));
        } else {
            self.refresh();
        }
    }

    pub fn open_diagnostics(&self) {
        let Some(ui) = self.ui() else { return };
        match self.svc().diagnostics_json() {
            Ok(report) => {
                ui.set_diagnostics_report(report.into());
                ui.set_diagnostics_open(true);
            }
            Err(error) => self.show_toast(&error, None, None),
        }
    }

    pub fn export_diagnostics(&self) {
        let Some(ui) = self.ui() else { return };
        let filename = format!("Notelikeus-diagnostics-{}.json", chrono::Local::now().format("%Y-%m-%d"));
        let handle = ui.window().window_handle();
        let Some(path) = rfd::FileDialog::new()
            .set_title("Export diagnostics")
            .set_file_name(filename)
            .add_filter("JSON report", &["json"])
            .set_parent(&handle)
            .save_file()
        else {
            return;
        };
        let report = match self.svc().diagnostics_json() {
            Ok(report) => report,
            Err(error) => return self.show_toast(&error, None, None),
        };
        match std::fs::write(path, report) {
            Ok(()) => self.show_toast("Diagnostics report saved", None, None),
            Err(error) => self.show_toast(&format!("Couldn't save diagnostics: {error}"), None, None),
        }
    }

    pub fn open_delete_account(&self) {
        let Some(ui) = self.ui() else { return };
        if !self.svc().can_delete_account() {
            self.show_toast("Account deletion isn't available in this build.", None, None);
            return;
        }
        ui.set_delete_account_confirmation("".into());
        ui.set_deleting_account(false);
        ui.set_delete_account_open(true);
    }

    pub fn delete_account_confirmed(&self) {
        let Some(ui) = self.ui() else { return };
        if ui.get_delete_account_confirmation().as_str() != "DELETE" || !self.svc().can_delete_account() {
            return;
        }
        self.save_now();
        ui.set_deleting_account(true);
        self.with_notes_in_background(
            |service| service.delete_account(),
            |controller, result| {
                controller.delete_account_finished(result);
            },
        );
    }

    fn delete_account_finished(&self, result: Result<notelikeus_app::DeleteAccountReport, String>) {
        let Some(ui) = self.ui() else { return };
        ui.set_deleting_account(false);
        match result {
            Ok(report) => {
                self.account.reset_after_deletion();
                self.pictures.clear();
                self.reset_reminders();
                ui.set_delete_account_open(false);
                ui.set_delete_account_confirmation("".into());
                ui.set_settings_open(false);
                self.close_sheet();
                self.show_account();
                if let Some(warning) = report.warning {
                    self.show_toast(&warning, None, None);
                } else {
                    self.show_toast("Account deleted. Guest notes remain on this device.", None, None);
                }
            }
            Err(message) if message.to_ascii_lowercase().contains("sign in again") => {
                ui.set_delete_account_open(false);
                ui.set_delete_account_confirmation("".into());
                ui.set_settings_open(false);
                self.show_toast(&message, None, None);
            }
            Err(message) => self.show_toast(&message, None, None),
        }
    }

    fn editing_id(&self) -> Option<String> {
        self.state.borrow().editing.as_ref().and_then(|e| e.id.clone())
    }

    fn card(&self, note: &NoteView, now: &chrono::DateTime<chrono::Local>, now_ms: i64) -> NoteCard {
        let labels = sorted_labels(&note.labels);
        let items = sorted_items(&note.checklist);
        let cover = note.images.first().map(|image| self.picture(&note.id, image));
        NoteCard {
            id: note.id.as_str().into(),
            has_cover: cover.is_some(),
            cover: cover.unwrap_or_default(),
            more_images: note.images.len().saturating_sub(1) as i32,
            title: note.title.trim().into(),
            preview: if note.checklist.is_empty() { view::preview(&note.content).into() } else { SharedString::new() },
            time: view::card_time(note.timestamp, now).into(),
            reminder: note.reminder_timestamp.map_or_else(String::new, reminder_card_time).into(),
            reminder_overdue: view::is_overdue(note.reminder_timestamp, now_ms),
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
        // A picture can arrive while the grid is built (at once, when jobs run inline): build again
        // after, rather than inside, this build.
        if self.refreshing.replace(true) {
            self.mark_pictures_dirty();
            return;
        }
        loop {
            self.build_grid();
            if !self.take_pictures_dirty() {
                break;
            }
        }
        self.refreshing.set(false);
    }

    fn build_grid(&self) {
        let Some(ui) = self.ui() else { return };
        let filters = self.state.borrow().filters.clone();
        let (sort, density) = {
            let s = self.settings.borrow();
            (s.to_sort_order(), s.view_density)
        };
        let now = now_millis();
        let service = self.svc();
        let counts = service.counts();
        let found = service.search_sorted(&filters, sort, now);
        let view_counts = service.smart_view_counts(now);
        let saved = service.saved_filters().to_vec();
        let labels = service.label_names();
        let reminders = service
            .list()
            .into_iter()
            .filter_map(|note| note.reminder_timestamp.map(|timestamp| (note.id, timestamp, !note.is_archived)))
            .chain(
                service
                    .list_in(Scope::Trash)
                    .into_iter()
                    .filter_map(|note| note.reminder_timestamp.map(|timestamp| (note.id, timestamp, false))),
            )
            .collect::<Vec<_>>();
        drop(service);
        self.schedule_reminders(reminders);

        ui.set_count_notes(counts.notes as i32);
        ui.set_count_archive(counts.archive as i32);
        ui.set_count_trash(counts.trash as i32);
        let local_now = chrono::Local::now();
        let shown = found.notes.len();
        let cols = if density == crate::settings::DENSITY_LIST { 1 } else { ui.get_column_count().max(1) as usize };
        let make_cols = |notes: Vec<NoteView>| -> Vec<CardColumn> {
            view::masonry(notes, cols, view::card_height)
                .into_iter()
                .map(|column| CardColumn {
                    cards: model(column.iter().map(|n| self.card(n, &local_now, now)).collect()),
                })
                .collect()
        };
        if filters.scope == NoteScope::Active {
            let (pinned, others): (Vec<_>, Vec<_>) = found.notes.into_iter().partition(|n| n.is_pinned);
            let has_pinned = !pinned.is_empty();
            let has_other = !others.is_empty();
            ui.set_has_pinned_section(has_pinned);
            ui.set_has_other_section(has_other);
            ui.set_pinned_columns(model(if has_pinned { make_cols(pinned) } else { Vec::new() }));
            ui.set_columns(model(if has_other { make_cols(others) } else { Vec::new() }));
        } else {
            let has_other = !found.notes.is_empty();
            ui.set_has_pinned_section(false);
            ui.set_has_other_section(has_other);
            ui.set_pinned_columns(model(Vec::new()));
            ui.set_columns(model(if has_other { make_cols(found.notes) } else { Vec::new() }));
        }
        ui.set_note_count(shown as i32);
        ui.set_searching(filters.has_active());
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
        self.after_change();
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
        let saved = self.svc().saved_filters().iter().find(|f| f.name == name).cloned();
        if let Some(saved) = saved {
            self.set_filters(saved.filters);
        }
    }

    pub fn delete_saved(&self, name: &str) {
        let deleted = self.svc().delete_saved_filter(name);
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
        let service = self.svc();
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
        let saved = self.svc().save_filter(name, &filters);
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
        ui.set_editor_reminder_set(note.reminder_timestamp.is_some());
        ui.set_reminder_set(note.reminder_timestamp.is_some());
        ui.set_editor_has_color(note.color != 0);
        ui.set_editor_color_light(argb(palette::for_theme(note.color, false)));
        ui.set_editor_color_dark(argb(palette::for_theme(note.color, true)));
        ui.set_editor_color_index(palette::index_of(note.color).map_or(-1, |i| i as i32));
        ui.set_editor_labels(model(
            sorted_labels(&note.labels).iter().map(|l| SharedString::from(l.as_str())).collect(),
        ));
        ui.set_editor_footer(Self::saved_footer(note.timestamp));
        ui.set_editor_conflict(notelikeus_core::conflict::is_conflicted_copy_title(&note.title));
        self.show_pictures(ui, note);
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

    fn byte_to_char_idx(text: &str, byte_idx: i32) -> usize {
        let mut boundary = (byte_idx.max(0) as usize).min(text.len());
        while boundary > 0 && !text.is_char_boundary(boundary) {
            boundary -= 1;
        }
        text[..boundary].chars().count()
    }

    fn char_to_byte_offset(text: &str, char_idx: usize) -> i32 {
        text.char_indices().nth(char_idx).map_or(text.len(), |(byte_idx, _)| byte_idx) as i32
    }

    fn apply_body_selection(&self, ui: &AppWindow, text: &str, start_char: usize, end_char: usize) {
        ui.set_editor_body_sel_anchor(Self::char_to_byte_offset(text, start_char));
        ui.set_editor_body_sel_focus(Self::char_to_byte_offset(text, end_char));
        ui.set_editor_body_sel_seq(ui.get_editor_body_sel_seq().wrapping_add(1));
    }

    pub fn format_markdown(
        &self,
        anchor_byte: i32,
        focus_byte: i32,
        transform: fn(&str, usize, usize) -> (String, usize, usize),
    ) {
        let Some(ui) = self.ui() else { return };
        if ui.get_editor_mode() == View::Trash || ui.get_editor_is_checklist() {
            return;
        }
        let body = ui.get_editor_body().to_string();
        let start_char = Self::byte_to_char_idx(&body, anchor_byte);
        let end_char = Self::byte_to_char_idx(&body, focus_byte);
        let (updated, new_start, new_end) = transform(&body, start_char, end_char);
        ui.set_editor_body(updated.as_str().into());
        self.apply_body_selection(&ui, &updated, new_start, new_end);
        self.edited();
    }

    pub fn open_link_dialog(&self, anchor_byte: i32, focus_byte: i32) {
        let Some(ui) = self.ui() else { return };
        if ui.get_editor_mode() == View::Trash || ui.get_editor_is_checklist() {
            return;
        }
        let body = ui.get_editor_body().to_string();
        let a = Self::byte_to_char_idx(&body, anchor_byte);
        let b = Self::byte_to_char_idx(&body, focus_byte);
        let (start, end) = (a.min(b), a.max(b));
        self.state.borrow_mut().link_selection = Some((start, end));
        let selected: String = body.chars().skip(start).take(end - start).collect();
        ui.set_link_label_text(selected.trim().into());
        ui.set_link_url_text("".into());
        ui.set_link_error("".into());
        ui.set_link_dialog_open(true);
    }

    pub fn insert_link_submitted(&self) {
        let Some(ui) = self.ui() else { return };
        if ui.get_editor_mode() == View::Trash || ui.get_editor_is_checklist() {
            return;
        }
        let body = ui.get_editor_body().to_string();
        let (start, end) = self.state.borrow().link_selection.unwrap_or_else(|| {
            let len = body.chars().count();
            (len, len)
        });
        let label = ui.get_link_label_text().to_string();
        let url = ui.get_link_url_text().to_string();
        match view::insert_link(&body, start, end, &label, &url) {
            Ok((updated, caret_start, caret_end)) => {
                self.state.borrow_mut().link_selection = None;
                ui.set_link_error("".into());
                ui.set_link_dialog_open(false);
                ui.set_editor_body(updated.as_str().into());
                self.apply_body_selection(&ui, &updated, caret_start, caret_end);
                self.edited();
            }
            Err(message) => {
                ui.set_link_error(message.into());
            }
        }
    }

    fn show_editor(&self, ui: &AppWindow, note: Option<&NoteView>) {
        self.editor_dirty_hint.set(false);
        self.state.borrow_mut().link_selection = None;
        ui.set_editor_title(note.map(|n| n.title.as_str()).unwrap_or_default().into());
        ui.set_editor_body(note.map(|n| n.content.as_str()).unwrap_or_default().into());
        ui.set_editor_saved_once(note.is_some());
        match note {
            Some(n) => self.show_note_details(ui, n),
            None => {
                ui.set_editor_mode(View::Notes);
                ui.set_editor_pinned(false);
                ui.set_editor_reminder_set(false);
                ui.set_reminder_set(false);
                ui.set_editor_has_color(false);
                ui.set_editor_color_index(0);
                ui.set_editor_labels(model(Vec::new()));
                ui.set_editor_pictures(model(Vec::new()));
                ui.set_editor_conflict(false);
                ui.set_editor_footer("Not saved yet".into());
            }
        }
        self.show_rows(ui, None);
        ui.set_options_open(false);
        ui.set_reminder_open(false);
        ui.set_link_dialog_open(false);
        ui.set_editor_open(true);
    }

    pub(super) fn reload_open_note(&self, note: &NoteView) {
        let Some(ui) = self.ui() else { return };
        self.editor_dirty_hint.set(false);
        let is_checklist = !note.checklist.is_empty();
        if let Some(editing) = self.state.borrow_mut().editing.as_mut() {
            editing.dirty = false;
            editing.is_checklist = is_checklist;
            editing.checklist = note.checklist.clone();
        }
        ui.set_editor_title(note.title.as_str().into());
        ui.set_editor_body(note.content.as_str().into());
        self.show_note_details(&ui, note);
        self.show_rows(&ui, None);
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
        let Some(note) = self.svc().note(id) else { return };
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
        self.editor_dirty_hint.set(true);
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
            (e.id.clone(), e.dirty || self.editor_dirty_hint.get(), e.is_checklist, e.checklist.clone())
        };
        if !dirty && !force {
            self.editor_dirty_hint.set(false);
            return id.and_then(|id| self.svc().note(&id));
        }
        let title = ui.get_editor_title().to_string();
        let body = if is_checklist { String::new() } else { ui.get_editor_body().to_string() };
        let result = match &id {
            None if !force && title.trim().is_empty() && body.trim().is_empty() => {
                self.editor_dirty_hint.set(false);
                if let Some(e) = self.state.borrow_mut().editing.as_mut() {
                    e.dirty = false;
                }
                ui.set_editor_footer("Not saved yet".into());
                return None;
            }
            None => self.svc().create(&title, &body),
            Some(id) if is_checklist => self.svc().update_checklist_note(id, &title, checklist),
            Some(id) => self.svc().update(id, &title, &body),
        };
        match result {
            Ok(saved) => {
                self.editor_dirty_hint.set(false);
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

    pub fn open_reminder(&self) {
        self.save_now();
        let Some(ui) = self.ui() else { return };
        let Some(id) = self.editing_id() else {
            self.show_toast("Save the note before setting a reminder.", None, None);
            return;
        };
        let Some(note) = self.svc().note(&id) else { return };
        let input = note
            .reminder_timestamp
            .map(reminder_time_input)
            .or_else(|| reminder_preset_time(1).map(reminder_time_input))
            .unwrap_or_default();
        ui.set_reminder_input(input.into());
        ui.set_reminder_error("".into());
        ui.set_reminder_open(true);
    }

    pub fn reminder_preset(&self, index: usize) {
        let Some(timestamp) = reminder_preset_time(index) else { return };
        self.set_reminder(timestamp);
    }

    pub fn save_reminder_input(&self) {
        let Some(ui) = self.ui() else { return };
        let input = ui.get_reminder_input().to_string();
        let Ok(local) = chrono::NaiveDateTime::parse_from_str(input.trim(), "%Y-%m-%d %H:%M") else {
            ui.set_reminder_error("Use YYYY-MM-DD HH:MM in your local time.".into());
            return;
        };
        let Some(time) = resolve_local_time(local) else {
            ui.set_reminder_error("That local time could not be resolved.".into());
            return;
        };
        let existing = self
            .editing_id()
            .and_then(|id| self.svc().note(&id))
            .and_then(|note| note.reminder_timestamp)
            .and_then(|timestamp| chrono::Local.timestamp_millis_opt(timestamp).single())
            .filter(|existing| {
                let current = existing.naive_local();
                current.date() == local.date() && current.hour() == local.hour() && current.minute() == local.minute()
            });
        let timestamp = existing.map_or_else(|| time.timestamp_millis(), |existing| existing.timestamp_millis());
        if timestamp <= now_millis() {
            ui.set_reminder_error("Choose a time in the future.".into());
            return;
        }
        self.set_reminder(timestamp);
    }

    fn set_reminder(&self, timestamp: i64) {
        let Some(ui) = self.ui() else { return };
        let Some(id) = self.editing_id() else {
            self.show_toast("Save the note before setting a reminder.", None, None);
            return;
        };
        match self.svc().set_reminder_timestamp(&id, timestamp) {
            Ok(note) => {
                self.show_note_details(&ui, &note);
                ui.set_reminder_open(false);
                self.show_toast("Reminder set.", None, None);
                self.refresh();
            }
            Err(message) => ui.set_reminder_error(message.into()),
        }
    }

    pub fn remove_reminder(&self) {
        let Some(ui) = self.ui() else { return };
        let Some(id) = self.editing_id() else { return };
        match self.svc().clear_reminder(&id) {
            Ok(note) => {
                self.show_note_details(&ui, &note);
                ui.set_reminder_open(false);
                self.show_toast("Reminder removed.", None, None);
                self.refresh();
            }
            Err(message) => ui.set_reminder_error(message.into()),
        }
    }

    pub fn cancel_reminder_dialog(&self) {
        if let Some(ui) = self.ui() {
            ui.set_reminder_open(false);
        }
    }

    fn schedule_reminders(&self, reminders: Vec<(String, i64, bool)>) {
        let now = now_millis();
        let known: HashSet<(String, i64)> = reminders.iter().map(|(id, time, _)| (id.clone(), *time)).collect();
        let mut fired = self.fired_reminders.borrow_mut();
        fired.retain(|reminder| known.contains(reminder));
        if !self.reminders_initialized.replace(true) {
            fired.extend(
                reminders.iter().filter(|(_, timestamp, _)| *timestamp <= now).map(|(id, time, _)| (id.clone(), *time)),
            );
        } else {
            fired.extend(
                reminders
                    .iter()
                    .filter(|(_, timestamp, active)| !active && *timestamp <= now)
                    .map(|(id, time, _)| (id.clone(), *time)),
            );
        }
        let next = reminders
            .iter()
            .filter(|(id, timestamp, _)| !fired.contains(&(id.clone(), *timestamp)))
            .min_by_key(|(_, timestamp, _)| *timestamp)
            .map(|(id, timestamp, _)| (id.clone(), *timestamp));
        drop(fired);

        let next_timestamp = next.as_ref().map(|(_, timestamp)| *timestamp);
        if self.scheduled_reminder.get() == next_timestamp {
            return;
        }
        self.reminder_timer.stop();
        self.scheduled_reminder.set(next_timestamp);
        let Some(timestamp) = next_timestamp else { return };
        let weak = self.ui.clone();
        self.reminder_timer.start(
            TimerMode::SingleShot,
            Duration::from_millis(timestamp.saturating_sub(now).max(0) as u64),
            move || {
                if let Some(ui) = weak.upgrade() {
                    ui.invoke_reminder_due();
                }
            },
        );
    }

    fn deliver_due_reminders(&self) {
        self.scheduled_reminder.set(None);
        let now = now_millis();
        let reminders = {
            let service = self.svc();
            service
                .list()
                .into_iter()
                .filter_map(|note| note.reminder_timestamp.map(|timestamp| (note.id, timestamp, !note.is_archived)))
                .chain(
                    service
                        .list_in(Scope::Trash)
                        .into_iter()
                        .filter_map(|note| note.reminder_timestamp.map(|timestamp| (note.id, timestamp, false))),
                )
                .collect::<Vec<_>>()
        };
        let due = reminders
            .iter()
            .filter(|(id, timestamp, active)| {
                *active && *timestamp <= now && !self.fired_reminders.borrow().contains(&(id.clone(), *timestamp))
            })
            .map(|(id, timestamp, _)| (id.clone(), *timestamp))
            .collect::<Vec<_>>();
        if !due.is_empty() {
            self.fired_reminders.borrow_mut().extend(due);
            self.show_reminder_notification();
        }
        self.schedule_reminders(reminders);
    }

    pub(super) fn reset_reminders(&self) {
        self.reminder_timer.stop();
        self.scheduled_reminder.set(None);
        self.reminders_initialized.set(false);
        self.fired_reminders.borrow_mut().clear();
        self.cleanup_notifications();
    }

    #[cfg(windows)]
    fn replace_active_notice(&self, next: Option<windows_sys::Win32::Foundation::HWND>) {
        if let Some(prev) = self.active_notify_hwnd.replace(next.map(|h| h as isize)) {
            crate::win_notify::remove(prev as windows_sys::Win32::Foundation::HWND);
        }
    }

    pub fn cleanup_notifications(&self) {
        self.notification_timer.stop();
        #[cfg(windows)]
        if let Some(hwnd) = self.active_notify_hwnd.take() {
            crate::win_notify::remove(hwnd as windows_sys::Win32::Foundation::HWND);
        }
    }

    fn show_reminder_notification(&self) {
        #[cfg(windows)]
        if let Some(ui) = self.ui()
            && let Some(hwnd) = crate::win_notify::show_for_window(&ui)
        {
            self.replace_active_notice(Some(hwnd));
            let hwnd_val = hwnd as isize;
            self.notification_timer.start(TimerMode::SingleShot, Duration::from_secs(20), move || {
                crate::win_notify::remove(hwnd_val as windows_sys::Win32::Foundation::HWND);
            });
        }
    }

    fn close_sheet(&self) {
        self.editor_dirty_hint.set(false);
        let mut state = self.state.borrow_mut();
        state.editing = None;
        state.link_selection = None;
        drop(state);
        if let Some(ui) = self.ui() {
            ui.set_options_open(false);
            ui.set_reminder_open(false);
            ui.set_link_dialog_open(false);
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
        let result = change(&mut self.svc(), &id);
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
        let count = self.svc().counts().trash;
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
                let result = self.svc().delete(&id);
                match result {
                    Ok(_) => {
                        self.close_sheet();
                        self.show_toast("Note deleted forever", None, None);
                    }
                    Err(message) => self.show_toast(&message, None, None),
                }
            }
            Some(Confirm::EmptyTrash) => {
                let result = self.svc().empty_trash();
                match result {
                    Ok(0) => {}
                    Ok(n) => self.show_toast(&format!("Trash emptied ({n})"), None, None),
                    Err(message) => self.show_toast(&message, None, None),
                }
                self.refresh();
            }
            Some(Confirm::SignOut) => self.sign_out(),
            Some(Confirm::RemoveImage(note, image)) => self.remove_picture(&note, &image),
            Some(Confirm::ImportBackup(path)) => self.import_backup(&path),
            Some(Confirm::DeleteCloudData) => self.delete_cloud_data_confirmed(),
            None => {}
        }
    }

    pub fn export_json_backup(&self) {
        let Some(ui) = self.ui() else { return };
        let filename = format!("Notelikeus-backup-{}.json", chrono::Local::now().format("%Y-%m-%d"));
        let handle = ui.window().window_handle();
        let Some(path) = rfd::FileDialog::new()
            .set_title("Export JSON backup")
            .set_file_name(filename)
            .add_filter("JSON backup", &["json"])
            .set_parent(&handle)
            .save_file()
        else {
            return;
        };
        self.with_notes_in_background(
            move |service| crate::backup::export_json(service, &path),
            |controller, result| match result {
                Ok(()) => controller.show_toast("JSON backup saved", None, None),
                Err(message) => controller.show_toast(&message, None, None),
            },
        );
    }

    pub fn export_bundle_backup(&self) {
        let Some(ui) = self.ui() else { return };
        let filename = format!("Notelikeus-backup-{}.nlkbak", chrono::Local::now().format("%Y-%m-%d"));
        let handle = ui.window().window_handle();
        let Some(path) = rfd::FileDialog::new()
            .set_title("Export backup with pictures")
            .set_file_name(filename)
            .add_filter("Notelikeus backup bundle", &["nlkbak"])
            .set_parent(&handle)
            .save_file()
        else {
            return;
        };
        self.with_notes_in_background(
            move |service| crate::backup::export_bundle(service, &path),
            |controller, result| match result {
                Ok(report) if report.images_skipped == 0 => controller.show_toast(
                    &format!(
                        "Backup saved with {} picture{}",
                        report.images_included,
                        if report.images_included == 1 { "" } else { "s" }
                    ),
                    None,
                    None,
                ),
                Ok(report) => controller.show_toast(
                    &format!(
                        "Backup saved with {} picture{}; {} skipped because local bytes were unavailable or over the limit",
                        report.images_included,
                        if report.images_included == 1 { "" } else { "s" },
                        report.images_skipped
                    ),
                    None,
                    None,
                ),
                Err(message) => controller.show_toast(&message, None, None),
            },
        );
    }

    pub fn pick_backup_to_import(&self) {
        let Some(ui) = self.ui() else { return };
        let handle = ui.window().window_handle();
        let Some(path) = rfd::FileDialog::new()
            .set_title("Import a backup")
            .add_filter("Notelikeus backups", &["json", "nlkbak"])
            .set_parent(&handle)
            .pick_file()
        else {
            return;
        };
        self.ask(
            "Import this backup?",
            "This adds copies of its notes. Importing the same backup again adds more copies. Some pictures may be skipped if their files are missing or invalid.",
            "Import copies",
            Confirm::ImportBackup(path),
        );
    }

    fn import_backup(&self, path: &std::path::Path) {
        let path = path.to_path_buf();
        self.with_notes_in_background(
            move |service| crate::backup::import_file(service, &path),
            |controller, result| match result {
                Ok(report) if report.notes_imported == 0 => {
                    controller.show_toast("No notes were found in that backup", None, None)
                }
                Ok(report) => {
                    controller.refresh();
                    let message = if report.attachments_skipped > 0 {
                        format!(
                            "Imported {} notes; {} pictures skipped",
                            report.notes_imported, report.attachments_skipped
                        )
                    } else if report.attachments_imported > 0 {
                        format!("Imported {} notes and {} pictures", report.notes_imported, report.attachments_imported)
                    } else {
                        format!("Imported {} notes", report.notes_imported)
                    };
                    controller.show_toast(&message, None, None);
                }
                Err(message) => controller.show_toast(&message, None, None),
            },
        );
    }

    // --- Note options: colour and labels --------------------------------------------------------------

    fn show_label_choices(&self, ui: &AppWindow) {
        let service = self.svc();
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
            .and_then(|id| self.svc().note(&id))
            .is_some_and(|n| n.labels.iter().any(|l| label_key(l) == label_key(name)));
        self.change_open_note(|s, id| s.set_note_label(id, name, !on));
        if let Some(ui) = self.ui() {
            self.show_label_choices(&ui);
        }
    }

    /// A label created in the options sheet is put on the note too.
    pub fn create_label_for_note(&self, name: &str) {
        let Some(ui) = self.ui() else { return };
        let created = self.svc().create_label(name);
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
        let (count, next) = {
            let state = self.state.borrow();
            let Some(editing) = state.editing.as_ref() else { return };
            let next = editing
                .checklist
                .iter()
                .map(|i| i.position.saturating_add(1))
                .max()
                .unwrap_or(0)
                .min(notelikeus_app::MAX_CHECKLIST_POSITION);
            (editing.checklist.len(), next)
        };
        if count >= notelikeus_app::MAX_CHECKLIST_ITEMS {
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
        let converted = self.svc().convert_to_checklist(&id);
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
        let converted = self.svc().convert_to_text(&id);
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
        let names = self.svc().label_names();
        ui.set_all_labels(model(names.iter().map(|n| SharedString::from(n.as_str())).collect()));
    }

    /// After a label changed everywhere: the grid, and the open note's chips if any.
    fn labels_changed(&self, ui: &AppWindow) {
        self.show_all_labels(ui);
        if let Some(note) = self.editing_id().and_then(|id| self.svc().note(&id)) {
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
        let created = self.svc().create_label(name);
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
        let renamed = self.svc().rename_label(old, new);
        match renamed {
            Ok(()) => self.labels_changed(&ui),
            Err(message) => self.show_toast(&message, None, None),
        }
    }

    pub fn label_deleted(&self, name: &str) {
        let Some(ui) = self.ui() else { return };
        let deleted = self.svc().delete_label(name);
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
            let mut service = self.svc();
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
