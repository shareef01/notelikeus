//! Reply parsing for the Supabase transport, against the shapes the migrations return.
#![cfg(feature = "http")]

use notelikeus_core::http::{
    SupabaseTransport, classify_error, parse_apply_reply, parse_delete_reply, parse_pull_reply,
};
use notelikeus_core::model::CloudChange;
use notelikeus_core::sync::{ApplyOutcome, DeleteOutcome, TransportError};
use serde_json::json;

fn row() -> serde_json::Value {
    json!({ "type": "note", "note_id": "12", "local_id": 12, "revision": 41, "title": "T", "content": "",
            "client_timestamp": 1, "color": 0, "is_pinned": false, "is_archived": false, "is_trashed": false,
            "position": 0, "reminder_timestamp": null, "server_updated_at": 5, "labels": [], "checklist": [] })
}

#[test]
fn apply_replies() {
    assert_eq!(
        parse_apply_reply(json!({"status": "applied", "revision": 42, "server_updated_at": 9})).unwrap(),
        ApplyOutcome::Applied { revision: 42, server_updated_at: Some(9) }
    );
    assert_eq!(
        parse_apply_reply(json!({"status": "conflict", "error": "note_deleted"})).unwrap(),
        ApplyOutcome::Deleted
    );
    assert_eq!(
        parse_apply_reply(json!({"status": "conflict", "error": "note_not_found"})).unwrap(),
        ApplyOutcome::NotFound
    );
    assert!(matches!(
        parse_apply_reply(json!({"status": "conflict", "current": row()})).unwrap(),
        ApplyOutcome::Conflict { current } if current.revision == Some(41)
    ));
    assert!(parse_apply_reply(json!({"status": "applied"})).is_err(), "applied without a revision");
    assert!(parse_apply_reply(json!([])).is_err(), "an empty array is not a reply");
}

#[test]
fn delete_replies() {
    assert_eq!(
        parse_delete_reply(json!({"status": "applied", "revision": 3, "deleted_at": 1})).unwrap(),
        DeleteOutcome::Deleted
    );
    assert_eq!(
        parse_delete_reply(json!({"status": "applied", "idempotent": true})).unwrap(),
        DeleteOutcome::AlreadyGone
    );
    assert_eq!(
        parse_delete_reply(json!({"status": "conflict", "error": "note_not_found"})).unwrap(),
        DeleteOutcome::AlreadyGone
    );
    assert!(matches!(
        parse_delete_reply(json!({"status": "conflict", "current": row()})).unwrap(),
        DeleteOutcome::Conflict { .. }
    ));
}

#[test]
fn pull_replies() {
    let page = parse_pull_reply(json!({
        "changes": [row(), {"type": "tombstone", "note_id": "13", "revision": 43, "deleted_at": 7}],
        "has_more": true, "wipe_generation": 2
    }))
    .unwrap();
    assert!(page.has_more);
    assert_eq!(page.wipe_generation, 2);
    assert!(matches!(&page.changes[1], CloudChange::Tombstone(t) if t.note_id == "13"));
}

#[test]
fn errors_are_classified_by_code_never_by_message() {
    assert_eq!(classify_error(400, &json!({"code": "NL002", "message": "x"})), TransportError::WipeGenerationStale);
    assert_eq!(classify_error(400, &json!({"code": "NL003"})), TransportError::AccountDeleted);
    assert_eq!(classify_error(401, &json!({})), TransportError::Unauthorized);
    // A note title can say anything; it must not be read as a code.
    assert!(matches!(
        classify_error(400, &json!({"code": "22023", "message": "NL003 account_deleted"})),
        TransportError::Server(_)
    ));
}

#[test]
fn plain_http_is_refused_except_on_loopback() {
    assert!(SupabaseTransport::new("http://example.supabase.co", "k", "t").is_err());
    assert!(SupabaseTransport::new("http://127.0.0.1:54321", "k", "t").is_ok());
    assert!(SupabaseTransport::new("https://example.supabase.co", "k", "t").is_ok());
}

#[test]
fn only_https_or_this_machine_is_accepted() {
    use notelikeus_core::http::checked_base_url;
    for ok in [
        "https://abc.supabase.co",
        "https://abc.supabase.co/",
        "http://127.0.0.1:54321",
        "http://localhost:54321",
        "http://[::1]:54321",
    ] {
        assert!(checked_base_url(ok).is_ok(), "{ok}");
    }
    for bad in [
        "http://localhost.example.com",
        "http://127.0.0.1.example.com",
        "http://example.com",
        "ftp://x",
        "https://",
        "localhost:54321",
    ] {
        assert!(checked_base_url(bad).is_err(), "{bad}");
    }
    assert_eq!(checked_base_url("https://abc.supabase.co/").unwrap(), "https://abc.supabase.co");
}

#[test]
fn sign_in_replies_and_errors() {
    use notelikeus_core::auth::{AuthError, classify, parse_session};
    let session = parse_session(
        &json!({ "access_token": "a", "refresh_token": "r", "expires_in": 3600, "user": { "id": "u-1", "email": "e@x.test" } }),
        1_000,
    )
    .unwrap();
    assert_eq!((session.user_id.as_str(), session.expires_at_ms), ("u-1", 3_601_000));
    assert!(session.needs_refresh(3_541_000) && !session.needs_refresh(3_540_999));
    assert!(parse_session(&json!({ "user": { "id": "u" } }), 0).is_err(), "no token is not a session");
    assert_eq!(classify(429, &json!({})), AuthError::RateLimited);
    assert_eq!(classify(400, &json!({ "error_code": "over_email_send_rate_limit" })), AuthError::RateLimited);
    assert_eq!(classify(403, &json!({ "error_code": "otp_expired" })), AuthError::InvalidCode);
    assert_eq!(classify(400, &json!({ "error_code": "captcha_failed" })), AuthError::CaptchaRequired);
    assert_eq!(classify(400, &json!({ "error_code": "refresh_token_not_found" })), AuthError::SessionExpired);
    // A message mentioning a code is not a code.
    assert!(matches!(classify(400, &json!({ "msg": "otp_expired" })), AuthError::Refused { .. }));
}
