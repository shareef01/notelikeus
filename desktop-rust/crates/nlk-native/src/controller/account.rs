//! The account: signing in with an emailed code (after the browser's security check when the project
//! asks for one), syncing (after a change, every few minutes, and on request), signing out, and
//! reviewing the copies a sync conflict kept.

use std::cell::{Cell, RefCell};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use notelikeus_app::{AccountView, SyncSummary};
use notelikeus_core::captcha::Loopback;
use slint::{SharedString, Timer, TimerMode};

use super::{Confirm, Controller, model};
use crate::ReviewRow;

/// After a change, sync once things have been quiet this long.
const SYNC_AFTER_CHANGE: Duration = Duration::from_secs(4);
/// And every so often regardless, for changes made on other devices.
const SYNC_EVERY: Duration = Duration::from_secs(120);
/// The web app's wait before "Resend code" (Supabase's minimum gap).
const RESEND_AFTER_SECS: i32 = 60;
/// How long the browser's security check may take.
const CAPTCHA_TIMEOUT: Duration = Duration::from_secs(300);
/// `auth.email.otp_length` in supabase/config.toml.
pub const CODE_LENGTH: usize = 6;

/// The sidebar's account area: which of these the window shows.
pub mod mode {
    pub const NO_SYNC: i32 = 0;
    pub const GUEST: i32 = 1;
    pub const SIGNED_IN: i32 = 2;
}

/// Sign-in steps.
pub mod step {
    pub const EMAIL: i32 = 0;
    pub const CHECKING: i32 = 1;
    pub const CODE: i32 = 2;
}

#[derive(Default)]
pub(super) struct AccountState {
    syncing: Cell<bool>,
    /// A sync was asked for while one ran: run another when it ends.
    sync_again: Cell<bool>,
    soon: Timer,
    every: Timer,
    resend: Timer,
    resend_left: Cell<i32>,
    captcha_cancel: RefCell<Option<Arc<AtomicBool>>>,
    /// The address the code was sent to.
    email: RefCell<String>,
    /// The copy under review and the note it was split from.
    review: RefCell<Option<(String, Option<String>)>>,
}

impl AccountState {
    pub(super) fn reset_after_deletion(&self) {
        self.soon.stop();
        self.every.stop();
        self.sync_again.set(false);
        self.syncing.set(false);
    }
}

fn edits(count: usize) -> String {
    if count == 1 { "1 edit".into() } else { format!("{count} edits") }
}

/// The account area's status line, and whether it is a problem to point out.
pub fn status_line(account: &AccountView, syncing: bool, now: i64) -> (String, bool) {
    if syncing {
        return ("Syncing…".into(), false);
    }
    if account.uploads_blocked_after_wipe {
        return ("Cloud notes were wiped from another device. Uploads are paused.".into(), true);
    }
    if let Some(error) = &account.last_error {
        return (error.clone(), true);
    }
    if account.waiting > 0 {
        return (format!("{} waiting to sync", edits(account.waiting)), false);
    }
    match account.last_synced_at {
        Some(at) if now - at < 60_000 => ("Synced just now".into(), false),
        Some(at) if now - at < 3_600_000 => (format!("Synced {} min ago", (now - at) / 60_000), false),
        Some(at) => {
            let local = chrono::TimeZone::timestamp_millis_opt(&chrono::Local, at).single();
            (local.map_or("Synced".into(), |t| format!("Synced at {}", t.format("%-I:%M %p"))), false)
        }
        None => ("Not synced yet".into(), false),
    }
}

/// A plausible email address: something, an @, and a dotted domain, no spaces.
fn plausible_email(email: &str) -> bool {
    let Some((local, domain)) = email.split_once('@') else { return false };
    !local.is_empty()
        && domain.contains('.')
        && !domain.starts_with('.')
        && !domain.ends_with('.')
        && !email.contains(char::is_whitespace)
}

/// Opens `url` in the default browser.
fn open_browser(url: &str) -> Result<(), String> {
    #[cfg(windows)]
    {
        use windows_sys::Win32::UI::Shell::ShellExecuteW;
        use windows_sys::Win32::UI::WindowsAndMessaging::SW_SHOWNORMAL;
        let wide = |s: &str| s.encode_utf16().chain(std::iter::once(0)).collect::<Vec<u16>>();
        let (verb, target) = (wide("open"), wide(url));
        // SAFETY: both strings are NUL-terminated UTF-16 that outlive the call; the other pointers are null.
        let result = unsafe {
            ShellExecuteW(
                std::ptr::null_mut(),
                verb.as_ptr(),
                target.as_ptr(),
                std::ptr::null(),
                std::ptr::null(),
                SW_SHOWNORMAL,
            )
        };
        // ShellExecute reports success as a value above 32.
        if result as isize > 32 { Ok(()) } else { Err("Couldn't open your browser. Try again.".into()) }
    }
    #[cfg(not(windows))]
    {
        let _ = url;
        Err("Couldn't open your browser.".into())
    }
}

impl Controller {
    pub(super) fn open_preview_link(&self, href: &str) {
        let Some(url) = super::view::normalize_safe_href(href) else {
            self.show_toast("This link isn't supported.", None, None);
            return;
        };
        if let Err(message) = open_browser(&url) {
            self.show_toast(&message, None, None);
        }
    }

    fn raise_sync_due(&self, timer: &Timer, mode: TimerMode, after: Duration) {
        let weak = self.ui.clone();
        timer.start(mode, after, move || {
            if let Some(ui) = weak.upgrade() {
                ui.invoke_sync_due();
            }
        });
    }

    /// Shows the account area and, when signed in, syncs now and every few minutes.
    pub(super) fn start_account(&self) {
        self.show_account();
        let account = self.svc().account();
        if account.signed_in {
            self.raise_sync_due(&self.account.soon, TimerMode::SingleShot, Duration::from_millis(300));
        }
        if account.sync_available {
            self.raise_sync_due(&self.account.every, TimerMode::Repeated, SYNC_EVERY);
        }
    }

    /// The sidebar's account area. Kept as it is while a worker holds the notes.
    pub(super) fn show_account(&self) {
        let Some(ui) = self.ui() else { return };
        if self.jobs.holds_notes() && !self.account.syncing.get() {
            return;
        }
        if self.jobs.holds_notes() {
            ui.set_syncing(true);
            ui.set_account_status("Syncing…".into());
            ui.set_account_problem(false);
            return;
        }
        let service = self.svc();
        let account = service.account();
        ui.set_can_delete_account(service.can_delete_account());
        ui.set_uploads_blocked_after_wipe(account.uploads_blocked_after_wipe);
        ui.set_account_mode(if !account.sync_available {
            mode::NO_SYNC
        } else if account.signed_in {
            mode::SIGNED_IN
        } else {
            mode::GUEST
        });
        let email = account.email.clone().unwrap_or_default();
        ui.set_account_initial(
            email.chars().next().map_or("?".into(), |c| c.to_uppercase().collect::<String>()).into(),
        );
        ui.set_account_email(email.into());
        let (status, problem) = status_line(&account, self.account.syncing.get(), super::now_millis());
        ui.set_account_status(status.into());
        ui.set_account_problem(problem);
        ui.set_syncing(self.account.syncing.get());
    }

    /// After a change: sync once things are quiet, when signed in and something is waiting.
    pub(super) fn after_change(&self) {
        if self.jobs.holds_notes() {
            return;
        }
        let account = self.svc().account();
        if account.signed_in && account.waiting > 0 && !account.uploads_blocked_after_wipe {
            self.raise_sync_due(&self.account.soon, TimerMode::SingleShot, SYNC_AFTER_CHANGE);
        }
        self.show_account();
    }

    /// Syncs the signed-in account on a worker. Asked again while one runs, it runs once more after.
    pub fn sync_now(&self) {
        let account = self.svc().account();
        if !account.signed_in || !account.sync_available {
            return;
        }
        if self.account.syncing.get() {
            self.account.sync_again.set(true);
            return;
        }
        // The editor's words go with this sync.
        self.save_now();
        self.account.soon.stop();
        self.account.syncing.set(true);
        self.show_account();
        self.with_notes_in_background(|s| s.sync_now(), |c, result: Result<SyncSummary, String>| c.synced(result));
    }

    fn synced(&self, result: Result<SyncSummary, String>) {
        self.account.syncing.set(false);
        let still_signed_in = self.svc().account().signed_in;
        if !still_signed_in {
            self.close_sheet();
            self.pictures.clear();
            self.reset_reminders();
        }
        match &result {
            Ok(summary) => {
                self.pictures.clear_transient_failures();
                if summary.conflicted_copies > 0 {
                    let copies = if summary.conflicted_copies == 1 {
                        "1 conflicted copy".to_string()
                    } else {
                        format!("{} conflicted copies", summary.conflicted_copies)
                    };
                    self.show_toast(
                        &format!("A note changed on two devices. Sync kept {copies} so nothing was lost."),
                        None,
                        None,
                    );
                }
            }
            // A sign-in that ended or account deleted is worth interrupting for.
            Err(message) if message.contains("sign in again") || !still_signed_in => {
                self.show_toast(message, None, None);
            }
            Err(_) => {}
        }
        if still_signed_in && let Some(open_id) = self.editing_id() {
            let dirty = self.state.borrow().editing.as_ref().is_some_and(|e| e.dirty) || self.editor_dirty_hint.get();
            if !dirty {
                let latest = self.svc().note(&open_id);
                match latest {
                    Some(note) => self.reload_open_note(&note),
                    None => {
                        self.close_sheet();
                        self.show_toast("That note was deleted on another device.", None, None);
                    }
                }
            }
        }
        self.refresh();
        self.show_account();
        if still_signed_in && self.account.sync_again.replace(false) {
            self.sync_now();
        }
    }

    pub fn ask_delete_cloud_data(&self) {
        self.ask(
            "Delete cloud notes?",
            "All notes and pictures in the cloud will be permanently erased across devices. Notes stored on this device remain local.",
            "Delete cloud data",
            Confirm::DeleteCloudData,
        );
    }

    pub(super) fn delete_cloud_data_confirmed(&self) {
        self.save_now();
        self.account.soon.stop();
        self.with_notes_in_background(
            |s| s.delete_cloud_data(),
            |c, result: Result<(), String>| {
                if !c.svc().account().signed_in {
                    c.close_sheet();
                    c.pictures.clear();
                    c.reset_reminders();
                }
                match result {
                    Ok(()) => {
                        if let Some(ui) = c.ui() {
                            ui.set_settings_open(false);
                        }
                        c.show_toast("Cloud notes deleted. Local notes stay on this device.", None, None);
                    }
                    Err(message) => c.show_toast(&message, None, None),
                }
                c.refresh();
                c.show_account();
            },
        );
    }

    pub fn reupload_after_wipe(&self) {
        let account = self.svc().account();
        if !account.signed_in || !account.sync_available || self.account.syncing.get() {
            return;
        }
        self.save_now();
        self.account.soon.stop();
        self.account.syncing.set(true);
        self.show_account();
        self.with_notes_in_background(
            |s| s.reupload_after_wipe(),
            |c, result: Result<SyncSummary, String>| {
                let reuploaded = result.is_ok();
                c.synced(result);
                if reuploaded {
                    c.show_toast("Local notes re-uploaded to cloud.", None, None);
                }
            },
        );
    }

    pub fn ask_sign_out(&self) {
        let waiting = self.svc().account().waiting;
        let body = if waiting > 0 {
            format!(
                "{} not synced yet will stay on this device and sync when you sign in again. Your notes from this account stay here, sealed.",
                edits(waiting)
            )
        } else {
            "Your notes from this account stay on this device, sealed, and come back when you sign in again.".into()
        };
        self.ask("Sign out?", &body, "Sign out", Confirm::SignOut);
    }

    pub(super) fn sign_out(&self) {
        if !self.save_now() {
            return;
        }
        self.close_sheet();
        let result = self.svc().sign_out();
        self.pictures.clear();
        self.reset_reminders();
        match result {
            Ok(_) => self.show_toast("Signed out. These are the notes on this device.", None, None),
            Err(message) => self.show_toast(&message, None, None),
        }
        self.refresh();
        self.show_account();
    }

    // --- Signing in -----------------------------------------------------------------------------------

    pub fn open_sign_in(&self) {
        let Some(ui) = self.ui() else { return };
        ui.set_signin_step(step::EMAIL);
        ui.set_signin_email("".into());
        ui.set_signin_code("".into());
        ui.set_signin_error("".into());
        ui.set_signin_notice("".into());
        ui.set_signin_busy(false);
        ui.set_signin_resend_in(0);
        ui.set_signin_open(true);
    }

    fn sign_in_failed(&self, step: i32, message: &str) {
        let Some(ui) = self.ui() else { return };
        ui.set_signin_busy(false);
        ui.set_signin_step(step);
        ui.set_signin_error(message.into());
    }

    /// Sends the code: after the browser's security check when this build's project needs one.
    pub fn send_code(&self, resend: bool) {
        let Some(ui) = self.ui() else { return };
        let email = if resend { self.account.email.borrow().clone() } else { ui.get_signin_email().trim().to_string() };
        let back_to = if resend { step::CODE } else { step::EMAIL };
        if !plausible_email(&email) {
            return self.sign_in_failed(back_to, "Enter a valid email address.");
        }
        ui.set_signin_error("".into());
        ui.set_signin_notice("".into());
        ui.set_signin_busy(true);
        let page = self.svc().captcha_page_url();
        let Some(page) = page else {
            return self.request_code(email, None, resend);
        };
        let started = Loopback::start().and_then(|loopback| {
            let url = loopback.challenge_url(&page)?;
            open_browser(&url)?;
            Ok(loopback)
        });
        let loopback = match started {
            Ok(loopback) => loopback,
            Err(message) => return self.sign_in_failed(back_to, &message),
        };
        ui.set_signin_step(step::CHECKING);
        let cancel = Arc::new(AtomicBool::new(false));
        *self.account.captcha_cancel.borrow_mut() = Some(cancel.clone());
        self.in_background_unbounded(
            move || loopback.wait(CAPTCHA_TIMEOUT, &cancel),
            move |c, token: Result<String, String>| {
                c.account.captcha_cancel.borrow_mut().take();
                if !c.ui().is_some_and(|ui| ui.get_signin_open()) {
                    return;
                }
                match token {
                    Ok(token) => c.request_code(email, Some(token), resend),
                    Err(message) => c.sign_in_failed(back_to, &message),
                }
            },
        );
    }

    fn request_code(&self, email: String, token: Option<String>, resend: bool) {
        let address = email.clone();
        self.with_notes_in_background(
            move |s| s.request_code(&address, token.as_deref()),
            move |c, result: Result<(), String>| {
                let Some(ui) = c.ui().filter(|ui| ui.get_signin_open()) else { return };
                match result {
                    Ok(()) => {
                        ui.set_signin_busy(false);
                        ui.set_signin_step(step::CODE);
                        ui.set_signin_notice(if resend {
                            format!("We sent a new code to {email}.").into()
                        } else {
                            SharedString::new()
                        });
                        *c.account.email.borrow_mut() = email;
                        c.start_resend_wait();
                    }
                    Err(message) => c.sign_in_failed(if resend { step::CODE } else { step::EMAIL }, &message),
                }
            },
        );
    }

    fn start_resend_wait(&self) {
        self.account.resend_left.set(RESEND_AFTER_SECS);
        if let Some(ui) = self.ui() {
            ui.set_signin_resend_in(RESEND_AFTER_SECS);
        }
        let weak = self.ui.clone();
        self.account.resend.start(TimerMode::Repeated, Duration::from_secs(1), move || {
            if let Some(ui) = weak.upgrade() {
                ui.invoke_resend_tick();
            }
        });
    }

    pub fn resend_tick(&self) {
        let left = (self.account.resend_left.get() - 1).max(0);
        self.account.resend_left.set(left);
        if let Some(ui) = self.ui() {
            ui.set_signin_resend_in(left);
        }
        if left == 0 {
            self.account.resend.stop();
        }
    }

    pub fn verify_code(&self) {
        let Some(ui) = self.ui() else { return };
        let code: String = ui.get_signin_code().chars().filter(char::is_ascii_digit).collect();
        if code.len() != CODE_LENGTH {
            return self.sign_in_failed(step::CODE, &format!("Enter the {CODE_LENGTH}-digit code from the email."));
        }
        // The notes on screen change to the account's: nothing half-typed stays behind.
        self.close_editor();
        ui.set_signin_error("".into());
        ui.set_signin_busy(true);
        let email = self.account.email.borrow().clone();
        self.with_notes_in_background(
            move |s| s.verify_code(&email, &code).map(|_| ()),
            |c, result: Result<(), String>| match result {
                Ok(()) => {
                    c.account.resend.stop();
                    c.pictures.clear();
                    c.reset_reminders();
                    if let Some(ui) = c.ui() {
                        ui.set_signin_busy(false);
                        ui.set_signin_open(false);
                    }
                    c.show_toast("Signed in successfully", None, None);
                    c.refresh();
                    c.show_account();
                    c.sync_now();
                }
                Err(message) => c.sign_in_failed(step::CODE, &message),
            },
        );
    }

    pub fn change_email(&self) {
        let Some(ui) = self.ui() else { return };
        self.account.resend.stop();
        ui.set_signin_code("".into());
        ui.set_signin_error("".into());
        ui.set_signin_notice("".into());
        ui.set_signin_step(step::EMAIL);
    }

    pub fn cancel_sign_in(&self) {
        if let Some(cancel) = self.account.captcha_cancel.borrow_mut().take() {
            cancel.store(true, Ordering::Relaxed);
        }
        self.account.resend.stop();
        if let Some(ui) = self.ui() {
            ui.set_signin_busy(false);
            ui.set_signin_open(false);
        }
    }

    // --- Conflict review ------------------------------------------------------------------------------

    pub fn open_review(&self) {
        self.save_now();
        let Some(ui) = self.ui() else { return };
        let Some(copy_id) = self.editing_id() else { return };
        let review = self.svc().conflict_review(&copy_id);
        let Some(review) = review else { return };
        let rows = |lines: &[(String, bool)]| {
            model(
                lines
                    .iter()
                    .map(|(text, changed)| ReviewRow { text: text.as_str().into(), changed: *changed })
                    .collect(),
            )
        };
        let edited = |at: i64| -> SharedString {
            chrono::TimeZone::timestamp_millis_opt(&chrono::Local, at)
                .single()
                .map_or(SharedString::new(), |t| format!("Edited {}", t.format("%-d %b %Y, %-I:%M %p")).into())
        };
        ui.set_review_has_original(review.original.is_some());
        ui.set_review_copy_title(review.copy.title.as_str().into());
        ui.set_review_copy_edited(edited(review.copy.timestamp));
        ui.set_review_copy_lines(rows(&review.copy_lines));
        if let Some(original) = &review.original {
            ui.set_review_original_title(original.title.as_str().into());
            ui.set_review_original_edited(edited(original.timestamp));
            ui.set_review_original_lines(rows(&review.original_lines));
        }
        *self.account.review.borrow_mut() = Some((copy_id, review.original.map(|n| n.id)));
        ui.set_review_open(true);
    }

    fn reviewed(&self, result: Result<(), String>, done: &str) {
        if let Some(ui) = self.ui() {
            ui.set_review_open(false);
        }
        self.account.review.borrow_mut().take();
        match result {
            Ok(()) => {
                self.close_sheet();
                self.show_toast(done, None, None);
            }
            Err(_) => self.show_toast("Could not update the notes. Nothing was changed.", None, None),
        }
        self.refresh();
    }

    pub fn review_keep_original(&self) {
        let Some((copy, original)) = self.account.review.borrow().clone() else { return };
        let result = self.svc().keep_original(&copy);
        let done =
            if original.is_some() { "Kept the current note. The copy is in Trash." } else { "The copy is in Trash." };
        self.reviewed(result, done);
    }

    pub fn review_keep_copy(&self) {
        let Some((copy, Some(original))) = self.account.review.borrow().clone() else { return };
        let result = self.svc().keep_copy(&copy, &original);
        self.reviewed(result, "Kept the copy. The other version is in Trash.");
    }

    pub fn review_keep_both(&self) {
        let Some((copy, _)) = self.account.review.borrow().clone() else { return };
        let result = self.svc().keep_both(&copy);
        self.reviewed(result, "Kept both versions.");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn account(waiting: usize, last_synced_at: Option<i64>, last_error: Option<&str>) -> AccountView {
        AccountView {
            sync_available: true,
            signed_in: true,
            email: Some("someone@example.test".into()),
            waiting,
            last_synced_at,
            last_error: last_error.map(str::to_string),
            uploads_blocked_after_wipe: false,
        }
    }

    #[test]
    fn the_status_line_says_what_matters_most() {
        let now = 10_000_000;
        assert_eq!(status_line(&account(0, None, None), true, now), ("Syncing…".into(), false));
        let mut wiped = account(0, None, None);
        wiped.uploads_blocked_after_wipe = true;
        assert_eq!(
            status_line(&wiped, false, now),
            ("Cloud notes were wiped from another device. Uploads are paused.".into(), true)
        );
        assert_eq!(status_line(&account(2, None, Some("Offline.")), false, now), ("Offline.".into(), true));
        assert_eq!(status_line(&account(1, None, None), false, now).0, "1 edit waiting to sync");
        assert_eq!(status_line(&account(3, None, None), false, now).0, "3 edits waiting to sync");
        assert_eq!(status_line(&account(0, Some(now - 5_000), None), false, now).0, "Synced just now");
        assert_eq!(status_line(&account(0, Some(now - 600_000), None), false, now).0, "Synced 10 min ago");
        assert!(status_line(&account(0, Some(now - 7_200_000), None), false, now).0.starts_with("Synced at "));
        assert_eq!(status_line(&account(0, None, None), false, now).0, "Not synced yet");
    }

    #[test]
    fn email_addresses_are_checked_before_anything_is_sent() {
        assert!(plausible_email("someone@example.test"));
        for bad in ["", "someone", "@example.com", "someone@", "someone@example", "some one@example.com", "a@.com"] {
            assert!(!plausible_email(bad), "{bad:?}");
        }
    }
}
