use serde_json::{Value, json};

use super::NotesService;

impl NotesService {
    /// A support report with counts and categories only. It never includes note text, email, owner id,
    /// note ids, attachment ids, tokens, or local filesystem paths.
    pub fn diagnostics_json(&self) -> String {
        let notes: Vec<_> = self.state.notes.values().collect();
        let attachments: Vec<_> = self.state.attachments.values().flatten().collect();
        let pending_attachments: Vec<_> = attachments.iter().filter(|image| image.is_pending()).collect();
        let report = json!({
            "schemaVersion": 1,
            "generatedAt": now_millis(),
            "app": {
                "version": env!("CARGO_PKG_VERSION"),
                "platform": "windows",
            },
            "storage": {
                "kind": "sealed-file",
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
                "withAttachments": self.state.attachments.values().filter(|images| !images.is_empty()).count(),
            },
            "sync": {
                "lastRemoteRevision": self.state.last_remote_revision,
                "knownCloudIdCount": self.state.revisions.len(),
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
        serde_json::to_string_pretty(&report).unwrap_or_else(|_| Value::Null.to_string())
    }
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
    } else if error.contains("permission") || error.contains("forbidden") {
        "permission"
    } else if error.contains("conflict") || error.contains("changed on two devices") {
        "conflict"
    } else if error.contains("upstream") || error.contains("http 5") || error.contains("503") {
        "upstream"
    } else {
        "unknown"
    }
}
