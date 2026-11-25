//! The Rust core against the same `contracts/` fixtures the Kotlin and web suites read, so the three
//! clients cannot drift apart on the wire format or the sync rules without a failing test.

use std::path::PathBuf;

use notelikeus_core::conflict::{self, InboundAction};
use notelikeus_core::model::{ApplyNoteChangeArgs, ChecklistItem, CloudChange, CloudNoteRow, Note};
use serde_json::Value;

fn contracts_dir() -> PathBuf {
    let mut dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    loop {
        let candidate = dir.join("contracts");
        if candidate.join("README.md").exists() {
            return candidate;
        }
        assert!(dir.pop(), "contracts/ not found above {}", env!("CARGO_MANIFEST_DIR"));
    }
}

fn fixture(path: &str) -> Value {
    let text = std::fs::read_to_string(contracts_dir().join(path)).unwrap_or_else(|e| panic!("{path}: {e}"));
    serde_json::from_str(&text).unwrap()
}

/// The client-neutral normal form of `contracts/README.md`.
fn normal_form(note: &Note) -> Value {
    serde_json::json!({
        "localId": note.local_id,
        "title": note.title,
        "content": note.content,
        "timestamp": note.timestamp,
        "color": note.color,
        "isPinned": note.is_pinned,
        "isArchived": note.is_archived,
        "isTrashed": note.is_trashed,
        "position": note.position,
        "reminderTimestamp": note.reminder_timestamp,
        "serverUpdatedAt": note.server_updated_at,
        "labels": note.labels,
        "checklist": note.checklist,
    })
}

#[test]
fn cloud_rows_map_to_the_expected_notes() {
    for path in ["cloud/note-row.json", "cloud/note-row-sparse.json"] {
        let fixture = fixture(path);
        let row: CloudNoteRow = serde_json::from_value(fixture["row"].clone()).unwrap();
        assert_eq!(normal_form(&row.to_note()), fixture["expected"], "{path}");
    }
}

#[test]
fn a_row_parses_as_a_typed_change() {
    let fixture = fixture("cloud/note-row.json");
    let change: CloudChange = serde_json::from_value(fixture["row"].clone()).unwrap();
    assert!(matches!(change, CloudChange::Note(row) if row.revision == Some(41)));
}

#[test]
fn apply_note_change_arguments_match_the_contract() {
    let row: CloudNoteRow = serde_json::from_value(fixture("cloud/note-row.json")["row"].clone()).unwrap();
    let args = ApplyNoteChangeArgs::new(&row.to_note(), None, Vec::new(), 0);
    assert_eq!(serde_json::to_value(args).unwrap(), fixture("cloud/note-rpc-args.json")["args"]);
}

#[test]
fn tombstone_rows_give_the_expected_deleted_at_map() {
    let fixture = fixture("cloud/tombstone-row.json");
    let mut map = serde_json::Map::new();
    for row in fixture["rows"].as_array().unwrap() {
        match serde_json::from_value::<CloudChange>(row.clone()).unwrap() {
            CloudChange::Tombstone(t) => {
                map.insert(t.note_id, t.deleted_at.unwrap().into());
            }
            CloudChange::Note(_) => panic!("a tombstone row parsed as a note"),
        }
    }
    assert_eq!(Value::Object(map), fixture["expected"]);
}

fn side(value: &Value) -> Option<Note> {
    if value.is_null() {
        return None;
    }
    let mut note = Note::new(1, value["timestamp"].as_i64().unwrap());
    note.title = value["title"].as_str().unwrap().into();
    note.content = value["content"].as_str().unwrap().into();
    note.server_updated_at = value["serverUpdatedAt"].as_i64();
    Some(note)
}

#[test]
fn every_inbound_resolution_case_resolves_as_the_contract_says() {
    let fixture = fixture("sync/inbound-resolution.json");
    let mut mismatches = Vec::new();
    for case in fixture["cases"].as_array().unwrap() {
        let local = side(&case["local"]);
        let synced = case["synced"].as_i64();
        let actual = match side(&case["cloud"]) {
            None => {
                let edited = local.as_ref().is_some_and(|l| conflict::is_locally_edited(l, synced));
                if edited { "fork-then-delete" } else { "delete" }
            }
            Some(cloud) => match conflict::decide(local.as_ref(), synced, &cloud) {
                InboundAction::Accept => "accept",
                InboundAction::KeepLocal => "keep-local",
                InboundAction::Fork => "fork",
            },
        };
        if actual != case["expected"].as_str().unwrap() {
            mismatches.push(format!("{}: expected {}, got {actual}", case["name"], case["expected"]));
        }
    }
    assert!(mismatches.is_empty(), "{mismatches:#?}");
}

#[test]
fn owner_tags_match_every_client() {
    for vector in fixture("diagnostics/owner-tag-vectors.json")["vectors"].as_array().unwrap() {
        let owner = vector["ownerId"].as_str().unwrap();
        assert_eq!(conflict::owner_tag(Some(owner)), vector["tag"].as_str().unwrap(), "{owner}");
    }
    assert_eq!(conflict::owner_tag(None), "none");
}

#[test]
fn content_digests_match_the_kotlin_and_web_vectors() {
    // Vectors from VerifiedDeleteTest.kt / verifiedDelete.test.ts.
    let mut groceries = Note::new(1, 0);
    groceries.title = "Groceries".into();
    groceries.content = "milk\nbread".into();
    assert_eq!(conflict::authored_content_digest(&groceries), "v2:1ue18w28pk2");

    let mut unicode = Note::new(2, 0);
    unicode.title = "Café ✓ 日本".into();
    unicode.checklist = vec![
        ChecklistItem { text: "eggs".into(), is_checked: true, position: 1 },
        ChecklistItem { text: "oat milk".into(), is_checked: false, position: 0 },
    ];
    unicode.labels = vec!["home".into(), "Errands".into()];
    assert_eq!(conflict::authored_content_digest(&unicode), "v2:18lg26bb6hd");
}

#[test]
fn a_conflicted_copy_keeps_the_words_under_a_new_id() {
    let mut note = Note::new(5, 10);
    note.title = "Plan".into();
    note.server_updated_at = Some(99);
    let copy = conflict::conflicted_copy_of(&note, 6);
    assert_eq!((copy.id.as_str(), copy.title.as_str(), copy.server_updated_at), ("6", "Plan (conflicted copy)", None));
    assert!(conflict::is_conflicted_copy_title(&copy.title));
    assert_eq!(conflict::conflicted_copy_of(&Note::new(5, 1), 7).title, "Conflicted copy");
    // A title at the server's limit still fits once the suffix is added.
    note.title = "x".repeat(2000);
    assert_eq!(conflict::conflicted_copy_of(&note, 8).title.encode_utf16().count(), 2000);
}

#[test]
fn the_note_palette_matches_every_client() {
    use notelikeus_core::palette::{NOTE_COLORS, for_theme, index_of, to_store};
    let fixture = fixture("design/note-colors.json");
    let parse = |v: &Value| u32::from_str_radix(v.as_str().unwrap().trim_start_matches("0x"), 16).unwrap() as i32;
    let colors = fixture["colors"].as_array().unwrap();
    assert_eq!(colors.len(), NOTE_COLORS.len());
    for (i, entry) in colors.iter().enumerate() {
        assert_eq!(entry["index"], i);
        let ours = NOTE_COLORS[i];
        assert_eq!(ours.keyword, entry["keyword"].as_str().unwrap(), "keyword {i}");
        assert_eq!(ours.name, entry["name"].as_str().unwrap(), "name {i}");
        assert_eq!(ours.light, parse(&entry["light"]), "light {i}");
        assert_eq!(ours.dark, parse(&entry["dark"]), "dark {i}");
    }
    assert_eq!(parse(&fixture["noColor"]), 0);
    // One stored value, shown as the active theme's counterpart; stored as the variant on screen.
    let green = NOTE_COLORS[4];
    assert_eq!(for_theme(green.light, true), green.dark);
    assert_eq!(for_theme(green.dark, false), green.light);
    assert_eq!(index_of(green.dark), Some(4));
    assert_eq!(to_store(4, true), green.dark);
    assert_eq!(for_theme(0x12345678, true), 0x12345678, "a colour outside the palette is shown as stored");
    assert_eq!(for_theme(0, true), 0);
}

#[test]
fn theme_palettes_match_contracts_design_tokens() {
    use notelikeus_core::palette::{LIGHT_ACCENT_BLUE, LIGHT_ACCENT_GREEN, THEME_PALETTES};
    let fixture = fixture("design/theme-colors.json");
    let parse_hex = |v: &Value| {
        let hex = v.as_str().unwrap().trim_start_matches('#');
        0xFF00_0000 | u32::from_str_radix(hex, 16).unwrap()
    };
    let themes = fixture["themes"].as_object().unwrap();
    assert_eq!(themes.len(), THEME_PALETTES.len());
    for palette in &THEME_PALETTES {
        let expected = &themes[palette.key];
        assert!(expected.is_object(), "missing theme {}", palette.key);
        let compose = &expected["compose"];
        let want_dark = compose["base"].as_str().unwrap() == "DARK";
        let want_amoled = compose["amoled"].as_bool().unwrap();
        let want_accent = compose["accent"].as_str().unwrap().to_ascii_lowercase();
        assert_eq!(palette.dark, want_dark, "{}", palette.key);
        assert_eq!(palette.amoled, want_amoled, "{}", palette.key);
        assert_eq!(palette.accent_key, want_accent, "{}", palette.key);

        let colors = &expected["colors"];
        assert_eq!(palette.background, parse_hex(&colors["background"]), "{} background", palette.key);
        assert_eq!(palette.surface, parse_hex(&colors["surface"]), "{} surface", palette.key);
        assert_eq!(palette.surface_variant, parse_hex(&colors["surfaceVariant"]), "{} surfaceVariant", palette.key);
        assert_eq!(palette.on_surface, parse_hex(&colors["onSurface"]), "{} onSurface", palette.key);
        assert_eq!(palette.secondary, parse_hex(&colors["secondary"]), "{} secondary", palette.key);
        assert_eq!(palette.muted, parse_hex(&colors["muted"]), "{} muted", palette.key);
        assert_eq!(palette.outline, parse_hex(&colors["outline"]), "{} outline", palette.key);
    }
    assert_eq!(LIGHT_ACCENT_BLUE, parse_hex(&fixture["lightAccentPrimary"]["blue"]));
    assert_eq!(LIGHT_ACCENT_GREEN, parse_hex(&fixture["lightAccentPrimary"]["green"]));
}

#[test]
fn http_rpcs_match_latest_supabase_migrations() {
    use std::collections::{BTreeMap, BTreeSet};

    let migrations_dir = contracts_dir().parent().unwrap().join("supabase").join("migrations");
    let mut files: Vec<PathBuf> = std::fs::read_dir(&migrations_dir)
        .unwrap()
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| path.extension().and_then(|e| e.to_str()) == Some("sql"))
        .collect();
    files.sort();
    assert!(!files.is_empty(), "expected SQL migrations in {}", migrations_dir.display());

    let mut latest_signatures: BTreeMap<(String, usize), Vec<(String, bool)>> = BTreeMap::new();
    let needle = "create or replace function public.";
    for file in files {
        let sql = std::fs::read_to_string(&file).unwrap();
        let lowered = sql.to_ascii_lowercase();
        let mut search_from = 0;
        while let Some(pos) = lowered[search_from..].find(needle) {
            let start = search_from + pos + needle.len();
            let Some(open_paren) = sql[start..].find('(') else { break };
            let fn_name = sql[start..start + open_paren].trim().to_ascii_lowercase();
            let mut idx = start + open_paren + 1;
            let bytes = sql.as_bytes();
            let mut depth = 1usize;
            while idx < bytes.len() && depth > 0 {
                match bytes[idx] {
                    b'(' => depth += 1,
                    b')' => depth -= 1,
                    _ => {}
                }
                idx += 1;
            }
            let raw_params = &sql[start + open_paren + 1..idx.saturating_sub(1)];
            let mut params = Vec::new();
            let mut cur = String::new();
            let mut arg_depth = 0usize;
            for line in raw_params.lines() {
                let no_comment = line.split("--").next().unwrap_or("");
                for ch in no_comment.chars() {
                    match ch {
                        '(' => {
                            arg_depth += 1;
                            cur.push(ch);
                        }
                        ')' => {
                            arg_depth = arg_depth.saturating_sub(1);
                            cur.push(ch);
                        }
                        ',' if arg_depth == 0 => {
                            let piece = cur.trim();
                            if !piece.is_empty() {
                                let name = piece.split_whitespace().next().unwrap().to_ascii_lowercase();
                                let has_default = piece.to_ascii_lowercase().contains(" default ");
                                params.push((name, has_default));
                            }
                            cur.clear();
                        }
                        _ => cur.push(ch),
                    }
                }
                cur.push(' ');
            }
            let piece = cur.trim();
            if !piece.is_empty() {
                let name = piece.split_whitespace().next().unwrap().to_ascii_lowercase();
                let has_default = piece.to_ascii_lowercase().contains(" default ");
                params.push((name, has_default));
            }
            latest_signatures.insert((fn_name, params.len()), params);
            search_from = idx;
        }
    }

    // Verify every literal `.rpc("<name>", ...)` in `http.rs` is accounted for.
    let http_src = include_str!("../src/http.rs");
    let mut called_rpcs = BTreeSet::new();
    let mut from = 0;
    while let Some(pos) = http_src[from..].find(".rpc(\"") {
        let name_start = from + pos + ".rpc(\"".len();
        if let Some(end) = http_src[name_start..].find('"') {
            called_rpcs.insert(http_src[name_start..name_start + end].to_string());
            from = name_start + end + 1;
        } else {
            break;
        }
    }

    let args_row: CloudNoteRow = serde_json::from_value(fixture("cloud/note-row.json")["row"].clone()).unwrap();
    let apply_change_val =
        serde_json::to_value(ApplyNoteChangeArgs::new(&args_row.to_note(), None, Vec::new(), 0)).unwrap();
    let apply_change_keys: Vec<String> = apply_change_val.as_object().unwrap().keys().cloned().collect();
    let restore_keys = vec![
        "p_note_id".to_string(),
        "p_local_id".to_string(),
        "p_base_revision".to_string(),
        "p_title".to_string(),
        "p_content".to_string(),
        "p_client_timestamp".to_string(),
        "p_color".to_string(),
        "p_is_pinned".to_string(),
        "p_is_archived".to_string(),
        "p_is_trashed".to_string(),
        "p_position".to_string(),
        "p_reminder_timestamp".to_string(),
        "p_labels".to_string(),
        "p_checklist".to_string(),
    ];

    let mut apply_change_with_placements_keys = apply_change_keys.clone();
    apply_change_with_placements_keys.push("p_attachment_placements".into());
    let checks: Vec<(&str, Vec<String>)> = vec![
        ("get_sync_generation", vec![]),
        ("delete_all_user_cloud_data", vec![]),
        ("finalize_cloud_wipe", vec![]),
        ("fetch_snapshot_page", vec!["p_kind".into(), "p_after".into(), "p_limit".into()]),
        ("fetch_full_snapshot", vec![]),
        ("restore_note", restore_keys),
        ("list_pending_deleted_attachments", vec![]),
        ("purge_deleted_note_attachment", vec!["p_attachment_id".into(), "p_note_id".into()]),
        ("apply_note_change", apply_change_keys),
        ("apply_note_change_with_placements", apply_change_with_placements_keys),
        ("apply_note_delete", vec!["p_note_id".into(), "p_base_revision".into(), "p_wipe_generation".into()]),
        ("pull_changes", vec!["p_after_revision".into(), "p_limit".into()]),
        ("list_user_attachments", vec![]),
    ];

    let checked_names: BTreeSet<String> = checks.iter().map(|(name, _)| (*name).to_string()).collect();
    assert_eq!(called_rpcs, checked_names, "every RPC called in http.rs must be contract-checked");

    for (rpc_name, sent_params) in checks {
        let sent_set: BTreeSet<&str> = sent_params.iter().map(String::as_str).collect();
        let matched = latest_signatures.iter().find(|((name, _), sql_params)| {
            if name != rpc_name {
                return false;
            }
            let sql_names: BTreeSet<&str> = sql_params.iter().map(|(n, _)| n.as_str()).collect();
            sent_params.iter().all(|s| sql_names.contains(s.as_str()))
                && sql_params
                    .iter()
                    .all(|(sql_param, has_default)| sent_set.contains(sql_param.as_str()) || *has_default)
        });
        assert!(
            matched.is_some(),
            "RPC `{rpc_name}` with params {sent_params:?} does not match any overload in supabase/migrations"
        );
    }
}
