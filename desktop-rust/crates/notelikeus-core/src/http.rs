//! The Supabase transport: PostgREST RPCs over HTTPS with the user's access token.
//!
//! TLS is Windows SChannel (`native-tls`), so nothing here needs a C compiler. It sends exactly the
//! argument objects pinned by `contracts/cloud/note-rpc-args.json` and reads the answers the
//! migrations return. It holds no service-role key and never could: it is a user client.

use serde::Deserialize;
use serde_json::{Value, json};

use crate::crypto;
use crate::model::{ApplyNoteChangeArgs, CloudChange, CloudNoteRow, CloudTombstoneRow, Note};
use crate::sync::{ApplyOutcome, DeleteOutcome, PullPage, Transport, TransportError};

/// The project URL without a trailing slash, if it is HTTPS, or plain HTTP to this machine (a local
/// stack). The host is compared strictly: userinfo (`@`), whitespace, control characters, and
/// `http://localhost.example.com` are refused.
pub fn checked_base_url(base_url: &str) -> Result<String, String> {
    let base = base_url.trim().trim_end_matches('/');
    if base.bytes().any(|b| b <= 0x20 || b == 0x7F || matches!(b, b'\\' | b'"' | b'\'' | b'<' | b'>')) {
        return Err("refusing a Supabase URL with invalid characters".into());
    }
    let (is_http, rest) = if let Some(rest) = base.strip_prefix("https://") {
        (false, rest)
    } else if let Some(rest) = base.strip_prefix("http://") {
        (true, rest)
    } else {
        return Err("refusing a non-HTTPS Supabase URL".into());
    };
    let authority = rest.split(['/', '?', '#']).next().unwrap_or_default();
    if authority.is_empty() || authority.contains('@') {
        return Err("refusing a URL without a valid host".into());
    }
    let (host, port_part) = if let Some(v6_rest) = authority.strip_prefix('[') {
        let Some((addr, after_bracket)) = v6_rest.split_once(']') else {
            return Err("the Supabase URL has a malformed IPv6 host".into());
        };
        if addr.is_empty() {
            return Err("the Supabase URL has no host".into());
        }
        let port = if let Some(p) = after_bracket.strip_prefix(':') {
            Some(p)
        } else if after_bracket.is_empty() {
            None
        } else {
            return Err("the Supabase URL has a malformed host".into());
        };
        (format!("[{addr}]"), port)
    } else if let Some((h, p)) = authority.split_once(':') {
        if h.is_empty() || p.contains(':') {
            return Err("the Supabase URL has a malformed host".into());
        }
        (h.to_string(), Some(p))
    } else {
        (authority.to_string(), None)
    };
    if host.is_empty() {
        return Err("the Supabase URL has no host".into());
    }
    if let Some(port) = port_part {
        let parsed: u16 = port.parse().map_err(|_| "the Supabase URL has an invalid port".to_string())?;
        if parsed == 0 {
            return Err("the Supabase URL has an invalid port".into());
        }
    }
    if is_http {
        match host.to_ascii_lowercase().as_str() {
            "127.0.0.1" | "localhost" | "[::1]" => Ok(base.to_string()),
            _ => Err("refusing a non-HTTPS Supabase URL".into()),
        }
    } else {
        Ok(base.to_string())
    }
}

fn decode_base64url_segment(segment: &str) -> Option<Vec<u8>> {
    let val = |b: u8| -> Option<u32> {
        match b {
            b'A'..=b'Z' => Some(u32::from(b - b'A')),
            b'a'..=b'z' => Some(u32::from(b - b'a' + 26)),
            b'0'..=b'9' => Some(u32::from(b - b'0' + 52)),
            b'-' => Some(62),
            b'_' => Some(63),
            _ => None,
        }
    };
    let clean = segment.trim_end_matches('=').as_bytes();
    let mut out = Vec::with_capacity(clean.len() * 3 / 4);
    let mut i = 0;
    while i < clean.len() {
        let rem = clean.len() - i;
        if rem == 1 {
            return None;
        }
        let a = val(clean[i])?;
        let b = val(clean[i + 1])?;
        out.push(((a << 2) | (b >> 4)) as u8);
        if rem >= 3 {
            let c = val(clean[i + 2])?;
            out.push((((b & 0x0f) << 4) | (c >> 2)) as u8);
            if rem >= 4 {
                let d = val(clean[i + 3])?;
                out.push((((c & 0x03) << 6) | d) as u8);
            }
        }
        i += 4;
    }
    Some(out)
}

/// Validates that a configured Supabase client API key is not a secret or `service_role` key (D31).
pub fn checked_publishable_key(key: &str) -> Result<String, String> {
    let trimmed = key.trim();
    if trimmed.is_empty() {
        return Err("the Supabase key is empty".into());
    }
    if trimmed.to_ascii_lowercase().starts_with("sb_secret_") {
        return Err("refusing a secret Supabase key in the desktop client".into());
    }
    let mut parts = trimmed.split('.');
    if let (Some(_), Some(payload_b64), Some(_), None) = (parts.next(), parts.next(), parts.next(), parts.next())
        && let Some(bytes) = decode_base64url_segment(payload_b64)
        && let Ok(payload) = serde_json::from_slice::<Value>(&bytes)
        && payload.get("role").and_then(Value::as_str) == Some("service_role")
    {
        return Err("refusing a service_role Supabase key in the desktop client".into());
    }
    Ok(trimmed.to_string())
}

/// One HTTPS agent: Windows SChannel through `native-tls`, 30-second timeout, zero redirects.
pub(crate) fn tls_agent() -> Result<ureq::Agent, String> {
    let tls = native_tls::TlsConnector::new().map_err(|e| e.to_string())?;
    Ok(ureq::AgentBuilder::new()
        .tls_connector(std::sync::Arc::new(tls))
        .timeout(std::time::Duration::from_secs(30))
        .redirects(0)
        .build())
}

pub struct SupabaseTransport {
    base_url: String,
    publishable_key: String,
    access_token: String,
    agent: ureq::Agent,
}

impl Drop for SupabaseTransport {
    fn drop(&mut self) {
        crypto::wipe_string(&mut self.access_token);
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SnapshotData {
    pub notes: Vec<CloudNoteRow>,
    pub tombstones: Vec<CloudTombstoneRow>,
    pub wipe_generation: i64,
    pub head_revision: Option<i64>,
    pub authoritative_note_count: Option<usize>,
}

#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
pub struct PendingDeletedAttachment {
    pub attachment_id: String,
    pub note_id: String,
    pub object_key: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
struct SnapshotState {
    head_revision: i64,
    wipe_generation: i64,
    note_count: i64,
    tombstone_count: i64,
}

#[derive(Deserialize)]
struct SnapshotNotesPage {
    #[serde(default)]
    items: Vec<CloudNoteRow>,
    next_after: Option<String>,
    state: SnapshotState,
}

#[derive(Deserialize)]
struct SnapshotTombstonesPage {
    #[serde(default)]
    items: Vec<CloudTombstoneRow>,
    next_after: Option<String>,
    state: SnapshotState,
}

#[derive(Deserialize)]
struct LegacyFullSnapshot {
    #[serde(default)]
    notes: Vec<CloudNoteRow>,
    #[serde(default)]
    tombstones: Vec<CloudTombstoneRow>,
    #[serde(default)]
    note_count: Option<i64>,
    #[serde(default)]
    wipe_generation: i64,
}

impl SupabaseTransport {
    /// `base_url` must be `https://` (a plain-HTTP URL is accepted only for a local stack on loopback).
    pub fn new(base_url: &str, publishable_key: &str, access_token: &str) -> Result<Self, TransportError> {
        let base = checked_base_url(base_url).map_err(TransportError::Server)?;
        let key = checked_publishable_key(publishable_key).map_err(TransportError::Server)?;
        let agent = tls_agent().map_err(TransportError::Network)?;
        Ok(SupabaseTransport { base_url: base, publishable_key: key, access_token: access_token.to_string(), agent })
    }

    pub fn set_access_token(&mut self, token: &str) {
        crypto::wipe_string(&mut self.access_token);
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

    /// Reads the account's current wipe generation (`get_sync_generation`).
    pub fn get_sync_generation(&self) -> Result<i64, TransportError> {
        let value = self.rpc("get_sync_generation", &json!({}))?;
        value
            .get("wipe_generation")
            .and_then(Value::as_i64)
            .ok_or_else(|| TransportError::Server("get_sync_generation missing wipe_generation".into()))
    }

    /// Whether the authenticated account has entered the deletion flow (NL003).
    pub fn account_is_deleted(&self) -> Result<bool, TransportError> {
        match self.get_sync_generation() {
            Ok(_) => Ok(false),
            Err(TransportError::AccountDeleted) => Ok(true),
            Err(error) => Err(error),
        }
    }

    /// Deletes all cloud notes and increments `wipe_generation`. Requires a recent sign-in (NL004).
    pub fn delete_all_user_cloud_data(&self) -> Result<i64, TransportError> {
        let value = self.rpc("delete_all_user_cloud_data", &json!({}))?;
        value
            .get("wipe_generation")
            .and_then(Value::as_i64)
            .ok_or_else(|| TransportError::Server("delete_all_user_cloud_data missing wipe_generation".into()))
    }

    /// Finalizes confirmed attachment deletes queued during a cloud wipe.
    pub fn finalize_cloud_wipe(&self) -> Result<(), TransportError> {
        let _ = self.rpc("finalize_cloud_wipe", &json!({}))?;
        Ok(())
    }

    /// Fetches a consistent snapshot of all cloud notes and tombstones via `fetch_snapshot_page`,
    /// falling back to `fetch_full_snapshot` only when the paged RPC is absent (PGRST202).
    pub fn fetch_full_snapshot_rows(&self) -> Result<SnapshotData, TransportError> {
        for _attempt in 0..3 {
            let mut expected_state: Option<SnapshotState> = None;
            let mut notes = Vec::new();
            let mut after_note: Option<String> = None;
            let mut consistent = true;
            let mut fallback = false;
            loop {
                let args = json!({ "p_kind": "notes", "p_after": after_note, "p_limit": 500 });
                let raw = match self.rpc("fetch_snapshot_page", &args) {
                    Ok(v) => v,
                    Err(TransportError::Server(msg)) if msg.contains("PGRST202") || msg.contains("HTTP 404") => {
                        fallback = true;
                        break;
                    }
                    Err(err) => return Err(err),
                };
                let page: SnapshotNotesPage = serde_json::from_value(raw)
                    .map_err(|e| TransportError::Server(format!("bad snapshot notes page: {e}")))?;
                match &expected_state {
                    None => expected_state = Some(page.state.clone()),
                    Some(prev) if prev != &page.state => {
                        consistent = false;
                        break;
                    }
                    _ => {}
                }
                notes.extend(page.items);
                match page.next_after {
                    Some(cursor) => after_note = Some(cursor),
                    None => break,
                }
            }
            if fallback {
                let raw = self.rpc("fetch_full_snapshot", &json!({}))?;
                let full: LegacyFullSnapshot = serde_json::from_value(raw)
                    .map_err(|e| TransportError::Server(format!("bad full snapshot: {e}")))?;
                let expected = full.note_count.and_then(|n| usize::try_from(n).ok());
                if let Some(count) = expected
                    && count != full.notes.len()
                {
                    return Err(TransportError::Server("incomplete cloud snapshot".into()));
                }
                return Ok(SnapshotData {
                    notes: full.notes,
                    tombstones: full.tombstones,
                    wipe_generation: full.wipe_generation,
                    head_revision: None,
                    authoritative_note_count: expected,
                });
            }
            if !consistent {
                continue;
            }
            let mut tombstones = Vec::new();
            let mut after_tomb: Option<String> = None;
            loop {
                let args = json!({ "p_kind": "tombstones", "p_after": after_tomb, "p_limit": 500 });
                let raw = self.rpc("fetch_snapshot_page", &args)?;
                let page: SnapshotTombstonesPage = serde_json::from_value(raw)
                    .map_err(|e| TransportError::Server(format!("bad snapshot tombstones page: {e}")))?;
                match &expected_state {
                    Some(prev) if prev != &page.state => {
                        consistent = false;
                        break;
                    }
                    _ => {}
                }
                tombstones.extend(page.items);
                match page.next_after {
                    Some(cursor) => after_tomb = Some(cursor),
                    None => break,
                }
            }
            if !consistent {
                continue;
            }
            let state = expected_state.ok_or_else(|| TransportError::Server("snapshot returned no state".into()))?;
            let expected_notes = usize::try_from(state.note_count).unwrap_or(0);
            let expected_tombstones = usize::try_from(state.tombstone_count).unwrap_or(0);
            if notes.len() != expected_notes || tombstones.len() != expected_tombstones {
                return Err(TransportError::Server("incomplete cloud snapshot".into()));
            }
            return Ok(SnapshotData {
                notes,
                tombstones,
                wipe_generation: state.wipe_generation,
                head_revision: Some(state.head_revision),
                authoritative_note_count: Some(expected_notes),
            });
        }
        Err(TransportError::Server("cloud snapshot kept changing across pages".into()))
    }

    /// Restores a deleted note atomically on the server via the 14-arg `restore_note` RPC.
    pub fn restore_note(&self, note: &Note, base_revision: Option<i64>) -> Result<ApplyOutcome, TransportError> {
        let labels: Vec<_> = note.labels.iter().map(|name| json!({ "name": name })).collect();
        let body = json!({
            "p_note_id": note.id,
            "p_local_id": note.local_id,
            "p_base_revision": base_revision,
            "p_title": note.title,
            "p_content": note.content,
            "p_client_timestamp": note.timestamp,
            "p_color": note.color,
            "p_is_pinned": note.is_pinned,
            "p_is_archived": note.is_archived,
            "p_is_trashed": note.is_trashed,
            "p_position": note.position,
            "p_reminder_timestamp": note.reminder_timestamp,
            "p_labels": labels,
            "p_checklist": note.checklist,
        });
        parse_apply_reply(self.rpc("restore_note", &body)?)
    }

    /// Attachments for tombstoned notes awaiting client GC.
    pub fn list_pending_deleted_attachments(&self) -> Result<Vec<PendingDeletedAttachment>, TransportError> {
        let value = self.rpc("list_pending_deleted_attachments", &json!({}))?;
        serde_json::from_value(value).map_err(|e| TransportError::Server(format!("bad pending deletes: {e}")))
    }

    /// Purges a confirmed-deleted attachment metadata row for a non-live note.
    pub fn purge_deleted_note_attachment(&self, note_id: &str, attachment_id: &str) -> Result<(), TransportError> {
        let _ = self
            .rpc("purge_deleted_note_attachment", &json!({ "p_attachment_id": attachment_id, "p_note_id": note_id }))?;
        Ok(())
    }
}

/// Maps a PostgREST error to what the engine needs to know. Matches on the SQLSTATE `code`, never on
/// message text: a conflict message can embed a note title.
pub fn classify_error(status: u16, body: &Value) -> TransportError {
    match body.get("code").and_then(Value::as_str) {
        Some("NL001") | Some("NL002") => TransportError::WipeGenerationStale,
        Some("NL003") => TransportError::AccountDeleted,
        Some("NL004") => TransportError::ReauthenticationRequired,
        Some("NL005") => TransportError::AccountNoteLimit,
        Some("22023") => TransportError::PayloadInvalid(format!("HTTP {status}, code 22023")),
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

impl SupabaseTransport {
    /// The account's committed images (`list_user_attachments`): every note's, in upload order.
    pub fn list_user_attachments(&self) -> Result<Vec<crate::attachments::RemoteAttachment>, TransportError> {
        let value = self.rpc("list_user_attachments", &json!({}))?;
        let rows: Vec<crate::attachments::RemoteAttachment> = serde_json::from_value(value)
            .map_err(|e| TransportError::Server(format!("unreadable attachment list: {e}")))?;
        Ok(rows.into_iter().filter(|r| !r.attachment_id.is_empty() && !r.note_id.is_empty()).collect())
    }
}

// --- The attachments Worker -----------------------------------------------------------------------------

/// What can go wrong talking to the attachments Worker.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum AttachmentError {
    #[error("the sign-in was refused")]
    Unauthorized,
    #[error("the image is not there")]
    NotFound,
    #[error("{0}")]
    Refused(String),
    #[error("network: {0}")]
    Network(String),
}

/// The account-deletion Worker can refuse before deletion, require recent sign-in, or leave an
/// ambiguous network outcome that must be checked through Supabase before local data is cleared.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum AccountDeletionError {
    #[error("the sign-in has ended; sign in again to delete your account")]
    Unauthorized,
    #[error("sign in again to confirm account deletion, then retry")]
    ReauthenticationRequired,
    #[error("account deletion was refused (HTTP {0}); nothing was deleted")]
    Refused(u16),
    #[error("the deletion request may have reached the server")]
    OutcomeUnknown,
}

/// The Worker's answer to an upload.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UploadReply {
    pub object_key: Option<String>,
    pub size_bytes: Option<u64>,
    pub mime_type: Option<String>,
}

/// The attachments Worker (`workers/attachments`): `PUT /v2/attachments/<note>/<id>` stores an image as a
/// provisional upload, `GET`/`DELETE /v1/attachments/<note>/<id>` read and delete it. Authorized by the
/// user's Supabase access token; the Worker derives the owner from it.
pub struct AttachmentsClient {
    base_url: String,
    access_token: String,
    agent: ureq::Agent,
    /// Waits before each retry of a request the Worker throttled (429) without a usable Retry-After.
    pub rate_limit_waits: Vec<std::time::Duration>,
}

impl Drop for AttachmentsClient {
    fn drop(&mut self) {
        crypto::wipe_string(&mut self.access_token);
    }
}

const MAX_RATE_LIMIT_WAIT: std::time::Duration = std::time::Duration::from_secs(60);

fn worker_path(version: &str, note_id: &str, attachment_id: &str) -> Result<String, AttachmentError> {
    if !crate::attachments::valid_segment(note_id) || !crate::attachments::valid_segment(attachment_id) {
        return Err(AttachmentError::Refused("invalid attachment path".into()));
    }
    Ok(format!("/{version}/attachments/{note_id}/{attachment_id}"))
}

impl AttachmentsClient {
    /// `base_url` must be `https://`, or plain HTTP to this machine (a local Worker).
    pub fn new(base_url: &str, access_token: &str) -> Result<Self, AttachmentError> {
        let base = checked_base_url(base_url).map_err(AttachmentError::Refused)?;
        let agent = tls_agent().map_err(AttachmentError::Network)?;
        Ok(AttachmentsClient {
            base_url: base,
            access_token: access_token.to_string(),
            agent,
            rate_limit_waits: vec![std::time::Duration::from_secs(15), std::time::Duration::from_secs(45)],
        })
    }

    pub fn set_access_token(&mut self, token: &str) {
        crypto::wipe_string(&mut self.access_token);
        self.access_token = token.to_string();
    }

    /// Deletes the authenticated account through the Worker. Network and server errors remain
    /// ambiguous so the app can ask `get_sync_generation` before removing local data.
    pub fn delete_account(&self) -> Result<(), AccountDeletionError> {
        let url = format!("{}/v1/account", self.base_url);
        let mut waits = self.rate_limit_waits.iter();
        loop {
            match self.agent.delete(&url).set("Authorization", &format!("Bearer {}", self.access_token)).call() {
                Ok(response) => {
                    let status = response.status();
                    if status != 200 && status != 202 {
                        return Err(AccountDeletionError::Refused(status));
                    }
                    let reply = response.into_json::<Value>().map_err(|_| AccountDeletionError::OutcomeUnknown)?;
                    match reply.get("status").and_then(Value::as_str) {
                        Some("deleted" | "pending") => return Ok(()),
                        _ => return Err(AccountDeletionError::OutcomeUnknown),
                    }
                }
                Err(ureq::Error::Status(429, response)) => match waits.next() {
                    Some(fallback) => {
                        let wait = response
                            .header("Retry-After")
                            .and_then(|value| value.trim().parse::<u64>().ok())
                            .filter(|seconds| *seconds > 0)
                            .map_or(*fallback, std::time::Duration::from_secs)
                            .min(MAX_RATE_LIMIT_WAIT);
                        std::thread::sleep(wait);
                    }
                    None => return Err(AccountDeletionError::Refused(429)),
                },
                Err(ureq::Error::Status(401, _)) => return Err(AccountDeletionError::Unauthorized),
                Err(ureq::Error::Status(403, response)) => {
                    let body = response.into_string().unwrap_or_default();
                    if body.contains("reauthentication_required") {
                        return Err(AccountDeletionError::ReauthenticationRequired);
                    }
                    return Err(AccountDeletionError::Refused(403));
                }
                Err(ureq::Error::Status(status, _)) if status >= 500 => {
                    return Err(AccountDeletionError::OutcomeUnknown);
                }
                Err(ureq::Error::Status(status, _)) => return Err(AccountDeletionError::Refused(status)),
                Err(ureq::Error::Transport(_)) => return Err(AccountDeletionError::OutcomeUnknown),
            }
        }
    }

    /// Sends a request, waiting and sending it again when the Worker answers 429, at most twice.
    fn send(
        &self,
        request: impl Fn() -> Result<ureq::Response, Box<ureq::Error>>,
    ) -> Result<ureq::Response, AttachmentError> {
        let mut waits = self.rate_limit_waits.iter();
        loop {
            match request() {
                Ok(response) => return Ok(response),
                Err(error) => match *error {
                    ureq::Error::Status(429, response) => match waits.next() {
                        Some(fallback) => {
                            let wait = response
                                .header("Retry-After")
                                .and_then(|s| s.trim().parse::<u64>().ok())
                                .filter(|s| *s > 0)
                                .map_or(*fallback, std::time::Duration::from_secs)
                                .min(MAX_RATE_LIMIT_WAIT);
                            std::thread::sleep(wait);
                        }
                        None => {
                            return Err(AttachmentError::Refused(
                                "Too many image requests right now. Try again in a minute.".into(),
                            ));
                        }
                    },
                    ureq::Error::Status(status, _) => {
                        return Err(match status {
                            401 => AttachmentError::Unauthorized,
                            404 => AttachmentError::NotFound,
                            409 => AttachmentError::Refused("That image was deleted.".into()),
                            413 => AttachmentError::Refused("The image is larger than 10 MB.".into()),
                            415 => AttachmentError::Refused("That kind of file can't be attached.".into()),
                            403 => {
                                AttachmentError::Refused("The image was refused (storage limit or permission).".into())
                            }
                            other => AttachmentError::Refused(format!("The image service answered HTTP {other}.")),
                        });
                    }
                    ureq::Error::Transport(t) => return Err(AttachmentError::Network(t.to_string())),
                },
            }
        }
    }

    /// Stores an image as a provisional upload; the note commit that names it makes it permanent.
    pub fn upload(
        &self,
        note_id: &str,
        attachment_id: &str,
        mime_type: &str,
        bytes: &[u8],
    ) -> Result<UploadReply, AttachmentError> {
        let url = format!("{}{}", self.base_url, worker_path("v2", note_id, attachment_id)?);
        let response = self.send(|| {
            self.agent
                .put(&url)
                .set("Authorization", &format!("Bearer {}", self.access_token))
                .set("Content-Type", mime_type)
                .send_bytes(bytes)
                .map_err(Box::new)
        })?;
        response.into_json::<UploadReply>().map_err(|e| AttachmentError::Network(format!("unreadable reply: {e}")))
    }

    pub fn download(&self, note_id: &str, attachment_id: &str) -> Result<Vec<u8>, AttachmentError> {
        let url = format!("{}{}", self.base_url, worker_path("v1", note_id, attachment_id)?);
        let response = self.send(|| {
            self.agent.get(&url).set("Authorization", &format!("Bearer {}", self.access_token)).call().map_err(Box::new)
        })?;
        let mut bytes = Vec::new();
        std::io::Read::read_to_end(
            &mut std::io::Read::take(response.into_reader(), crate::attachments::MAX_ATTACHMENT_BYTES as u64 + 1),
            &mut bytes,
        )
        .map_err(|e| AttachmentError::Network(e.to_string()))?;
        if bytes.len() > crate::attachments::MAX_ATTACHMENT_BYTES {
            return Err(AttachmentError::Refused("The image is larger than 10 MB.".into()));
        }
        Ok(bytes)
    }

    pub fn delete(&self, note_id: &str, attachment_id: &str) -> Result<(), AttachmentError> {
        let url = format!("{}{}", self.base_url, worker_path("v1", note_id, attachment_id)?);
        match self.send(|| {
            self.agent
                .delete(&url)
                .set("Authorization", &format!("Bearer {}", self.access_token))
                .call()
                .map_err(Box::new)
        }) {
            // Already gone is what a delete wants.
            Ok(_) | Err(AttachmentError::NotFound) => Ok(()),
            Err(other) => Err(other),
        }
    }
}
