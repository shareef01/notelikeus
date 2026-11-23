use notelikeus_core::store::{LocalState, Session};
use serde_json::{Value, json};

use super::NotesService;

impl NotesService {
    /// A support report with counts and categories only. It never includes note text, email, owner id,
    /// note ids, attachment ids, tokens, or local filesystem paths.
    pub fn diagnostics_json(&self) -> Result<String, String> {
        let notes: Vec<_> = self.state.notes.values().collect();
        let attachments: Vec<_> = self.state.attachments.values().flatten().collect();
        let pending_attachments: Vec<_> = attachments.iter().filter(|image| image.is_pending()).collect();
        let with_attachments = self
            .state
            .attachments
            .iter()
            .filter(|(id, images)| !images.is_empty() && self.state.notes.contains_key(*id))
            .count();
        let report = json!({
            "schemaVersion": 1,
            "generatedAt": now_millis(),
            "app": {
                "version": env!("CARGO_PKG_VERSION"),
                "platform": "windows",
            },
            "storage": {
                "kind": "sealed-file",
                "schemaVersion": 1,
                "available": true,
                "encryptedAtRest": true,
            },
            "account": {
                "state": if self.session.is_some() { "signed-in" } else { "guest" },
                "ownerTag": notelikeus_core::conflict::owner_tag(Some(&self.state.owner_id)),
            },
            "notes": {
                "total": notes.len(),
                "active": notes.iter().filter(|note| !note.is_archived && !note.is_trashed).count(),
                "archived": notes.iter().filter(|note| note.is_archived && !note.is_trashed).count(),
                "trashed": notes.iter().filter(|note| note.is_trashed).count(),
                "pinned": notes.iter().filter(|note| note.is_pinned).count(),
                "withReminder": notes.iter().filter(|note| note.reminder_timestamp.is_some()).count(),
                "withAttachments": with_attachments,
            },
            "sync": {
                "lastRemoteRevision": self.state.last_remote_revision,
                "wipeGeneration": self.state.wipe_generation,
                "uploadsBlockedAfterWipe": self.state.uploads_blocked_after_wipe,
                "knownCloudIdCount": self.state.known_cloud_ids.len(),
                "trackedRevisionCount": self.state.revisions.len(),
                "pendingMutationCount": self.state.pending.len(),
                "tombstoneCount": self.state.tombstones.len(),
                "pendingRestoreCount": 0,
                "lastSuccessfulSyncAt": self.last_synced_at,
                "lastErrorCategory": error_category(self.last_error.as_deref()),
            },
            "attachments": {
                "stagedCount": pending_attachments.len(),
                "stagedBytes": pending_attachments.iter().map(|image| image.size_bytes).sum::<u64>(),
                "pendingUploadCount": pending_attachments.len(),
                "unresolvedCleanupCount": self.state.attachment_removals.values().map(Vec::len).sum::<usize>(),
                "encryptedAtRest": true,
            },
        });
        let text = serde_json::to_string_pretty(&report).unwrap_or_else(|_| Value::Null.to_string());
        assert_no_sensitive_values(&text, &self.state, &self.session, &self.root)?;
        Ok(text)
    }
}

fn is_b64url(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'-' || b == b'_'
}

fn contains_jwt_pattern(text: &str) -> bool {
    let bytes = text.as_bytes();
    let mut start = 0usize;
    while let Some(offset) = text[start..].find("eyJ") {
        let idx = start + offset;
        let rest = &bytes[idx + 3..];
        if let Some(dot1) = rest.iter().position(|&b| b == b'.')
            && dot1 >= 2
            && rest[..dot1].iter().all(|&b| is_b64url(b))
        {
            let seg2 = &rest[dot1 + 1..];
            if let Some(dot2) = seg2.iter().position(|&b| b == b'.')
                && dot2 >= 5
                && seg2[..dot2].iter().all(|&b| is_b64url(b))
            {
                return true;
            }
        }
        start = idx + 3;
    }
    false
}

fn contains_uuid_pattern(text: &str) -> bool {
    let bytes = text.as_bytes();
    const GROUPS: [usize; 5] = [8, 4, 4, 4, 12];
    if bytes.len() < 36 {
        return false;
    }
    'outer: for start in 0..=bytes.len() - 36 {
        let slice = &bytes[start..start + 36];
        let mut pos = 0;
        for (gi, &len) in GROUPS.iter().enumerate() {
            if !slice[pos..pos + len].iter().all(|b| b.is_ascii_hexdigit()) {
                continue 'outer;
            }
            pos += len;
            if gi < 4 {
                if slice[pos] != b'-' {
                    continue 'outer;
                }
                pos += 1;
            }
        }
        return true;
    }
    false
}

fn assert_no_sensitive_values(
    text: &str,
    state: &LocalState,
    session: &Option<Session>,
    root: &std::path::Path,
) -> Result<(), String> {
    const FORBIDDEN_SUBSTRINGS: &[&str] = &[
        "access_token",
        "refresh_token",
        "accesstoken",
        "refreshtoken",
        "id_token",
        "idtoken",
        "apikey",
        "api_key",
        "authorization",
        "bearer ",
        "password",
        "passphrase",
        "secret",
        "private_key",
        "privatekey",
        "@",
        "owners/",
        "pending:",
        "r2:",
        "pending-attachments/",
    ];
    let lowered = text.to_ascii_lowercase();
    for needle in FORBIDDEN_SUBSTRINGS {
        if lowered.contains(needle) {
            return Err("Diagnostics report failed the privacy check.".into());
        }
    }
    if contains_jwt_pattern(text) || contains_uuid_pattern(text) {
        return Err("Diagnostics report failed the privacy check.".into());
    }

    let mut runtime_secrets: Vec<String> = Vec::new();
    let root_str = root.to_string_lossy();
    if root_str.len() >= 6 {
        runtime_secrets.push(root_str.to_string());
    }
    if state.owner_id != "__guest__" && state.owner_id.len() >= 6 {
        runtime_secrets.push(state.owner_id.clone());
    }
    if let Some(session) = session {
        if let Some(email) = &session.email
            && !email.is_empty()
        {
            runtime_secrets.push(email.clone());
        }
        if session.access_token.len() >= 8 {
            runtime_secrets.push(session.access_token.clone());
        }
        if let Some(refresh) = &session.refresh_token
            && refresh.len() >= 8
        {
            runtime_secrets.push(refresh.clone());
        }
    }
    for needle in runtime_secrets {
        let escaped = serde_json::to_string(&needle).unwrap_or_default();
        let inner = escaped.trim_matches('"');
        if text.contains(&needle) || (!inner.is_empty() && text.contains(inner)) {
            return Err("Diagnostics report failed the privacy check.".into());
        }
    }
    Ok(())
}

fn now_millis() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_millis() as i64)
        .unwrap_or(0)
}

fn error_category(error: Option<&str>) -> &'static str {
    let Some(error) = error else { return "none" };
    let error = error.to_ascii_lowercase();
    if error.contains("offline") || error.contains("network") {
        "offline"
    } else if error.contains("unauthorized") || error.contains("sign in") || error.contains("session") {
        "auth"
    } else if error.contains("429") || error.contains("rate limit") || error.contains("too many") {
        "rate_limit"
    } else if error.contains("permission") || error.contains("forbidden") {
        "permission"
    } else if error.contains("conflict") || error.contains("changed on two devices") {
        "conflict"
    } else if error.contains("wiped") || error.contains("wipe") {
        "wipe"
    } else if error.contains("too long") || error.contains("limit") || error.contains("invalid") {
        "validation"
    } else if error.contains("could not save") || error.contains("storage") || error.contains("i/o") {
        "storage"
    } else if error.contains("upstream") || error.contains("http 5") || error.contains("503") {
        "upstream"
    } else {
        "unknown"
    }
}
