use std::collections::HashSet;
use std::fs::{self, File};
use std::io::{self, Cursor, Read, Seek, SeekFrom, Write};
use std::path::Path;

use notelikeus_app::{BackupImportReport, NotesService};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, ZipArchive, ZipWriter};

const MAX_BACKUP_FILE_BYTES: u64 = 25 * 1024 * 1024;
const MAX_BUNDLE_FILE_BYTES: u64 = 256 * 1024 * 1024;
const MAX_BUNDLE_ATTACHMENT_BYTES: u64 = 10 * 1024 * 1024;
const MAX_BUNDLE_ATTACHMENTS: usize = 5_000;
const MAX_BUNDLE_MANIFEST_BYTES: u64 = MAX_BACKUP_FILE_BYTES + 2 * 1024 * 1024;
const MANIFEST_ENTRY: &str = "manifest.json";

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct BackupExportReport {
    pub images_included: usize,
    pub images_skipped: usize,
}

/// Saves a v3 JSON backup at the user-selected path.
pub fn export_json(service: &NotesService, path: &Path) -> Result<(), String> {
    let bytes = service.export_backup_v3()?;
    fs::write(path, bytes).map_err(|error| format!("Couldn't save the backup: {error}"))
}

/// Saves a v4 ZIP bundle with local image bytes only. It does not fetch cloud-only images.
pub fn export_bundle(service: &NotesService, path: &Path) -> Result<BackupExportReport, String> {
    let backup_bytes = service.export_backup_v3()?;
    let backup: Value = serde_json::from_slice(&backup_bytes).map_err(|error| error.to_string())?;
    let (attachments, mut images_skipped) = service.local_backup_attachments();
    if attachments.len() > MAX_BUNDLE_ATTACHMENTS {
        images_skipped += attachments.len() - MAX_BUNDLE_ATTACHMENTS;
    }
    let attachments = attachments.into_iter().take(MAX_BUNDLE_ATTACHMENTS).collect::<Vec<_>>();

    let attachment_manifest: Vec<Value> = attachments
        .iter()
        .map(|attachment| {
            let sha256 = format!("{:x}", Sha256::digest(&attachment.bytes));
            json!({
                "noteId": attachment.note_id,
                "attachmentId": attachment.attachment_id,
                "path": format!("media/{}", attachment.attachment_id),
                "type": "image",
                "mimeType": attachment.mime_type,
                "sizeBytes": attachment.bytes.len(),
                "sha256": sha256,
            })
        })
        .collect();
    let metadata = serde_json::to_vec(&json!({
        "formatVersion": 4,
        "app": "Notelikeus",
        "appVersion": env!("CARGO_PKG_VERSION"),
        "exportedAt": backup.get("exportedAt").and_then(Value::as_i64).unwrap_or_default(),
    }))
    .map_err(|error| format!("Could not create backup manifest: {error}"))?;
    let attachment_manifest = serde_json::to_vec(&attachment_manifest)
        .map_err(|error| format!("Could not create backup manifest: {error}"))?;
    let mut manifest = Vec::with_capacity(metadata.len() + backup_bytes.len() + attachment_manifest.len() + 32);
    manifest.extend_from_slice(&metadata[..metadata.len().saturating_sub(1)]);
    manifest.extend_from_slice(b",\"backup\":");
    manifest.extend_from_slice(&backup_bytes);
    manifest.extend_from_slice(b",\"attachments\":");
    manifest.extend_from_slice(&attachment_manifest);
    manifest.push(b'}');
    if manifest.len() as u64 > MAX_BUNDLE_MANIFEST_BYTES {
        return Err("Cannot create backup: the manifest would exceed 27 MB.".into());
    }

    let mut zip = ZipWriter::new(CappedWriter::new(MAX_BUNDLE_FILE_BYTES));
    let stored = SimpleFileOptions::default().compression_method(CompressionMethod::Stored);
    zip.start_file(MANIFEST_ENTRY, stored).map_err(|error| format!("Could not write backup bundle: {error}"))?;
    zip.write_all(&manifest).map_err(|error| format!("Could not write backup bundle: {error}"))?;
    for attachment in &attachments {
        let name = format!("media/{}", attachment.attachment_id);
        zip.start_file(name, stored).map_err(|error| format!("Could not write backup bundle: {error}"))?;
        zip.write_all(&attachment.bytes).map_err(|error| {
            if error.kind() == io::ErrorKind::InvalidData {
                "Cannot create backup: the bundle would exceed 256 MB.".to_string()
            } else {
                format!("Could not write backup bundle: {error}")
            }
        })?;
    }
    let bytes = zip.finish().map_err(|error| format!("Could not finish backup bundle: {error}"))?.inner.into_inner();
    if bytes.len() as u64 > MAX_BUNDLE_FILE_BYTES {
        return Err("Cannot create backup: the bundle would exceed 256 MB.".into());
    }
    fs::write(path, bytes).map_err(|error| format!("Couldn't save the backup bundle: {error}"))?;
    Ok(BackupExportReport { images_included: attachments.len(), images_skipped })
}

/// Imports a v3 JSON backup or a v4 `.nlkbak` bundle, according to the selected file extension.
pub fn import_file(service: &mut NotesService, path: &Path) -> Result<BackupImportReport, String> {
    if path.extension().and_then(|extension| extension.to_str()).is_some_and(|ext| ext.eq_ignore_ascii_case("nlkbak")) {
        import_bundle(service, path)
    } else {
        let size = fs::metadata(path).map_err(|error| format!("Couldn't read the backup file: {error}"))?.len();
        if size > MAX_BACKUP_FILE_BYTES {
            return Err("Backup file is too large (maximum 25 MB).".into());
        }
        let bytes = fs::read(path).map_err(|error| format!("Couldn't read the backup file: {error}"))?;
        service.import_backup_v3(&bytes)
    }
}

fn import_bundle(service: &mut NotesService, path: &Path) -> Result<BackupImportReport, String> {
    let file = File::open(path).map_err(|error| format!("Couldn't read the backup bundle: {error}"))?;
    let size = file.metadata().map_err(|error| error.to_string())?.len();
    if size > MAX_BUNDLE_FILE_BYTES {
        return Err("Backup bundle is too large (maximum 256 MB).".into());
    }
    let mut archive =
        ZipArchive::new(file).map_err(|error| format!("Backup bundle isn't a valid ZIP file: {error}"))?;
    let mut names = HashSet::new();
    let mut manifest_index = None;
    for index in 0..archive.len() {
        let entry =
            archive.by_index(index).map_err(|error| format!("Backup bundle isn't a valid ZIP file: {error}"))?;
        let name = entry.name().to_string();
        if !names.insert(name.clone()) {
            return Err(format!("Backup bundle has a duplicate ZIP entry: {name}"));
        }
        if name == MANIFEST_ENTRY {
            manifest_index = Some(index);
        }
    }
    let manifest_index = manifest_index.ok_or("Backup bundle has no manifest.json")?;
    let manifest_bytes = {
        let mut manifest_entry = archive.by_index(manifest_index).map_err(|error| error.to_string())?;
        if manifest_entry.size() > MAX_BUNDLE_MANIFEST_BYTES {
            return Err("Backup bundle manifest is too large.".into());
        }
        read_limited(&mut manifest_entry, MAX_BUNDLE_MANIFEST_BYTES)?
    };
    check_json_depth(&manifest_bytes)?;
    let manifest: Value =
        serde_json::from_slice(&manifest_bytes).map_err(|_| "Backup bundle manifest is not valid JSON")?;
    let manifest = manifest.as_object().ok_or("Backup bundle manifest is not an object")?;
    let format_version = manifest.get("formatVersion").and_then(Value::as_i64).unwrap_or(0);
    if format_version > 4 {
        return Err(format!("Unsupported bundle version: {format_version}"));
    }
    let backup =
        manifest.get("backup").filter(|value| value.is_object()).ok_or("Backup bundle has no backup document")?;
    let backup_bytes = serde_json::to_vec(backup).map_err(|error| error.to_string())?;
    let attachments = manifest.get("attachments").and_then(Value::as_array).map(Vec::as_slice).unwrap_or(&[]);
    if attachments.len() > MAX_BUNDLE_ATTACHMENTS {
        return Err(format!("Bundle lists too many attachments (maximum {MAX_BUNDLE_ATTACHMENTS})."));
    }
    let mut report = service.import_backup_v3(&backup_bytes)?;
    let mut seen_ids = HashSet::new();
    for candidate in attachments {
        let Some(entry) = candidate.as_object() else {
            report.attachments_skipped += 1;
            continue;
        };
        let Some(attachment_id) = entry.get("attachmentId").and_then(Value::as_str) else {
            report.attachments_skipped += 1;
            continue;
        };
        if !valid_attachment_id(attachment_id) || !seen_ids.insert(attachment_id.to_string()) {
            report.attachments_skipped += 1;
            continue;
        }
        if entry.get("type").and_then(Value::as_str).is_some_and(|kind| kind != "image") {
            report.attachments_skipped += 1;
            continue;
        }
        let Some(old_note_id) = entry.get("noteId").and_then(backup_id_string) else {
            report.attachments_skipped += 1;
            continue;
        };
        let Some(new_note_id) = report.new_note_id_by_old_id.get(&old_note_id).cloned() else {
            report.attachments_skipped += 1;
            continue;
        };
        let path = format!("media/{attachment_id}");
        let mut media_entry = match archive.by_name(&path) {
            Ok(entry) => entry,
            Err(_) => {
                report.attachments_skipped += 1;
                continue;
            }
        };
        if media_entry.size() > MAX_BUNDLE_ATTACHMENT_BYTES {
            report.attachments_skipped += 1;
            continue;
        }
        let bytes = match read_limited(&mut media_entry, MAX_BUNDLE_ATTACHMENT_BYTES) {
            Ok(bytes) => bytes,
            Err(_) => {
                report.attachments_skipped += 1;
                continue;
            }
        };
        let declared_size = entry.get("sizeBytes").and_then(Value::as_u64).unwrap_or(bytes.len() as u64);
        if declared_size != bytes.len() as u64 {
            report.attachments_skipped += 1;
            continue;
        }
        let Some(declared_hash) = entry.get("sha256").and_then(Value::as_str) else {
            report.attachments_skipped += 1;
            continue;
        };
        if !valid_sha256(declared_hash) || format!("{:x}", Sha256::digest(&bytes)) != declared_hash {
            report.attachments_skipped += 1;
            continue;
        }
        match service.add_backup_image(&new_note_id, &bytes) {
            Ok(_) => report.attachments_imported += 1,
            Err(_) => report.attachments_skipped += 1,
        }
    }
    if report.attachments_skipped > 0 {
        report.warnings.push(format!(
            "{} picture{} skipped because the file was missing, invalid, or too large.",
            report.attachments_skipped,
            if report.attachments_skipped == 1 { " was" } else { "s were" },
        ));
    }
    Ok(report)
}

fn read_limited(reader: &mut impl Read, maximum: u64) -> Result<Vec<u8>, String> {
    let mut bytes = Vec::new();
    reader
        .take(maximum + 1)
        .read_to_end(&mut bytes)
        .map_err(|error| format!("Could not read archive entry: {error}"))?;
    if bytes.len() as u64 > maximum {
        return Err("Archive entry is too large".into());
    }
    Ok(bytes)
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
                if depth > 64 {
                    return Err("Backup manifest is too deeply nested".into());
                }
            }
            b'}' | b']' => depth = depth.saturating_sub(1),
            _ => {}
        }
    }
    Ok(())
}

fn backup_id_string(value: &Value) -> Option<String> {
    match value {
        Value::String(value) => Some(value.clone()),
        Value::Number(value) => Some(value.to_string()),
        _ => None,
    }
}

fn valid_attachment_id(value: &str) -> bool {
    (1..=128).contains(&value.len())
        && value.bytes().all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-'))
}

fn valid_sha256(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

struct CappedWriter {
    inner: Cursor<Vec<u8>>,
    maximum: u64,
}

impl CappedWriter {
    fn new(maximum: u64) -> Self {
        Self { inner: Cursor::new(Vec::new()), maximum }
    }
}

impl Write for CappedWriter {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        if self.inner.position().saturating_add(bytes.len() as u64) > self.maximum {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "backup bundle exceeds size limit"));
        }
        self.inner.write(bytes)
    }

    fn flush(&mut self) -> io::Result<()> {
        self.inner.flush()
    }
}

impl Seek for CappedWriter {
    fn seek(&mut self, position: SeekFrom) -> io::Result<u64> {
        self.inner.seek(position)
    }
}
