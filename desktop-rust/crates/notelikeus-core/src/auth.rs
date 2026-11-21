//! Supabase Auth (GoTrue) for the Rust client: the same calls the Kotlin client makes
//! (`composeApp/.../data/remote/SupabaseAuthApi.kt`).
//!
//! Sign-in is by emailed one-time code: `POST /auth/v1/otp` with `create_user: true` (so the first
//! sign-in creates the account), then `POST /auth/v1/verify` with the code. The hosted project also
//! wants a Turnstile token in `gotrue_meta_security`; [`AuthClient::request_email_code`] takes one,
//! but obtaining it (a browser challenge) is not implemented here, so hosted sign-in is not yet
//! possible from this client. Password sign-in exists for local development and tests only, as on
//! the other clients.

use serde_json::{Value, json};

use crate::http::{checked_base_url, tls_agent};
use crate::store::Session;

#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum AuthError {
    #[error("network unavailable: {0}")]
    Network(String),
    #[error("that code is wrong or has expired; ask for a new one")]
    InvalidCode,
    #[error("too many attempts; wait a little and try again")]
    RateLimited,
    #[error("this project requires a verification check that this build cannot show yet")]
    CaptchaRequired,
    #[error("the session has ended; sign in again")]
    SessionExpired,
    #[error("sign-in was refused (HTTP {status}{})", code.as_deref().map(|c| format!(", {c}")).unwrap_or_default())]
    Refused { status: u16, code: Option<String> },
    #[error("unexpected answer from the sign-in service: {0}")]
    BadReply(String),
    #[error("{0}")]
    Config(String),
}

pub struct AuthClient {
    base_url: String,
    publishable_key: String,
    agent: ureq::Agent,
}

/// Maps a GoTrue error to what the user can act on. Matches on status and `error_code`, never on
/// the message text.
pub fn classify(status: u16, body: &Value) -> AuthError {
    let code = body.get("error_code").or_else(|| body.get("error")).and_then(Value::as_str).map(str::to_string);
    match (status, code.as_deref()) {
        (429, _) | (_, Some("over_email_send_rate_limit" | "over_request_rate_limit")) => AuthError::RateLimited,
        (_, Some("captcha_failed")) => AuthError::CaptchaRequired,
        (_, Some("otp_expired" | "otp_disabled")) => AuthError::InvalidCode,
        (_, Some("refresh_token_not_found" | "refresh_token_already_used" | "session_not_found")) => {
            AuthError::SessionExpired
        }
        _ => AuthError::Refused { status, code },
    }
}

/// Reads the session GoTrue returns from `/verify`, `/token` and `/signup`.
pub fn parse_session(body: &Value, now_ms: i64) -> Result<Session, AuthError> {
    let text = |v: Option<&Value>| v.and_then(Value::as_str).map(str::to_string);
    let access_token = text(body.get("access_token")).ok_or_else(|| AuthError::BadReply("no access token".into()))?;
    let user = body.get("user").ok_or_else(|| AuthError::BadReply("no user".into()))?;
    let user_id = text(user.get("id")).ok_or_else(|| AuthError::BadReply("no user id".into()))?;
    let expires_in = body.get("expires_in").and_then(Value::as_i64).unwrap_or(3600);
    Ok(Session {
        access_token,
        refresh_token: text(body.get("refresh_token")),
        user_id,
        email: text(user.get("email")),
        expires_at_ms: now_ms + expires_in * 1000,
    })
}

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

impl AuthClient {
    pub fn new(base_url: &str, publishable_key: &str) -> Result<Self, AuthError> {
        Ok(AuthClient {
            base_url: checked_base_url(base_url).map_err(AuthError::Config)?,
            publishable_key: publishable_key.to_string(),
            agent: tls_agent().map_err(AuthError::Network)?,
        })
    }

    fn post(&self, path: &str, body: Value) -> Result<Value, AuthError> {
        let response = self
            .agent
            .post(&format!("{}/auth/v1/{path}", self.base_url))
            .set("apikey", &self.publishable_key)
            .set("Content-Type", "application/json")
            .send_json(body);
        match response {
            Ok(ok) => {
                let text = ok.into_string().map_err(|e| AuthError::BadReply(e.to_string()))?;
                if text.trim().is_empty() {
                    return Ok(Value::Null);
                }
                serde_json::from_str(&text).map_err(|e| AuthError::BadReply(e.to_string()))
            }
            Err(ureq::Error::Status(status, reply)) => Err(classify(status, &reply.into_json().unwrap_or(Value::Null))),
            Err(ureq::Error::Transport(t)) => Err(AuthError::Network(t.to_string())),
        }
    }

    /// Emails a one-time code to `email`, creating the account on first use.
    pub fn request_email_code(&self, email: &str, captcha_token: Option<&str>) -> Result<(), AuthError> {
        let mut body = json!({ "email": email.trim(), "create_user": true });
        if let Some(token) = captcha_token {
            body["gotrue_meta_security"] = json!({ "captcha_token": token });
        }
        self.post("otp", body).map(|_| ())
    }

    /// Exchanges the emailed code for a session.
    pub fn verify_email_code(&self, email: &str, code: &str) -> Result<Session, AuthError> {
        let reply = self.post("verify", json!({ "type": "email", "email": email.trim(), "token": code.trim() }))?;
        parse_session(&reply, now_ms())
    }

    /// A fresh session from the refresh token. GoTrue rotates it: the old one stops working.
    pub fn refresh(&self, refresh_token: &str) -> Result<Session, AuthError> {
        let reply = self.post("token?grant_type=refresh_token", json!({ "refresh_token": refresh_token }))?;
        parse_session(&reply, now_ms())
    }

    /// Local development and tests only: the hosted project has passwords switched off.
    pub fn sign_in_with_password(&self, email: &str, password: &str) -> Result<Session, AuthError> {
        let reply = self.post("token?grant_type=password", json!({ "email": email, "password": password }))?;
        parse_session(&reply, now_ms())
    }

    /// Local development and tests only.
    pub fn sign_up(&self, email: &str, password: &str) -> Result<Session, AuthError> {
        let reply = self.post("signup", json!({ "email": email, "password": password }))?;
        parse_session(&reply, now_ms())
    }
}
