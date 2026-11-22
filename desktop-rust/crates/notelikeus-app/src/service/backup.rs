use std::collections::{BTreeMap, HashMap};

use notelikeus_core::attachments::{self, Attachment, Location, MAX_ATTACHMENT_BYTES};
use notelikeus_core::model::{ChecklistItem, Note};
use notelikeus_core::sync;
use serde::Serialize;
use serde_json::{Map, Value};

use super::{
    MAX_CHECKLIST_ITEMS, MAX_CONTENT_CHARS, MAX_LABEL_CHARS, MAX_LABELS_PER_NOTE, MAX_TITLE_CHARS, NotesService,
    label_key, tidy_label,
};

const MAX_JSON_BYTES: usize = 25 * 1024 * 1024;
const MAX_JSON_DEPTH: usize = 64;
const MAX_BACKUP_NOTES: usize = 25_000;
const MAX_BACKUP_LABELS: usize = 2_000;

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct BackupImportReport {
    pub notes_imported: usize,
    pub labels_created: usize,
    /// Old id from the backup to the newly assigned local id.
    pub new_note_id_by_old_id: HashMap<String, String>,
    pub attachments_imported: usize,
    pub attachments_skipped: usize,
    pub warnings: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BackupAttachmentSource {
    pub note_id: i64,
    pub attachment_id: String,
    pub mime_type: String,
    pub bytes: Vec<u8>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct BackupDocument {
    version: u8,
    exported_at: i64,
    app: &'static str,
    app_version: &'static str,
    labels: Vec<BackupLabel>,
    notes: Vec<BackupNote>,
}

#[derive(Serialize)]
struct BackupLabel {
    id: usize,
    name: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct BackupNote {
    id: i64,
    title: String,
    content: String,
    timestamp: i64,
    color: i32,
    is_pinned: bool,
    is_archived: bool,
    is_trashed: bool,
    position: i64,
    reminder_timestamp: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    server_updated_at: Option<i64>,
    labels: Vec<String>,
    checklist: Vec<ChecklistItem>,
}

impl NotesService {
    /// Serializes all local notes, including archived and trashed notes, as a v3 backup.
    pub fn export_backup_v3(&self) -> Result<Vec<u8>, String> {
        let mut notes: Vec<&Note> = self.state.notes.values().collect();
        notes.sort_by(|a, b| a.position.cmp(&b.position).then(a.id.cmp(&b.id)));
        if notes.len() > MAX_BACKUP_NOTES {
            return Err(format!("Cannot export backup: there are more than {MAX_BACKUP_NOTES} notes."));
        }

        let mut estimated_size = 512usize.saturating_add(notes.len().saturating_mul(256));
        for note in &notes {
            if note.title.chars().count() > MAX_TITLE_CHARS
                || note.content.chars().count() > MAX_CONTENT_CHARS
                || note.labels.len() > MAX_LABELS_PER_NOTE
                || note.checklist.len() > MAX_CHECKLIST_ITEMS
                || note.labels.iter().any(|name| name.chars().count() > MAX_LABEL_CHARS)
                || note.checklist.iter().any(|item| item.text.chars().count() > MAX_TITLE_CHARS)
            {
                return Err(format!("Cannot export backup: note {} exceeds a backup limit.", note.id));
            }
            estimated_size = estimated_size
                .saturating_add(json_string_size(&note.title))
                .saturating_add(json_string_size(&note.content))
                .saturating_add(note.labels.len().saturating_mul(32))
                .saturating_add(note.checklist.len().saturating_mul(128));
            for name in &note.labels {
                estimated_size = estimated_size.saturating_add(json_string_size(name));
            }
            for item in &note.checklist {
                estimated_size = estimated_size.saturating_add(json_string_size(&item.text));
            }
            if estimated_size > MAX_JSON_BYTES {
                return Err("Cannot export backup: the JSON file would exceed 25 MB.".into());
            }
        }

        let mut label_map = BTreeMap::<String, String>::new();
        for label in self.state.label_registry.iter().chain(notes.iter().flat_map(|note| note.labels.iter())) {
            let name = tidy_label(label);
            if name.is_empty() {
                continue;
            }
            if name.chars().count() > MAX_LABEL_CHARS {
                return Err("Cannot export backup: a label exceeds the supported length.".into());
            }
            let key = label_key(&name);
            if !label_map.contains_key(&key) && label_map.len() >= MAX_BACKUP_LABELS {
                return Err(format!("Cannot export backup: there are more than {MAX_BACKUP_LABELS} labels."));
            }
            label_map.entry(key).or_insert(name);
        }
        for name in label_map.values() {
            estimated_size = estimated_size.saturating_add(json_string_size(name)).saturating_add(48);
            if estimated_size > MAX_JSON_BYTES {
                return Err("Cannot export backup: the JSON file would exceed 25 MB.".into());
            }
        }

        let labels = label_map.into_values().enumerate().map(|(id, name)| BackupLabel { id, name }).collect();
        let backup_notes = notes
            .into_iter()
            .map(|note| {
                let mut checklist = note.checklist.clone();
                checklist.sort_by_key(|item| item.position);
                BackupNote {
                    id: note.local_id,
                    title: note.title.clone(),
                    content: note.content.clone(),
                    timestamp: note.timestamp,
                    color: note.color,
                    is_pinned: note.is_pinned,
                    is_archived: note.is_archived,
                    is_trashed: note.is_trashed,
                    position: note.position,
                    reminder_timestamp: note.reminder_timestamp,
                    server_updated_at: note.server_updated_at,
                    labels: note.labels.clone(),
                    checklist,
                }
            })
            .collect();
        let exported_at = now_millis();
        let bytes = serde_json::to_vec_pretty(&BackupDocument {
            version: 3,
            exported_at,
            app: "Notelikeus",
            app_version: env!("CARGO_PKG_VERSION"),
            labels,
            notes: backup_notes,
        })
        .map_err(|error| format!("Could not serialize backup: {error}"))?;
        if bytes.len() > MAX_JSON_BYTES {
            return Err("Cannot export backup: the JSON file would exceed 25 MB.".into());
        }
        Ok(bytes)
    }

    /// Reads only image bytes already on this device for a v4 bundle; it never downloads from sync.
    pub fn local_backup_attachments(&self) -> (Vec<BackupAttachmentSource>, usize) {
        const MAX_ATTACHMENTS: usize = 5_000;
        const MAX_ATTACHMENT_BYTES: usize = 10 * 1024 * 1024;
        let blobs = self.store.blobs();
        let mut included = Vec::new();
        let mut skipped = 0;
        let mut seen = std::collections::HashSet::new();
        for (note_id, images) in &self.state.attachments {
            let Some(note) = self.state.notes.get(note_id) else { continue };
            for image in images {
                if included.len() >= MAX_ATTACHMENTS {
                    skipped += 1;
                    continue;
                }
                if !backup_attachment_id(&image.id) || !seen.insert(image.id.clone()) {
                    skipped += 1;
                    continue;
                }
                match blobs.get(&image.id) {
                    Ok(Some(bytes)) if bytes.len() <= MAX_ATTACHMENT_BYTES => {
                        if let Some(mime_type) = attachments::sniff_mime(&bytes) {
                            included.push(BackupAttachmentSource {
                                note_id: note.local_id,
                                attachment_id: image.id.clone(),
                                mime_type: mime_type.to_string(),
                                bytes,
                            });
                        } else {
                            skipped += 1;
                        }
                    }
                    _ => skipped += 1,
                }
            }
        }
        (included, skipped)
    }

    /// Stores a verified backup image under a newly generated id, preserving the imported note time.
    pub fn add_backup_image(&mut self, note_id: &str, bytes: &[u8]) -> Result<(), String> {
        if !self.state.notes.contains_key(note_id) {
            return Err("That note no longer exists.".into());
        }
        if bytes.len() > MAX_ATTACHMENT_BYTES {
            return Err("That image is larger than 10 MB.".into());
        }
        let mime_type = attachments::sniff_mime(bytes).ok_or("That file isn't a supported picture.")?;
        let image_id = attachments::new_attachment_id().map_err(|error| error.to_string())?;
        let blobs = self.store.blobs();
        blobs.put_pending(&image_id, bytes).map_err(|error| format!("Could not keep the image: {error}"))?;

        let before = self.state.clone();
        let note_timestamp = self.state.notes[note_id].timestamp;
        self.state.attachments.entry(note_id.to_string()).or_default().push(Attachment {
            id: image_id.clone(),
            mime_type: mime_type.to_string(),
            size_bytes: bytes.len() as u64,
            location: Location::Pending,
        });
        let now = self.ids.now_millis();
        let edited = sync::edit_note(&mut self.state, note_id, now, |_| {}).map_err(|error| error.to_string());
        if let Err(error) = edited {
            self.state = before;
            let _ = blobs.remove(&image_id);
            return Err(error);
        }
        self.state.notes.get_mut(note_id).expect("note checked above").timestamp = note_timestamp;
        if let Err(error) = self.commit(before) {
            let _ = blobs.remove(&image_id);
            return Err(error);
        }
        Ok(())
    }

    /// Adds notes from a v3/legacy JSON backup. It validates the document before touching the store.
    pub fn import_backup_v3(&mut self, bytes: &[u8]) -> Result<BackupImportReport, String> {
        if bytes.len() > MAX_JSON_BYTES {
            return Err("Backup file is too large (maximum 25 MB).".into());
        }
        check_json_depth(bytes)?;
        let root: Value = serde_json::from_slice(bytes).map_err(|_| "Backup file is not valid JSON".to_string())?;
        let root = root.as_object().ok_or("Invalid backup file")?;
        let version = parse_backup_version(root.get("version"))?;
        if version > 3 {
            return Err(format!("Unsupported backup version: {version}"));
        }
        let root_labels = root.get("labels").filter(|value| !value.is_null());
        if root_labels.is_some_and(|value| !value.is_array()) {
            return Err("Backup labels must be an array".into());
        }
        let note_entries: &[Value] = root.get("notes").and_then(Value::as_array).map(Vec::as_slice).unwrap_or(&[]);
        if note_entries.len() > MAX_BACKUP_NOTES {
            return Err(format!("Backup has too many notes (maximum {MAX_BACKUP_NOTES})."));
        }
        for entry in note_entries {
            if let Some(object) = entry.as_object()
                && object.get("labels").is_some_and(|value| !value.is_null() && !value.is_array())
            {
                return Err("Note labels must be an array".into());
            }
        }
        if note_entries.is_empty() {
            return Ok(BackupImportReport::default());
        }

        let mut backup_labels = Vec::new();
        if let Some(labels) = root_labels.and_then(Value::as_array) {
            for entry in labels {
                if backup_labels.len() >= MAX_BACKUP_LABELS {
                    break;
                }
                push_unique_label(&mut backup_labels, parse_label_name(entry));
            }
        }
        for entry in note_entries {
            let Some(labels) = entry.as_object().and_then(|object| object.get("labels")).and_then(Value::as_array)
            else {
                continue;
            };
            for label in labels {
                if backup_labels.len() >= MAX_BACKUP_LABELS {
                    break;
                }
                push_unique_label(&mut backup_labels, parse_label_name(label));
            }
        }

        let now = self.ids.now_millis();
        let existing_labels: HashMap<String, String> = self
            .state
            .label_registry
            .iter()
            .chain(self.state.notes.values().flat_map(|note| note.labels.iter()))
            .map(|name| (label_key(name), tidy_label(name)))
            .collect();
        let mut resolved_labels = HashMap::new();
        let mut report = BackupImportReport::default();
        for name in &backup_labels {
            let key = label_key(name);
            if let Some(existing) = existing_labels.get(&key) {
                resolved_labels.insert(key, existing.clone());
            } else {
                resolved_labels.insert(key, name.clone());
                report.labels_created += 1;
            }
        }

        let base_position = self
            .state
            .notes
            .values()
            .filter(|note| !note.is_archived && !note.is_trashed)
            .map(|note| note.position)
            .max()
            .unwrap_or(-1)
            .saturating_add(1);
        let before = self.state.clone();
        for entry in note_entries {
            let Some(object) = entry.as_object() else { continue };
            let labels = object
                .get("labels")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
                .map(parse_label_name)
                .filter(|name| !name.is_empty())
                .take(MAX_LABELS_PER_NOTE)
                .map(|name| resolved_labels.get(&label_key(&name)).cloned().unwrap_or(name))
                .collect::<Vec<_>>();
            let checklist = parse_checklist(object.get("checklist"), MAX_CHECKLIST_ITEMS);
            let position = base_position.saturating_add(report.notes_imported as i64);
            let old_id = object.get("id").and_then(backup_id_string);
            let new_id = sync::create_note(&mut self.state, self.ids.as_mut(), |note| {
                fill_note_from_backup(note, object, labels, checklist, position, now);
            });
            if let Some(old_id) = old_id {
                report.new_note_id_by_old_id.entry(old_id).or_insert_with(|| new_id.clone());
            }
            report.notes_imported += 1;
        }

        for name in backup_labels {
            let key = label_key(&name);
            let resolved = resolved_labels.get(&key).unwrap_or(&name);
            if !self.state.label_registry.iter().any(|existing| label_key(existing) == key) {
                self.state.label_registry.push(resolved.clone());
            }
        }
        self.commit(before)?;
        Ok(report)
    }
}

fn backup_attachment_id(id: &str) -> bool {
    (1..=128).contains(&id.len()) && id.bytes().all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-'))
}

fn now_millis() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_millis() as i64)
        .unwrap_or(0)
}

fn check_json_depth(bytes: &[u8]) -> Result<(), String> {
    let mut depth = 0usize;
    let mut in_string = false;
    let mut escaped = false;
    for byte in bytes {
        if in_string {
            if escaped {
                escaped = false;
            } else if *byte == b'\\' {
                escaped = true;
            } else if *byte == b'"' {
                in_string = false;
            }
            continue;
        }
        match byte {
            b'"' => in_string = true,
            b'{' | b'[' => {
                depth += 1;
                if depth > MAX_JSON_DEPTH {
                    return Err("Backup file is too deeply nested".into());
                }
            }
            b'}' | b']' => depth = depth.saturating_sub(1),
            _ => {}
        }
    }
    Ok(())
}

fn parse_backup_version(value: Option<&Value>) -> Result<i64, String> {
    match value {
        None | Some(Value::Null) => Ok(0),
        Some(Value::Number(number)) => {
            number.as_i64().filter(|version| *version >= 0).ok_or("Invalid backup version".into())
        }
        _ => Err("Invalid backup version".into()),
    }
}

fn parse_label_name(value: &Value) -> String {
    let raw = if let Some(text) = value.as_str() {
        text
    } else if let Some(object) = value.as_object() {
        let Some(text) = object.get("name").and_then(Value::as_str) else { return String::new() };
        text
    } else {
        return String::new();
    };
    take_chars(&tidy_label(raw.trim()), MAX_LABEL_CHARS)
}

fn push_unique_label(labels: &mut Vec<String>, name: String) {
    if !name.is_empty() && !labels.iter().any(|existing| label_key(existing) == label_key(&name)) {
        labels.push(name);
    }
}

fn backup_id_string(value: &Value) -> Option<String> {
    match value {
        Value::String(value) => Some(value.clone()),
        Value::Number(value) => Some(value.to_string()),
        _ => None,
    }
}

fn parse_checklist(value: Option<&Value>, limit: usize) -> Vec<ChecklistItem> {
    let Some(items) = value.and_then(Value::as_array) else { return Vec::new() };
    let mut parsed: Vec<(i64, ChecklistItem)> = items
        .iter()
        .enumerate()
        .map(|(index, item)| {
            let object = item.as_object();
            let text = object
                .and_then(|object| object.get("text"))
                .and_then(Value::as_str)
                .map(|text| take_chars(text, MAX_TITLE_CHARS))
                .unwrap_or_default();
            let is_checked =
                object.and_then(|object| object.get("isChecked")).and_then(Value::as_bool).unwrap_or(false);
            let position =
                object.and_then(|object| object.get("position")).and_then(Value::as_i64).unwrap_or(index as i64);
            (position, ChecklistItem { text, is_checked, position })
        })
        .collect();
    parsed.sort_by_key(|(position, _)| *position);
    parsed.into_iter().take(limit).map(|(_, item)| item).collect()
}

fn fill_note_from_backup(
    note: &mut Note,
    object: &Map<String, Value>,
    labels: Vec<String>,
    checklist: Vec<ChecklistItem>,
    position: i64,
    now: i64,
) {
    note.title =
        object.get("title").and_then(Value::as_str).map(|text| take_chars(text, MAX_TITLE_CHARS)).unwrap_or_default();
    note.content = object
        .get("content")
        .and_then(Value::as_str)
        .map(|text| take_chars(text, MAX_CONTENT_CHARS))
        .unwrap_or_default();
    note.timestamp = object.get("timestamp").and_then(Value::as_i64).unwrap_or(now);
    note.color = object.get("color").and_then(Value::as_i64).and_then(|color| i32::try_from(color).ok()).unwrap_or(0);
    note.is_pinned = object.get("isPinned").and_then(Value::as_bool).unwrap_or(false);
    note.is_archived = object.get("isArchived").and_then(Value::as_bool).unwrap_or(false);
    note.is_trashed = object.get("isTrashed").and_then(Value::as_bool).unwrap_or(false);
    note.position = position;
    note.reminder_timestamp =
        object.get("reminderTimestamp").and_then(Value::as_i64).filter(|timestamp| *timestamp > now);
    note.server_updated_at = None;
    note.labels = labels;
    note.checklist = checklist;
}

fn take_chars(text: &str, limit: usize) -> String {
    text.chars().take(limit).collect()
}

fn json_string_size(text: &str) -> usize {
    2usize.saturating_add(text.chars().fold(0usize, |size, ch| {
        size.saturating_add(match ch {
            '"' | '\\' => 2,
            '\u{0}'..='\u{1f}' => 6,
            _ => ch.len_utf8(),
        })
    }))
}
