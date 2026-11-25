use std::collections::{BTreeMap, HashSet};
use std::fs::{self, File};
use std::io::{self, Cursor, Read, Seek, SeekFrom, Write};
use std::path::Path;

use notelikeus_app::{BackupImage, BackupImportReport, NotesService};
use notelikeus_core::attachments::AttachmentPlacement;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, ZipArchive, ZipWriter};

const MAX_BACKUP_FILE_BYTES: u64 = 25 * 1024 * 1024;
const MAX_BUNDLE_FILE_BYTES: u64 = 256 * 1024 * 1024;
const MAX_BUNDLE_ATTACHMENT_BYTES: u64 = 10 * 1024 * 1024;
const MAX_BUNDLE_ATTACHMENTS: usize = 5_000;
const MAX_BUNDLE_MANIFEST_BYTES: u64 = MAX_BACKUP_FILE_BYTES + 2 * 1024 * 1024;
const MAX_ZIP_ENTRIES: usize = 10_000;
const MAX_TOTAL_UNCOMPRESSED_BYTES: u64 = 512 * 1024 * 1024;
const MANIFEST_ENTRY: &str = "manifest.json";

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct BackupExportReport {
    pub images_included: usize,
    pub images_skipped: usize,
}

fn write_file_atomically(path: &Path, bytes: &[u8], err_prefix: &str) -> Result<(), String> {
    use std::sync::atomic::{AtomicU64, Ordering};
    static BACKUP_TEMP_SEQ: AtomicU64 = AtomicU64::new(0);

    let parent = path.parent().filter(|p| !p.as_os_str().is_empty()).unwrap_or_else(|| Path::new("."));
    fs::create_dir_all(parent).map_err(|error| format!("{err_prefix}: {error}"))?;
    let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("backup");
    let seq = BACKUP_TEMP_SEQ.fetch_add(1, Ordering::Relaxed);
    let temp = parent.join(format!(".{name}.{}.{seq}.tmp", std::process::id()));
    let mut file = File::create(&temp).map_err(|error| format!("{err_prefix}: {error}"))?;
    if let Err(error) = file.write_all(bytes).and_then(|()| file.sync_all()) {
        drop(file);
        let _ = fs::remove_file(&temp);
        return Err(format!("{err_prefix}: {error}"));
    }
    drop(file);
    if let Err(error) = fs::rename(&temp, path) {
        let _ = fs::remove_file(&temp);
        return Err(format!("{err_prefix}: {error}"));
    }
    Ok(())
}

/// Saves a v3 JSON backup at the user-selected path.
pub fn export_json(service: &NotesService, path: &Path) -> Result<(), String> {
    let bytes = service.export_backup_v3()?;
    write_file_atomically(path, &bytes, "Couldn't save the backup")
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
            let mut entry = json!({
                "noteId": attachment.note_id,
                "attachmentId": attachment.attachment_id,
                "path": format!("media/{}", attachment.attachment_id),
                "type": "image",
                "mimeType": attachment.mime_type,
                "sizeBytes": attachment.bytes.len(),
                "sha256": sha256,
            });
            if let Some(placement) = &attachment.placement {
                entry["placement"] = json!(placement);
            }
            entry
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
    write_file_atomically(path, &bytes, "Couldn't save the backup bundle")?;
    Ok(BackupExportReport { images_included: attachments.len(), images_skipped })
}

/// Imports a v3 JSON backup or a v4 `.nlkbak` bundle, detected by magic bytes or file extension.
pub fn import_file(service: &mut NotesService, path: &Path) -> Result<BackupImportReport, String> {
    let mut file = File::open(path).map_err(|error| format!("Couldn't read the backup file: {error}"))?;
    let mut magic = [0u8; 4];
    let n = file.read(&mut magic).map_err(|error| format!("Couldn't read the backup file: {error}"))?;
    let is_zip_magic = n >= 2 && magic[0] == b'P' && magic[1] == b'K';
    let is_nlkbak_ext =
        path.extension().and_then(|extension| extension.to_str()).is_some_and(|ext| ext.eq_ignore_ascii_case("nlkbak"));
    drop(file);
    if is_zip_magic || is_nlkbak_ext {
        import_bundle(service, path)
    } else {
        let mut file = File::open(path).map_err(|error| format!("Couldn't read the backup file: {error}"))?;
        let size = file.metadata().map_err(|error| format!("Couldn't read the backup file: {error}"))?.len();
        if size > MAX_BACKUP_FILE_BYTES {
            return Err("Backup file is too large (maximum 25 MB).".into());
        }
        let bytes = read_limited(&mut file, MAX_BACKUP_FILE_BYTES)
            .map_err(|_| "Backup file is too large (maximum 25 MB).".to_string())?;
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
    if archive.has_duplicate_names() {
        return Err("Backup bundle has a duplicate entry name.".into());
    }
    if archive.len() > MAX_ZIP_ENTRIES {
        return Err(format!("Backup bundle has too many entries (maximum {MAX_ZIP_ENTRIES})."));
    }
    let mut names = HashSet::new();
    let mut manifest_index = None;
    let mut total_declared_uncompressed = 0u64;
    for index in 0..archive.len() {
        let entry =
            archive.by_index(index).map_err(|error| format!("Backup bundle isn't a valid ZIP file: {error}"))?;
        let name =
            entry.name().map_err(|error| format!("Backup bundle has an invalid ZIP entry name: {error}"))?.into_owned();
        if !names.insert(name.clone()) {
            return Err(format!("Backup bundle has a duplicate ZIP entry: {name}"));
        }
        total_declared_uncompressed = total_declared_uncompressed
            .checked_add(entry.size())
            .ok_or("Backup bundle uncompressed size overflows.")?;
        if total_declared_uncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES {
            return Err("Backup bundle uncompressed contents are too large.".into());
        }
        if name == MANIFEST_ENTRY {
            manifest_index = Some(index);
        }
    }
    let manifest_index = manifest_index.ok_or("Backup bundle has no manifest.json")?;
    let mut actual_uncompressed = 0u64;
    let manifest_bytes = {
        let mut manifest_entry = archive.by_index(manifest_index).map_err(|error| error.to_string())?;
        if manifest_entry.size() > MAX_BUNDLE_MANIFEST_BYTES {
            return Err("Backup bundle manifest is too large.".into());
        }
        read_limited(&mut manifest_entry, MAX_BUNDLE_MANIFEST_BYTES)?
    };
    actual_uncompressed = actual_uncompressed
        .checked_add(manifest_bytes.len() as u64)
        .ok_or("Backup bundle uncompressed size overflows.")?;
    if actual_uncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES {
        return Err("Backup bundle uncompressed contents are too large.".into());
    }
    check_json_depth(&manifest_bytes)?;
    let manifest: Value =
        serde_json::from_slice(&manifest_bytes).map_err(|_| "Backup bundle manifest is not valid JSON")?;
    let manifest = manifest.as_object().ok_or("Backup bundle manifest is not an object")?;
    let format_version =
        manifest.get("formatVersion").and_then(Value::as_i64).ok_or("Backup bundle has no valid formatVersion")?;
    if format_version != 4 {
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
    let mut verified_images = Vec::new();
    let mut positions = BTreeMap::new();
    let mut placements_skipped = 0;
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
        if entry
            .get("path")
            .and_then(Value::as_str)
            .is_some_and(|declared| declared != format!("media/{attachment_id}"))
        {
            report.attachments_skipped += 1;
            continue;
        }
        let Some(new_note_id) = report.new_note_id_by_old_id.get(&old_note_id).cloned() else {
            report.attachments_skipped += 1;
            continue;
        };
        let Some(declared_size) = entry.get("sizeBytes").and_then(Value::as_u64) else {
            report.attachments_skipped += 1;
            continue;
        };
        if declared_size > MAX_BUNDLE_ATTACHMENT_BYTES {
            report.attachments_skipped += 1;
            continue;
        }
        let Some(declared_hash) = entry.get("sha256").and_then(Value::as_str) else {
            report.attachments_skipped += 1;
            continue;
        };
        if !valid_sha256(declared_hash) {
            report.attachments_skipped += 1;
            continue;
        }
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
        actual_uncompressed =
            actual_uncompressed.checked_add(bytes.len() as u64).ok_or("Backup bundle uncompressed size overflows.")?;
        if actual_uncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES {
            return Err("Backup bundle uncompressed contents are too large.".into());
        }
        if declared_size != bytes.len() as u64 {
            report.attachments_skipped += 1;
            continue;
        }
        if format!("{:x}", Sha256::digest(&bytes)) != declared_hash {
            report.attachments_skipped += 1;
            continue;
        }
        let placement = entry.get("placement").and_then(parse_placement);
        placements_skipped += usize::from(entry.contains_key("placement") && placement.is_none());
        if let Some(placement) = &placement {
            positions
                .entry((new_note_id.clone(), placement.body_sha256.clone(), placement.offset))
                .or_insert_with(Vec::new)
                .push((placement.ordinal, attachment_id, verified_images.len()));
        }
        verified_images.push(BackupImage { note_id: new_note_id, bytes, placement });
    }
    // A tie uses the source id. Preserve that order after local ids are regenerated.
    for group in positions.values_mut() {
        group.sort_unstable();
        if group.windows(2).any(|pair| pair[0].0 == pair[1].0) {
            for (ordinal, &(_, _, index)) in group.iter().enumerate() {
                if let Some(placement) = &mut verified_images[index].placement {
                    placement.ordinal = ordinal as u64;
                }
            }
        }
    }
    if !verified_images.is_empty() {
        let (imported, batch_skipped, batch_positions_skipped) = service.add_backup_images(&verified_images)?;
        placements_skipped += batch_positions_skipped;
        report.attachments_imported += imported;
        report.attachments_skipped += batch_skipped;
    }
    if placements_skipped > 0 {
        report
            .warnings
            .push("Some image positions could not be restored. The images are in the attachment strip.".into());
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

fn parse_placement(value: &Value) -> Option<AttachmentPlacement> {
    const MAX_SAFE_INTEGER: u64 = (1 << 53) - 1;
    let digest = value.get("bodySha256")?.as_str().filter(|digest| valid_sha256(digest))?;
    let offset = value.get("offset")?.as_u64().filter(|offset| (1..=MAX_SAFE_INTEGER).contains(offset))?;
    let ordinal = value.get("ordinal")?.as_u64().filter(|ordinal| *ordinal <= MAX_SAFE_INTEGER)?;
    Some(AttachmentPlacement { body_sha256: digest.to_owned(), offset, ordinal })
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

#[cfg(test)]
mod tests {
    use super::*;
    use notelikeus_app::{NotesService, SystemIds};
    use notelikeus_core::crypto::PlaintextKeyProtectorForTests;
    use std::path::PathBuf;

    fn contracts_dir() -> PathBuf {
        let mut dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        loop {
            let candidate = dir.join("contracts");
            if candidate.join("README.md").exists() {
                return candidate;
            }
            assert!(dir.pop(), "contracts/ not found");
        }
    }

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("nlk-native-bak-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        dir
    }

    fn open_service(dir: &Path) -> NotesService {
        NotesService::open(dir, Box::new(PlaintextKeyProtectorForTests), Box::new(SystemIds), None).unwrap()
    }

    #[test]
    fn v4_kotlin_and_web_bundles_import_and_round_trip() {
        for fixture in ["backup/v4-kotlin-bundle.nlkbak", "backup/v4-web-bundle.nlkbak"] {
            let dir = temp_dir("v4-import");
            let mut service = open_service(&dir);
            let report = import_file(&mut service, &contracts_dir().join(fixture)).unwrap();
            assert!(report.notes_imported >= 1, "{fixture}");
            assert!(report.attachments_imported >= 1, "{fixture}");
            assert_eq!(report.attachments_skipped, 0, "{fixture}");

            let exported_bundle = dir.join("roundtrip.nlkbak");
            let export_report = export_bundle(&service, &exported_bundle).unwrap();
            assert_eq!(export_report.images_included, report.attachments_imported);
            assert_eq!(export_report.images_skipped, 0);

            let dir2 = temp_dir("v4-roundtrip");
            let mut service2 = open_service(&dir2);
            let reimported = import_file(&mut service2, &exported_bundle).unwrap();
            assert_eq!(reimported.notes_imported, report.notes_imported);
            assert_eq!(reimported.attachments_imported, report.attachments_imported);
            fs::remove_dir_all(&dir2).unwrap();
            fs::remove_dir_all(&dir).unwrap();
        }
    }

    #[test]
    fn a_v4_bundle_with_optional_placement_restores_the_note_and_picture() {
        let dir = temp_dir("v4-placement");
        let mut service = open_service(&dir);
        let report = import_file(&mut service, &contracts_dir().join("backup/v4-positioned-image.nlkbak")).unwrap();
        assert_eq!((report.notes_imported, report.attachments_imported, report.attachments_skipped), (1, 1, 0));
        let note = service.note(&service.list()[0].id).unwrap();
        assert_eq!(note.title, "Positioned image");
        assert_eq!(note.content, "Before 🧭\n\nAfter");
        assert_eq!(service.image_source().bytes(&note.id, &note.images[0].id).unwrap().len(), 70);
        assert!(report.warnings.is_empty());
        drop(service);
        let mut service = open_service(&dir);
        let exported = dir.join("positioned-roundtrip.nlkbak");
        export_bundle(&service, &exported).unwrap();
        let mut archive = ZipArchive::new(File::open(exported).unwrap()).unwrap();
        let manifest: Value = serde_json::from_reader(archive.by_name(MANIFEST_ENTRY).unwrap()).unwrap();
        assert_eq!(
            manifest["attachments"][0]["placement"],
            json!({
                "bodySha256": format!("{:x}", Sha256::digest(note.content.as_bytes())),
                "offset": 10,
                "ordinal": 0,
            })
        );
        drop(archive);

        service.update(&note.id, &note.title, "Changed body").unwrap();
        let exported = dir.join("stale-position.nlkbak");
        assert_eq!(export_bundle(&service, &exported).unwrap().images_included, 1);
        let mut archive = ZipArchive::new(File::open(exported).unwrap()).unwrap();
        let manifest: Value = serde_json::from_reader(archive.by_name(MANIFEST_ENTRY).unwrap()).unwrap();
        assert!(manifest["attachments"][0].get("placement").is_none());
        drop(archive);
        fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn v4_duplicate_entry_bundle_fixture_is_rejected() {
        let dir = temp_dir("v4-dup");
        let mut service = open_service(&dir);
        let err =
            import_file(&mut service, &contracts_dir().join("backup/v4-duplicate-entry-bundle.nlkbak")).unwrap_err();
        assert!(err.to_ascii_lowercase().contains("duplicate"), "{err}");
        fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn zip_bombs_and_malformed_v4_manifests_are_refused() {
        let dir = temp_dir("zip-caps");
        fs::create_dir_all(&dir).unwrap();
        let mut service = open_service(&dir.join("svc"));

        // 1. Entry count above MAX_ZIP_ENTRIES
        {
            let path = dir.join("many-entries.nlkbak");
            let file = File::create(&path).unwrap();
            let mut zip = ZipWriter::new(file);
            let opts = SimpleFileOptions::default().compression_method(CompressionMethod::Stored);
            for i in 0..=MAX_ZIP_ENTRIES {
                zip.start_file(format!("e/{i}"), opts).unwrap();
            }
            zip.finish().unwrap();
            let err = import_file(&mut service, &path).unwrap_err();
            assert!(err.contains("too many entries"), "{err}");
        }

        // 2. Uncompressed payload exceeding MAX_TOTAL_UNCOMPRESSED_BYTES via deflate
        {
            let path = dir.join("bomb.nlkbak");
            let file = File::create(&path).unwrap();
            let mut zip = ZipWriter::new(file);
            let opts = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);
            let chunk = vec![0u8; 5 * 1024 * 1024];
            for i in 0..110 {
                zip.start_file(format!("media/pad-{i}"), opts).unwrap();
                zip.write_all(&chunk).unwrap();
            }
            zip.finish().unwrap();
            let err = import_file(&mut service, &path).unwrap_err();
            assert!(err.contains("uncompressed contents are too large"), "{err}");
        }

        // 3. Unsupported formatVersion
        {
            let path = dir.join("bad-version.nlkbak");
            let file = File::create(&path).unwrap();
            let mut zip = ZipWriter::new(file);
            let opts = SimpleFileOptions::default().compression_method(CompressionMethod::Stored);
            zip.start_file(MANIFEST_ENTRY, opts).unwrap();
            zip.write_all(br#"{"formatVersion":99,"backup":{"version":3,"notes":[]},"attachments":[]}"#).unwrap();
            zip.finish().unwrap();
            let err = import_file(&mut service, &path).unwrap_err();
            assert!(err.contains("Unsupported bundle version"), "{err}");
        }

        // 4. Missing sizeBytes and mismatched attachment path drop those attachments cleanly
        {
            let png = [
                0x89u8, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, 0x00,
                0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4, 0x89, 0x00,
                0x00, 0x00, 0x0D, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0xF8, 0xCF, 0xC0, 0xF0, 0x1F, 0x00, 0x05,
                0x00, 0x01, 0xFF, 0x89, 0x99, 0x3D, 0x1D, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42,
                0x60, 0x82,
            ];
            let hash = format!("{:x}", Sha256::digest(png));
            let path = dir.join("bad-attachments.nlkbak");
            let file = File::create(&path).unwrap();
            let mut zip = ZipWriter::new(file);
            let opts = SimpleFileOptions::default().compression_method(CompressionMethod::Stored);
            let manifest = json!({
                "formatVersion": 4,
                "backup": {
                    "version": 3,
                    "exportedAt": 1_767_225_600_000_i64,
                    "labels": [],
                    "notes": [{"id": 1, "title": "Pic", "content": "", "timestamp": 1_767_225_000_000_i64, "color": 0, "isPinned": false, "isArchived": false, "isTrashed": false, "position": 0, "labels": [], "checklist": []}]
                },
                "attachments": [
                    { "noteId": 1, "attachmentId": "att-missing-size", "path": "media/att-missing-size", "type": "image", "sha256": hash },
                    { "noteId": 1, "attachmentId": "att-bad-path", "path": "media/../evil", "type": "image", "sizeBytes": png.len(), "sha256": hash }
                ]
            });
            zip.start_file(MANIFEST_ENTRY, opts).unwrap();
            zip.write_all(manifest.to_string().as_bytes()).unwrap();
            zip.start_file("media/att-missing-size", opts).unwrap();
            zip.write_all(&png).unwrap();
            zip.start_file("media/att-bad-path", opts).unwrap();
            zip.write_all(&png).unwrap();
            zip.finish().unwrap();
            let report = import_file(&mut service, &path).unwrap();
            assert_eq!(report.notes_imported, 1);
            assert_eq!(report.attachments_imported, 0);
            assert_eq!(report.attachments_skipped, 2);
        }

        fs::remove_dir_all(&dir).unwrap();
    }
}
