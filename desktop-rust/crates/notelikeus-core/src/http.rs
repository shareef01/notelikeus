//! The Supabase transport: PostgREST RPCs over HTTPS with the user's access token.
//!
//! TLS is Windows SChannel (`native-tls`), so nothing here needs a C compiler. It sends exactly the
//! argument objects pinned by `contracts/cloud/note-rpc-args.json` and reads the answers the
//! migrations return. It holds no service-role key and never could: it is a user client.

use serde::Deserialize;
use serde_json::{Value, json};

use crate::model::{ApplyNoteChangeArgs, CloudChange, CloudNoteRow};
use crate::sync::{ApplyOutcome, DeleteOutcome, PullPage, Transport, TransportError};

/// The project URL without a trailing slash, if it is HTTPS, or plain HTTP to this machine (a local
/// stack). The host is compared exactly: `http://localhost.example.com` is not loopback.
pub fn checked_base_url(base_url: &str) -> Result<String, String> {
    let base = base_url.trim().trim_end_matches('/');
    if let Some(rest) = base.strip_prefix("https://") {
        if rest.is_empty() {
            return Err("the Supabase URL has no host".into());
        }
        return Ok(base.to_string());
    }
    let rest = base.strip_prefix("http://").ok_or("refusing a non-HTTPS Supabase URL")?;
    let authority = rest.split('/').next().unwrap_or_default();
    let host = if let Some(v6) = authority.strip_prefix('[') {
        v6.split(']').next().map(|h| format!("[{h}]")).unwrap_or_default()
    } else {
        authority.split(':').next().unwrap_or_default().to_string()
    };
    match host.as_str() {
        "127.0.0.1" | "localhost" | "[::1]" => Ok(base.to_string()),
        _ => Err("refusing a non-HTTPS Supabase URL".into()),
    }
}

/// One HTTPS agent: Windows SChannel through `native-tls`, 30-second timeout.
pub(crate) fn tls_agent() -> Result<ureq::Agent, String> {
    let tls = native_tls::TlsConnector::new().map_err(|e| e.to_string())?;
    Ok(ureq::AgentBuilder::new()
        .tls_connector(std::sync::Arc::new(tls))
        .timeout(std::time::Duration::from_secs(30))
        .build())
}

pub struct SupabaseTransport {
    base_url: String,
    publishable_key: String,
    access_token: String,
    agent: ureq::Agent,
}

impl SupabaseTransport {
    /// `base_url` must be `https://` (a plain-HTTP URL is accepted only for a local stack on loopback).
    pub fn new(base_url: &str, publishable_key: &str, access_token: &str) -> Result<Self, TransportError> {
        let base = checked_base_url(base_url).map_err(TransportError::Server)?;
        let agent = tls_agent().map_err(TransportError::Network)?;
        Ok(SupabaseTransport {
            base_url: base,
            publishable_key: publishable_key.to_string(),
            access_token: access_token.to_string(),
            agent,
        })
    }

    pub fn set_access_token(&mut self, token: &str) {
        self.access_token = token.to_string();
    }

    fn rpc(&self, name: &str, body: &Value) -> Result<Value, TransportError> {
        let url = format!("{}/rest/v1/rpc/{name}", self.base_url);
        let response = self
            .agent
            .post(&url)
            .set("apikey", &self.publishable_key)
            .set("Authorization", &format!("Bearer {}", self.access_token))
            .set("Content-Type", "application/json")
            .send_json(body.clone());
        match response {
            Ok(ok) => ok.into_json::<Value>().map_err(|e| TransportError::Server(format!("unreadable reply: {e}"))),
            Err(ureq::Error::Status(status, reply)) => {
                let body: Value = reply.into_json().unwrap_or(Value::Null);
                Err(classify_error(status, &body))
            }
            Err(ureq::Error::Transport(t)) => Err(TransportError::Network(t.to_string())),
        }
    }
}

/// Maps a PostgREST error to what the engine needs to know. Matches on the SQLSTATE `code`, never on
/// message text: a conflict message can embed a note title.
pub fn classify_error(status: u16, body: &Value) -> TransportError {
    match body.get("code").and_then(Value::as_str) {
        Some("NL002") => TransportError::WipeGenerationStale,
        Some("NL003") => TransportError::AccountDeleted,
        Some("PGRST301") | Some("PGRST302") => TransportError::Unauthorized,
        _ if status == 401 || status == 403 => TransportError::Unauthorized,
        Some(code) => TransportError::Server(format!("HTTP {status}, code {code}")),
        None => TransportError::Server(format!("HTTP {status}")),
    }
}

#[derive(Deserialize)]
struct ApplyReply {
    status: Option<String>,
    revision: Option<i64>,
    server_updated_at: Option<i64>,
    error: Option<String>,
    current: Option<CloudNoteRow>,
    idempotent: Option<bool>,
}

pub fn parse_apply_reply(value: Value) -> Result<ApplyOutcome, TransportError> {
    let reply: ApplyReply =
        serde_json::from_value(value).map_err(|e| TransportError::Server(format!("bad reply: {e}")))?;
    match reply.status.as_deref() {
        Some("applied") => match reply.revision {
            Some(revision) => Ok(ApplyOutcome::Applied { revision, server_updated_at: reply.server_updated_at }),
            None => Err(TransportError::Server("applied without a revision".into())),
        },
        Some("conflict") => match (reply.error.as_deref(), reply.current) {
            (Some("note_deleted"), _) => Ok(ApplyOutcome::Deleted),
            (_, Some(current)) => Ok(ApplyOutcome::Conflict { current: Box::new(current) }),
            (Some("note_not_found"), None) => Ok(ApplyOutcome::NotFound),
            _ => Err(TransportError::Server("conflict without detail".into())),
        },
        _ => Err(TransportError::Server("unexpected apply_note_change reply".into())),
    }
}

pub fn parse_delete_reply(value: Value) -> Result<DeleteOutcome, TransportError> {
    let reply: ApplyReply =
        serde_json::from_value(value).map_err(|e| TransportError::Server(format!("bad reply: {e}")))?;
    if reply.idempotent == Some(true) {
        return Ok(DeleteOutcome::AlreadyGone);
    }
    match reply.status.as_deref() {
        Some("applied") => Ok(DeleteOutcome::Deleted),
        Some("conflict") => match (reply.error.as_deref(), reply.current) {
            (Some("note_not_found"), _) => Ok(DeleteOutcome::AlreadyGone),
            (_, Some(current)) => Ok(DeleteOutcome::Conflict { current: Box::new(current) }),
            _ => Err(TransportError::Server("delete conflict without detail".into())),
        },
        _ => Err(TransportError::Server("unexpected apply_note_delete reply".into())),
    }
}

#[derive(Deserialize)]
struct PullReply {
    #[serde(default)]
    changes: Vec<CloudChange>,
    #[serde(default)]
    has_more: bool,
    #[serde(default)]
    wipe_generation: i64,
}

pub fn parse_pull_reply(value: Value) -> Result<PullPage, TransportError> {
    let reply: PullReply =
        serde_json::from_value(value).map_err(|e| TransportError::Server(format!("bad reply: {e}")))?;
    Ok(PullPage { changes: reply.changes, has_more: reply.has_more, wipe_generation: reply.wipe_generation })
}

impl Transport for SupabaseTransport {
    fn apply_note_change(&mut self, args: &ApplyNoteChangeArgs) -> Result<ApplyOutcome, TransportError> {
        let body = serde_json::to_value(args).map_err(|e| TransportError::Server(e.to_string()))?;
        parse_apply_reply(self.rpc("apply_note_change", &body)?)
    }

    fn apply_note_delete(
        &mut self,
        note_id: &str,
        base_revision: Option<i64>,
        wipe_generation: i64,
    ) -> Result<DeleteOutcome, TransportError> {
        let body =
            json!({ "p_note_id": note_id, "p_base_revision": base_revision, "p_wipe_generation": wipe_generation });
        parse_delete_reply(self.rpc("apply_note_delete", &body)?)
    }

    fn pull_changes(&mut self, after_revision: i64, limit: u32) -> Result<PullPage, TransportError> {
        let body = json!({ "p_after_revision": after_revision, "p_limit": limit });
        parse_pull_reply(self.rpc("pull_changes", &body)?)
    }
}
