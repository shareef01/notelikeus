//! The window against a real Supabase stack: sign in with an emailed code, sync, a conflict with another
//! device and its review, sign out. Through the same accessible labels a screen reader uses.
//!
//! Only for a LOCAL stack (`npx supabase start`): it creates a throwaway account, so it refuses any URL
//! that is not this machine. Without the variables below it returns early and says so.
//!
//!   NLK_TEST_SUPABASE_URL=http://127.0.0.1:54326
//!   NLK_TEST_SUPABASE_KEY=<local anon or publishable key>
//!   NLK_TEST_MAILPIT_URL=http://127.0.0.1:54324

use std::path::PathBuf;

use i_slint_backend_testing::ElementHandle;
use nlk_native::AppWindow;
use nlk_native::controller::{Controller, Mode};
use notelikeus_app::{NotesService, SyncConfig, SystemIds};
use notelikeus_core::auth::AuthClient;
use notelikeus_core::crypto::PlaintextKeyProtectorForTests;
use notelikeus_core::http::SupabaseTransport;
use notelikeus_core::store::LocalState;
use notelikeus_core::sync::{self, IdSource};
use serde_json::Value;
use slint::{ComponentHandle, Model};

const PASSWORD: &str = "a-long-test-password-1";

fn press(ui: &AppWindow, label: &str) {
    ElementHandle::find_by_accessible_label(ui, label)
        .next()
        .unwrap_or_else(|| panic!("no element labelled {label:?}"))
        .invoke_accessible_default_action();
}

fn type_note(ui: &AppWindow, title: &str, body: &str) {
    ui.set_editor_title(title.into());
    ui.set_editor_body(body.into());
    ui.invoke_editor_edited();
}

fn card_titles(ui: &AppWindow) -> Vec<String> {
    let mut titles: Vec<String> = ui
        .get_pinned_columns()
        .iter()
        .chain(ui.get_columns().iter())
        .flat_map(|c| c.cards.iter().map(|card| card.title.to_string()).collect::<Vec<_>>())
        .collect();
    titles.sort();
    titles
}

fn emailed_code(mailpit: &str, email: &str) -> String {
    (0..40)
        .find_map(|_| {
            std::thread::sleep(std::time::Duration::from_millis(250));
            let found: Value = ureq::get(&format!("{mailpit}/api/v1/search"))
                .query("query", &format!("to:{email}"))
                .call()
                .ok()?
                .into_json()
                .ok()?;
            let id = found["messages"].as_array()?.first()?["ID"].as_str()?.to_string();
            let message: Value = ureq::get(&format!("{mailpit}/api/v1/message/{id}")).call().ok()?.into_json().ok()?;
            let text =
                message["Text"].as_str().unwrap_or_default().to_string() + message["HTML"].as_str().unwrap_or_default();
            text.split(|c: char| !c.is_ascii_digit()).find(|run| run.len() == 6).map(str::to_string)
        })
        .expect("the code email arrived")
}

struct Clock(i64);
impl IdSource for Clock {
    fn now_millis(&mut self) -> i64 {
        self.0 += 1;
        self.0
    }
    fn random_below_1000(&mut self) -> u32 {
        (self.0 % 997) as u32
    }
}

#[test]
fn signing_in_syncing_and_reviewing_a_conflict_through_the_window() {
    let (Ok(url), Ok(key), Ok(mailpit)) = (
        std::env::var("NLK_TEST_SUPABASE_URL"),
        std::env::var("NLK_TEST_SUPABASE_KEY"),
        std::env::var("NLK_TEST_MAILPIT_URL"),
    ) else {
        eprintln!("skipped: set NLK_TEST_SUPABASE_URL, NLK_TEST_SUPABASE_KEY and NLK_TEST_MAILPIT_URL");
        return;
    };
    assert!(
        url.starts_with("http://127.0.0.1:") || url.starts_with("http://localhost:"),
        "this test creates an account and only runs against a local stack, not {url}"
    );
    i_slint_backend_testing::init_no_event_loop();

    // The account exists with a password too, so a second device can sign in without a second email
    // (the local stack sends two an hour).
    let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos();
    let email = format!("nlk-native-{nanos}@example.test");
    let auth = AuthClient::new(&url, &key).unwrap();
    auth.sign_up(&email, PASSWORD).unwrap();

    let dir: PathBuf = std::env::temp_dir().join(format!("nlk-native-live-{nanos}"));
    let config = SyncConfig {
        url: url.clone(),
        publishable_key: key.clone(),
        captcha_page_url: None,
        attachments_url: std::env::var("NLK_TEST_ATTACHMENTS_URL").ok(),
    };
    let service =
        NotesService::open(&dir, Box::new(PlaintextKeyProtectorForTests), Box::new(SystemIds), Some(config)).unwrap();
    let ui = AppWindow::new().unwrap();
    ui.window().set_size(slint::LogicalSize::new(1280.0, 1600.0));
    let _controller = Controller::attach_with(&ui, service, Mode::Inline);
    assert_eq!(ui.get_account_mode(), 1, "a guest, with sync available");

    // Sign in with the emailed code.
    press(&ui, "Sign in");
    assert!(ui.get_signin_open());
    ui.set_signin_email("not an address".into());
    press(&ui, "Send code");
    assert_eq!(ui.get_signin_error(), "Enter a valid email address.");
    ui.set_signin_email(email.as_str().into());
    press(&ui, "Send code");
    if ui.get_signin_error().to_lowercase().contains("too many") {
        eprintln!("skipped: the local stack's email limit (2 an hour) is used up: {}", ui.get_signin_error());
        return;
    }
    assert_eq!(ui.get_signin_step(), 2, "on to the code: {}", ui.get_signin_error());
    assert_eq!(ui.get_signin_resend_in(), 60, "Resend waits a minute");
    ui.set_signin_code("12".into());
    ui.invoke_signin_verify();
    assert_eq!(ui.get_signin_error(), "Enter the 6-digit code from the email.");
    ui.set_signin_code(emailed_code(&mailpit, &email).as_str().into());
    ui.invoke_signin_verify();
    assert!(!ui.get_signin_open(), "signed in: {}", ui.get_signin_error());
    assert_eq!(ui.get_account_mode(), 2);
    assert_eq!(ui.get_account_email(), email.as_str());
    assert_eq!(ui.get_toast_text(), "Signed in successfully");

    // A note written here syncs.
    press(&ui, "New note");
    type_note(&ui, "Groceries", "milk");
    press(&ui, "Back to notes");
    assert_eq!(ui.get_account_status(), "1 edit waiting to sync");
    press(&ui, "Sync now");
    assert_eq!(ui.get_account_status(), "Synced just now");

    // Another device gets it and changes it; this window changes it too before syncing again.
    let session = auth.sign_in_with_password(&email, PASSWORD).unwrap();
    let mut phone = LocalState { owner_id: session.user_id.clone(), ..Default::default() };
    let mut transport = SupabaseTransport::new(&url, &key, &session.access_token).unwrap();
    let mut clock = Clock(5_000_000);
    sync::sync(&mut phone, &mut transport, &mut clock).unwrap();
    let id = phone.notes.values().find(|n| n.title == "Groceries").expect("the phone got the note").id.clone();
    sync::edit_note(&mut phone, &id, clock.now_millis(), |n| n.content = "milk, eggs".into()).unwrap();
    sync::sync(&mut phone, &mut transport, &mut clock).unwrap();

    press(&ui, "Groceries");
    type_note(&ui, "Groceries", "milk, bread");
    press(&ui, "Back to notes");
    press(&ui, "Sync now");
    assert!(ui.get_toast_text().starts_with("A note changed on two devices."), "{}", ui.get_toast_text());
    assert_eq!(card_titles(&ui), ["Groceries", "Groceries (conflicted copy)"]);

    // The copy says what it is, and the review shows both versions side by side.
    press(&ui, "Groceries (conflicted copy)");
    assert!(ui.get_editor_conflict());
    press(&ui, "Compare with the current note");
    assert!(ui.get_review_open());
    assert!(ui.get_review_has_original());
    let lines = |rows: slint::ModelRc<nlk_native::ReviewRow>| {
        rows.iter().map(|r| (r.text.to_string(), r.changed)).collect::<Vec<_>>()
    };
    assert_eq!(
        lines(ui.get_review_original_lines()),
        [("milk, eggs".to_string(), true)],
        "the phone's words won the note"
    );
    assert_eq!(
        lines(ui.get_review_copy_lines()),
        [("milk, bread".to_string(), true)],
        "this window's words are the copy"
    );
    press(&ui, "Keep both");
    assert!(!ui.get_review_open() && !ui.get_editor_open());
    assert_eq!(ui.get_toast_text(), "Kept both versions.");
    assert_eq!(card_titles(&ui), ["Groceries", "Groceries"]);
    press(&ui, "Sync now");
    assert_eq!(ui.get_account_status(), "Synced just now");

    // Signing out asks first, then shows this device's guest notes.
    press(&ui, "Sign out");
    assert_eq!(ui.get_confirm_title(), "Sign out?");
    ui.invoke_confirm_accepted();
    assert_eq!(ui.get_account_mode(), 1);
    assert!(card_titles(&ui).is_empty(), "the account's notes stay sealed on disk; the guest has none");
    let _ = std::fs::remove_dir_all(dir);
}
