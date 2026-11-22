//! Search: the operators against `contracts/query/parser-vectors.json` (the vectors the Kotlin and web
//! parsers read), and the matching, ranking, filters, smart views and saved filters ported from the web.

use std::path::PathBuf;

use notelikeus_core::model::{ChecklistItem, Note};
use notelikeus_core::query::{
    self, DatePreset, DateRange, Days, Filters, LabelMatch, NoteFlag, NoteScope, OPEN_END, SMART_VIEWS, parse,
};
use serde_json::Value;

fn contracts_dir() -> PathBuf {
    let mut dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    loop {
        let candidate = dir.join("contracts");
        if candidate.is_dir() {
            return candidate;
        }
        assert!(dir.pop(), "contracts/ not found");
    }
}

/// The vectors' clock: "today" starts at `todayStart`; a civil date is its UTC midnight.
struct VectorClock {
    today_start: i64,
}

const DAY: i64 = 86_400_000;

/// Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's algorithm).
fn days_from_civil(year: i64, month: u32, day: u32) -> i64 {
    let y = if month <= 2 { year - 1 } else { year };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let m = i64::from(month);
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + i64::from(day) - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

fn days_in_month(year: i64, month: u32) -> u32 {
    let leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
    match month {
        2 if leap => 29,
        2 => 28,
        4 | 6 | 9 | 11 => 30,
        _ => 31,
    }
}

impl Days for VectorClock {
    fn day_start(&self, days_from_today: i64) -> i64 {
        self.today_start + days_from_today * DAY
    }
    fn day_start_of_date(&self, year: i64, month: u32, day: u32) -> Option<i64> {
        (day <= days_in_month(year, month)).then(|| days_from_civil(year, month, day) * DAY)
    }
}

fn clock() -> VectorClock {
    VectorClock { today_start: 1_786_492_800_000 }
}

fn strings(value: &Value) -> Vec<String> {
    value.as_array().unwrap().iter().map(|v| v.as_str().unwrap().to_string()).collect()
}

#[test]
fn the_parser_matches_the_shared_vectors() {
    let text = std::fs::read_to_string(contracts_dir().join("query/parser-vectors.json")).unwrap();
    let vectors: Value = serde_json::from_str(&text).unwrap();
    let clock = clock();
    let cases = vectors["cases"].as_array().unwrap();
    assert!(cases.len() >= 40);
    for case in cases {
        let input = case["input"].as_str().unwrap();
        let expected = &case["expected"];
        let parsed = parse(input, &clock);
        let mut flags: Vec<&str> = parsed.flags.iter().map(|f| f.name()).collect();
        flags.sort_unstable();
        assert_eq!(parsed.text, expected["text"].as_str().unwrap(), "text of {input:?}");
        assert_eq!(parsed.label_names, strings(&expected["labelNames"]), "labels of {input:?}");
        assert_eq!(parsed.color_names, strings(&expected["colorNames"]), "colours of {input:?}");
        assert_eq!(flags, strings(&expected["flags"]), "flags of {input:?}");
        assert_eq!(parsed.scope.map(|s| s.name()), expected["scope"].as_str(), "scope of {input:?}");
        assert_eq!(parsed.date_field.map(|f| f.name()), expected["dateField"].as_str(), "date field of {input:?}");
        assert_eq!(parsed.before, expected["before"].as_i64(), "before of {input:?}");
        assert_eq!(parsed.after, expected["after"].as_i64(), "after of {input:?}");
        assert_eq!(parsed.unknown, strings(&expected["unknown"]), "unknown of {input:?}");
    }
}

fn note(id: &str, title: &str, content: &str) -> Note {
    Note {
        id: id.into(),
        local_id: 1,
        title: title.into(),
        content: content.into(),
        timestamp: 1_786_492_800_000,
        color: 0,
        is_pinned: false,
        is_archived: false,
        is_trashed: false,
        position: 0,
        reminder_timestamp: None,
        server_updated_at: None,
        labels: Vec::new(),
        checklist: Vec::new(),
    }
}

fn ids(outcome: &query::Outcome) -> Vec<String> {
    outcome.notes.iter().map(|n| n.id.clone()).collect()
}

fn search(notes: &[Note], filters: &Filters, labels: &[&str]) -> Vec<String> {
    let labels: Vec<String> = labels.iter().map(|l| l.to_string()).collect();
    ids(&query::search(notes.iter().collect(), filters, &labels, 1_786_550_000_000, &clock()))
}

fn typed(text: &str) -> Filters {
    Filters { search: text.into(), ..Filters::default() }
}

#[test]
fn text_folds_case_accents_and_punctuation_and_matches_word_prefixes() {
    assert_eq!(query::text::fold("  Café, CRÈME-brûlée!  Straße "), "cafe creme brulee strasse");
    assert_eq!(query::text::tokens("Œuvre  ÆON"), ["oeuvre", "aeon"]);
    let haystack = query::text::fold("My notes about cafés");
    assert!(query::text::matches_tokens(&haystack, &query::text::tokens("cafe not")));
    assert!(!query::text::matches_tokens(&haystack, &query::text::tokens("ote")), "mid-word does not match");
}

#[test]
fn a_typo_falls_back_to_near_matches_and_says_so() {
    let notes = [note("a", "Groceries", "sourdough bread"), note("b", "Trip", "passport")];
    let outcome = query::search(notes.iter().collect(), &typed("sourdogh"), &[], 0, &clock());
    assert!(outcome.is_fuzzy);
    assert_eq!(ids(&outcome), ["a"]);
    let outcome = query::search(notes.iter().collect(), &typed("sour"), &[], 0, &clock());
    assert!(!outcome.is_fuzzy, "an exact prefix is not a near match");
    // Short words are never corrected.
    assert!(query::search(notes.iter().collect(), &typed("brd"), &[], 0, &clock()).notes.is_empty());
    assert!(query::rank::edit_distance_within("kitten", "sitting", 3));
    assert!(!query::rank::edit_distance_within("kitten", "sitting", 2));
}

#[test]
fn relevance_puts_title_matches_first_and_pins_lead() {
    let mut body = note("body", "Weekend", "milk and eggs");
    body.timestamp += 10;
    let title = note("title", "Milk", "");
    let label = Note { labels: vec!["Milk run".into()], ..note("label", "Errands", "") };
    let mut pinned = note("pinned", "Other", "milk");
    pinned.is_pinned = true;
    let notes = [body, title, label, pinned];
    assert_eq!(search(&notes, &typed("milk"), &[]), ["pinned", "title", "label", "body"]);
}

#[test]
fn operators_narrow_by_label_colour_flag_scope_and_date() {
    let green = notelikeus_core::palette::NOTE_COLORS[4];
    let work = Note { labels: vec!["Work".into()], color: green.dark, ..note("work", "Report", "") };
    let mut list = note("list", "Packing", "");
    list.checklist = vec![
        ChecklistItem { text: "passport".into(), is_checked: true, position: 0 },
        ChecklistItem { text: "charger".into(), is_checked: false, position: 1 },
    ];
    let mut old = note("old", "Receipts", "");
    old.is_archived = true;
    old.timestamp = 1_700_000_000_000;
    let mut trashed = note("trashed", "Scratch", "https://example.com");
    trashed.is_trashed = true;
    let notes = [work, list, old, trashed];

    assert_eq!(search(&notes, &typed("label:work"), &["Work"]), ["work"]);
    assert_eq!(search(&notes, &typed("label:WORK"), &["Work"]), ["work"], "labels ignore case");
    assert_eq!(search(&notes, &typed("label:nowhere"), &["Work"]).len(), 2, "an unknown label narrows nothing");
    assert_eq!(search(&notes, &typed("color:green"), &[]), ["work"], "either variant of the hue");
    assert_eq!(search(&notes, &typed("has:unchecked"), &[]), ["list"]);
    assert_eq!(search(&notes, &typed("is:unlabeled"), &[]), ["list"]);
    assert_eq!(search(&notes, &typed("in:archive"), &[]), ["old"]);
    assert_eq!(search(&notes, &typed("in:trash has:links"), &[]), ["trashed"]);
    assert_eq!(search(&notes, &typed("in:all before:2024-01-01"), &[]), ["old"]);
    assert_eq!(search(&notes, &typed("in:all after:today"), &[]).len(), 3);

    let outcome = query::search(notes.iter().collect(), &typed("labe:work is:nonsense"), &[], 0, &clock());
    assert_eq!(outcome.unknown, ["is:nonsense"], "an unknown operator is reported");
    assert_eq!(outcome.search_text, "labe:work", "a word that only looks like one is searched for");
}

#[test]
fn the_sheet_combines_labels_any_or_all_and_adds_to_typed_operators() {
    let both = Note { labels: vec!["Work".into(), "Urgent".into()], ..note("both", "A", "") };
    let one = Note { labels: vec!["Work".into()], ..note("one", "B", "") };
    let notes = [both, one];
    let mut filters = Filters { label_names: vec!["work".into(), "urgent".into()], ..Filters::default() };
    assert_eq!(search(&notes, &filters, &[]).len(), 2);
    filters.label_match = LabelMatch::All;
    assert_eq!(search(&notes, &filters, &[]), ["both"]);

    let mut flagged = Filters::default();
    flagged.toggle_flag(NoteFlag::Pinned);
    flagged.search = "is:untitled".into();
    let effective = query::effective(&flagged, &[], &clock());
    assert_eq!(effective.flags, [NoteFlag::Pinned, NoteFlag::Untitled]);
}

#[test]
fn date_presets_are_windows_from_a_day_start() {
    let clock = clock();
    let week = DatePreset::Last7.range(&clock).unwrap();
    assert_eq!(week, DateRange { from: clock.day_start(-7), to: OPEN_END });
    assert_eq!(DatePreset::Any.range(&clock), None);
    assert_eq!(DatePreset::of(Some(week), &clock), Some(DatePreset::Last7));
    assert_eq!(DatePreset::of(None, &clock), Some(DatePreset::Any));
    assert_eq!(DatePreset::of(Some(DateRange { from: 1, to: 2 }), &clock), None);
}

#[test]
fn smart_views_count_active_notes_and_replace_the_filters() {
    let mut list = note("list", "Packing", "");
    list.checklist = vec![ChecklistItem { text: "socks".into(), is_checked: false, position: 0 }];
    let mut reminded = Note { labels: vec!["Home".into()], ..note("r", "Call", "") };
    reminded.reminder_timestamp = Some(1);
    let mut archived = note("arch", "Old", "");
    archived.is_archived = true;
    assert_eq!(query::smart_view_counts([&list, &reminded, &archived], 0), [1, 1, 1]);

    let current = Filters { colors: vec![2], ..Filters::in_scope(NoteScope::Archive) };
    let view = SMART_VIEWS[1].filters(&current);
    assert_eq!(view, typed("has:unchecked"));
    assert_eq!(query::active_smart_view(&view), Some(1));
    assert_eq!(
        query::active_smart_view(&Filters { colors: vec![2], ..view }),
        None,
        "anything added makes it another list"
    );
}

#[test]
fn saved_filters_replace_by_name_keep_the_newest_and_match_what_is_on_screen() {
    let invoices = Filters { search: " label:invoices ".into(), colors: vec![3, 4], ..Filters::default() };
    let list = query::with_saved(&[], "  Invoices  ", &invoices);
    assert_eq!(list[0].name, "Invoices");
    assert_eq!(list[0].filters.search, "label:invoices", "the text is stored trimmed");
    let same = Filters { colors: vec![4, 3], ..invoices.clone() };
    assert_eq!(query::current_saved(&list, &same).map(|f| f.name.as_str()), Some("Invoices"), "sets compare as sets");

    let replaced = query::with_saved(&list, "INVOICES", &Filters::default());
    assert_eq!(replaced.len(), 1, "the same name in another case replaces it");
    assert_eq!(query::with_saved(&list, "   ", &invoices), list, "an empty name saves nothing");
    let long = "x".repeat(60);
    assert_eq!(query::with_saved(&[], &long, &invoices)[0].name.chars().count(), query::SAVED_FILTER_MAX_NAME);

    let mut many = Vec::new();
    for i in 0..25 {
        many = query::with_saved(&many, &format!("F{i}"), &invoices);
    }
    assert_eq!(many.len(), query::SAVED_FILTER_MAX);
    assert_eq!(many[0].name, "F24", "newest first; the oldest drop off");
    assert!(query::find_saved(&many, "f4").is_none());
    assert_eq!(query::without_saved(&many, "F24").len(), query::SAVED_FILTER_MAX - 1);
}

#[test]
fn cleared_filters_keep_where_you_are() {
    let filters = Filters {
        scope: NoteScope::Archive,
        search: "milk".into(),
        colors: vec![1],
        label_match: LabelMatch::All,
        date_range: Some(DateRange { from: 0, to: 1 }),
        ..Filters::default()
    };
    assert!(filters.has_active());
    assert_eq!(filters.sheet_count(), 2);
    let cleared = filters.cleared();
    assert!(!cleared.has_active());
    assert_eq!((cleared.scope, cleared.label_match), (NoteScope::Archive, LabelMatch::All));
}
