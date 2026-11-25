//! Read-only Markdown rendering. The editor continues to own the original source and selection.

use std::borrow::Cow;
use std::ops::Range;

use notelikeus_core::attachments::PlacementBody;
use pulldown_cmark::{CodeBlockKind, Event, HeadingLevel, Options, Parser, Tag, TagEnd};
use slint::StyledText;

use crate::{DocumentBlock, DocumentKind, Picture};

type MarkdownEdit = (Range<usize>, Cow<'static, str>);

struct CodeCapture {
    // A nested block is substituted in its list. A top-level block has its own dedicated view.
    replacement: Option<(Range<usize>, usize)>,
    text: String,
}

fn styled(source: &str) -> StyledText {
    StyledText::from_markdown(source).unwrap_or_else(|_| StyledText::from_plain_text(source))
}

pub(crate) fn blocks(source: &str) -> Vec<DocumentBlock> {
    blocks_within_quote(source, 0)
}

/// Skip boundaries inside fenced code, lists, quotes, or Markdown spans that cross a line.
pub(crate) fn picture_offsets(source: &str, body: &PlacementBody) -> Vec<(usize, u64)> {
    let mut ranges: Vec<_> = Parser::new_ext(source, Options::ENABLE_TASKLISTS | Options::ENABLE_STRIKETHROUGH)
        .into_offset_iter()
        .filter_map(|(event, range)| match event {
            Event::Start(Tag::Paragraph) => None,
            Event::Start(_) | Event::Code(_) | Event::Html(_) => Some(range),
            _ => None,
        })
        .collect();
    ranges.sort_unstable_by_key(|range| range.start);
    let mut protected: Vec<Range<usize>> = Vec::new();
    for range in ranges {
        if let Some(last) = protected.last_mut().filter(|last| range.start < last.end) {
            last.end = last.end.max(range.end);
        } else {
            protected.push(range);
        }
    }
    let mut bytes = 0;
    let mut scalars = 0;
    source
        .split_inclusive('\n')
        .filter_map(|line| {
            bytes += line.len();
            scalars += line.chars().count() as u64;
            let index = protected.partition_point(|range| range.end <= bytes);
            (body.is_boundary(scalars) && protected.get(index).is_none_or(|range| bytes <= range.start))
                .then_some((bytes, scalars))
        })
        .collect()
}

pub(crate) fn blocks_with_pictures(source: &str, mut pictures: Vec<Picture>) -> Vec<DocumentBlock> {
    pictures.sort_unstable_by_key(|picture| (picture.inline_byte, picture.inline_order));
    let mut result = Vec::new();
    let mut start = 0;
    for picture in pictures.into_iter().filter(|picture| picture.inline_byte > 0) {
        let end = picture.inline_byte as usize;
        let Some(text) = source.get(start..end) else { continue };
        result.extend(blocks(text));
        result.push(DocumentBlock { kind: DocumentKind::Picture, picture, ..Default::default() });
        start = end;
    }
    result.extend(blocks(&source[start..]));
    result
}

fn blocks_within_quote(source: &str, quote_depth: i32) -> Vec<DocumentBlock> {
    const MAX_QUOTE_DEPTH: i32 = 8;
    let options = Options::ENABLE_TASKLISTS | Options::ENABLE_STRIKETHROUGH;
    let mut blocks = Vec::new();
    let mut depth = 0;
    let mut end = 0;
    let mut edits = Vec::new();
    let mut prose_starts = Vec::new();
    let mut code_capture: Option<CodeCapture> = None;
    let mut list_depth = 0;
    let mut outer_list = None;
    for (event, range) in Parser::new_ext(source, options).into_offset_iter() {
        if let Some(capture) = &mut code_capture {
            match event {
                Event::Text(line) => capture.text.push_str(&line),
                Event::End(TagEnd::CodeBlock) => {
                    if let Some((range, indent)) = capture.replacement.take() {
                        edits.push((range, Cow::Owned(list_code(&capture.text, indent))));
                    } else {
                        blocks.push(code(&capture.text));
                    }
                    code_capture = None;
                    depth -= 1;
                }
                _ => {}
            }
            continue;
        }
        let mut quote = None;
        let block = match &event {
            Event::Start(Tag::Heading { level, .. }) if depth == 0 => {
                let heading = match level {
                    HeadingLevel::H1 => 1,
                    HeadingLevel::H2 => 2,
                    _ => 3,
                };
                Some(prose(heading_content(&source[range.clone()]), heading))
            }
            Event::Start(Tag::CodeBlock(_)) if depth == 0 => {
                code_capture = Some(CodeCapture { replacement: None, text: String::new() });
                None
            }
            Event::Start(Tag::CodeBlock(kind)) if list_depth > 0 && range.start >= end => {
                if let Some(replacement) = list_code_range(source, range.clone(), kind) {
                    code_capture = Some(CodeCapture { replacement: Some(replacement), text: String::new() });
                }
                None
            }
            Event::Start(Tag::BlockQuote(_)) if depth == 0 && quote_depth < MAX_QUOTE_DEPTH => {
                quote = Some(quote_content(&source[range.clone()]));
                None
            }
            Event::Rule if depth == 0 => {
                Some(DocumentBlock { kind: DocumentKind::Divider, description: "Divider".into(), ..Default::default() })
            }
            Event::Start(_) if depth == 0 => {
                prose_starts.push(range.start);
                None
            }
            _ => None,
        };
        if depth == 0 && (block.is_some() || code_capture.is_some() || quote.is_some()) {
            append_prose(&mut blocks, source, end..range.start, &prose_starts, &edits);
            blocks.extend(block);
            if let Some(content) = quote {
                blocks.extend(blocks_within_quote(&content, quote_depth + 1));
            }
            edits.clear();
            prose_starts.clear();
            end = range.end;
        }
        match event {
            Event::Start(Tag::List(_)) => {
                if depth == 0 {
                    outer_list = Some((range, edits.len()));
                }
                depth += 1;
                list_depth += 1;
            }
            Event::End(TagEnd::List(_)) => {
                depth -= 1;
                list_depth -= 1;
                if depth == 0
                    && let Some((range, first_edit)) = outer_list.take()
                    && edits[first_edit..].iter().any(|(_, replacement)| matches!(replacement, Cow::Owned(_)))
                {
                    remove_list_blank_lines(source, range, &mut edits, first_edit);
                }
            }
            Event::Start(_) => depth += 1,
            Event::End(_) => depth -= 1,
            Event::TaskListMarker(checked) if range.start >= end => {
                edits.push((range, Cow::Borrowed(if checked { "✓" } else { "□" })))
            }
            _ => {}
        }
    }
    append_prose(&mut blocks, source, end..source.len(), &prose_starts, &edits);
    for block in &mut blocks {
        block.quote_depth = block.quote_depth.max(quote_depth);
    }
    blocks
}

fn heading_content(source: &str) -> &str {
    let source = source.trim();
    if source.starts_with('#') {
        let title = source.trim_start_matches('#').trim_start();
        let without_marks = title.trim_end_matches('#');
        if without_marks.ends_with([' ', '\t']) { without_marks.trim_end() } else { title }
    } else {
        // A setext heading ends in a separate line containing only the underline.
        source.rsplit_once('\n').map_or(source, |(title, _)| title.trim_end())
    }
}

fn prose(source: &str, heading: i32) -> DocumentBlock {
    DocumentBlock {
        text: styled(source),
        description: crate::view::strip_markdown(source.trim()).into(),
        heading,
        kind: DocumentKind::Prose,
        ..Default::default()
    }
}

fn code(text: &str) -> DocumentBlock {
    // The parser includes the line ending before the closing fence.
    let text = text.strip_suffix('\n').unwrap_or(text);
    DocumentBlock {
        text: StyledText::from_plain_text(text),
        description: text.into(),
        heading: 0,
        kind: DocumentKind::Code,
        ..Default::default()
    }
}

fn list_code_range(source: &str, mut range: Range<usize>, kind: &CodeBlockKind<'_>) -> Option<(Range<usize>, usize)> {
    let line_start = source[..range.start].rfind('\n').map_or(0, |index| index + 1);
    let prefix = &source[line_start..range.start];
    if prefix.len() > 64
        || !prefix.bytes().all(|byte| matches!(byte, b' ' | b'-' | b'+' | b'*' | b'.' | b')' | b'0'..=b'9'))
    {
        return None;
    }
    if matches!(kind, CodeBlockKind::Indented) {
        if !prefix.ends_with("    ") {
            return None;
        }
        range.start -= 4;
    }
    // Indented and unclosed fenced ranges can include the final line break.
    if source[range.clone()].ends_with('\n') {
        range.end -= if source[range.clone()].ends_with("\r\n") { 2 } else { 1 };
    }
    let indent = range.start - line_start;
    Some((range, indent))
}

fn list_code(text: &str, indent: usize) -> String {
    // Slint handles inline code inside a list. Render one span per line with explicit line breaks.
    let mut replacement = String::new();
    let continuation = format!("  \n{}", " ".repeat(indent));
    for (index, line) in text.strip_suffix('\n').unwrap_or(text).split('\n').enumerate() {
        let line = line.trim_end_matches('\r');
        if index > 0 {
            replacement.push_str(&continuation);
        }
        let longest_ticks = line.split(|character| character != '`').map(str::len).max().unwrap_or(0);
        let delimiter = "`".repeat(longest_ticks + 1);
        replacement.push_str(&delimiter);
        if line.is_empty() {
            replacement.push('\u{a0}');
        } else if line.bytes().all(|character| character == b' ') {
            replacement.push_str(line);
        } else {
            // CommonMark removes one pad space on each end, leaving the source indentation intact.
            replacement.push(' ');
            replacement.push_str(line);
            replacement.push(' ');
        }
        replacement.push_str(&delimiter);
    }
    replacement
}

fn remove_list_blank_lines(source: &str, range: Range<usize>, edits: &mut Vec<MarkdownEdit>, first_edit: usize) {
    // Slint lays out bullets separately from their text in loose lists. A tight list keeps that
    // text, including the rewritten code lines, alongside the bullet or number.
    let list = source[range.clone()].trim_end();
    let mut start = range.start;
    let mut blanks = Vec::new();
    for line in list.split_inclusive('\n') {
        let end = start + line.len();
        if line.trim().is_empty() {
            let next_edit = edits[first_edit..].partition_point(|(edit, _)| edit.end <= start);
            if edits.get(first_edit + next_edit).is_none_or(|(edit, _)| end <= edit.start) {
                blanks.push((start..end, Cow::Borrowed("")));
            }
        }
        start = end;
    }
    edits.extend(blanks);
    edits[first_edit..].sort_by_key(|(edit, _)| edit.start);
}

fn quote_content(source: &str) -> String {
    source
        .lines()
        .map(|line| {
            let Some(quoted) = line.trim_start_matches(' ').strip_prefix('>') else { return line };
            quoted.strip_prefix(' ').unwrap_or(quoted)
        })
        .collect::<Vec<_>>()
        .join("\n")
}

fn append_prose(
    blocks: &mut Vec<DocumentBlock>,
    source: &str,
    range: Range<usize>,
    starts: &[usize],
    edits: &[MarkdownEdit],
) {
    if source[range.clone()].trim().is_empty() {
        return;
    }
    let prepare = |range: Range<usize>| {
        let start = range.start;
        let first = edits.partition_point(|(edit, _)| edit.start < start);
        let last = edits.partition_point(|(edit, _)| edit.end <= range.end);
        let text = &source[range];
        if first == last {
            return Cow::Borrowed(text);
        }
        let mut text = text.to_owned();
        for (edit, replacement) in edits[first..last].iter().rev() {
            text.replace_range(edit.start - start..edit.end - start, replacement);
        }
        Cow::Owned(text)
    };
    let prepared = prepare(range.clone());
    if let Ok(text) = StyledText::from_markdown(&prepared) {
        blocks.push(DocumentBlock {
            text,
            description: crate::view::strip_markdown(prepared.trim()).into(),
            ..Default::default()
        });
    } else {
        // A block Slint cannot render should not make its neighboring paragraphs lose styling.
        let boundaries = starts.iter().copied().chain(std::iter::once(range.end));
        let mut start = range.start;
        for end in boundaries {
            if !source[start..end].trim().is_empty() {
                blocks.push(prose(&prepare(start..end), 0));
            }
            start = end;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn picture_positions_do_not_split_code_fences_or_list_items() {
        let source = "First 🧭\n\n```\ncode\n```\n\n- one\n- two\n\nLast";
        let offsets = picture_offsets(source, &PlacementBody::new(source));
        let prefixes: Vec<_> = offsets.iter().map(|(byte, _)| &source[..*byte]).collect();
        assert!(prefixes.contains(&"First 🧭\n"));
        assert!(prefixes.iter().any(|prefix| prefix.ends_with("code\n```\n")));
        assert!(!prefixes.iter().any(|prefix| prefix.ends_with("```\ncode\n") || prefix.ends_with("- one\n")));
        assert!(prefixes.contains(&source));
    }

    #[test]
    fn renders_headings_and_task_lists_without_changing_source() {
        let source = "# Travel **plan**\n\n- [ ] Passport\n- [x] Ticket\n\nPacking\n-------\n\nLast paragraph";
        let preview = blocks(source);
        assert_eq!(preview.iter().map(|block| block.heading).collect::<Vec<_>>(), [1, 0, 2, 0]);
        assert_eq!(preview[0].text, styled("Travel **plan**"));
        assert_eq!(preview[1].text, styled("- □ Passport\n- ✓ Ticket"));
        assert_eq!(preview[2].text, styled("Packing"));
    }

    #[test]
    fn code_and_dividers_do_not_remove_formatting_from_prose() {
        let source = "**Before**\n\n```rust\n# comment\n    let x = 1;\n```\n\n---\n\n*After*";
        let preview = blocks(source);
        assert_eq!(
            preview.iter().map(|block| block.kind).collect::<Vec<_>>(),
            [DocumentKind::Prose, DocumentKind::Code, DocumentKind::Divider, DocumentKind::Prose,]
        );
        assert_eq!(preview[0].text, styled("**Before**"));
        assert_eq!(preview[1].text, StyledText::from_plain_text("# comment\n    let x = 1;"));
        assert_eq!(preview[1].description, "# comment\n    let x = 1;");
        assert_eq!(preview[3].text, styled("*After*"));
    }

    #[test]
    fn quote_keeps_inline_formatting_and_does_not_consume_later_task_markers() {
        let preview = blocks("> **Quoted** text\n>\n> A second paragraph\n\n- [x] Outside the quote");
        assert_eq!(preview[0].quote_depth, 1);
        assert_eq!(preview[0].text, styled("**Quoted** text\n\nA second paragraph"));
        assert_eq!(preview[1].text, styled("- ✓ Outside the quote"));
        assert_eq!(preview[1].description, "- ✓ Outside the quote");
    }

    #[test]
    fn nested_quotes_keep_code_tasks_and_neighboring_prose_in_order() {
        let source =
            "> **Outer**\n>\n> > - [x] Inside\n> >\n> > ```text\n> > # literal\n> > ```\n>\n> *Outer again*\n\nOutside";
        let preview = blocks(source);
        assert_eq!(preview.iter().map(|block| block.quote_depth).collect::<Vec<_>>(), [1, 2, 2, 1, 0]);
        assert_eq!(preview[1].text, styled("- ✓ Inside"));
        assert_eq!(preview[2].kind, DocumentKind::Code);
        assert_eq!(preview[2].description, "# literal");
        assert_eq!(preview[3].text, styled("*Outer again*"));
    }

    #[test]
    fn unsupported_html_falls_back_only_for_its_own_block() {
        let preview = blocks("**Before**\n\n<div>Literal HTML</div>\n\n*After*");
        assert_eq!(preview.len(), 3);
        assert_eq!(preview[0].text, styled("**Before**"));
        assert!(preview[1].description.contains("<div>Literal HTML</div>"));
        assert_eq!(preview[2].text, styled("*After*"));
    }

    #[test]
    fn indented_code_preserves_relative_indent() {
        let preview = blocks("    first\n        second\n");
        assert_eq!(preview.len(), 1);
        assert_eq!(preview[0].kind, DocumentKind::Code);
        assert_eq!(preview[0].description, "first\n    second");
    }

    #[test]
    fn code_inside_lists_keeps_the_list_and_inline_formatting() {
        let cases = [
            (
                "- **Item**\n\n  ```text\n  a\n      `b`\n  ```\n\n- _After_",
                "- **Item**\n  ` a `  \n  ``     `b` ``\n- _After_",
            ),
            ("- ```\n  one\n  two\n  ```\n- **Next**", "- ` one `  \n  ` two `\n- **Next**"),
            ("10. first\n\n        code\n\n11. **Next**", "10. first\n    ` code `\n11. **Next**"),
        ];
        for (source, rendered) in cases {
            let preview = blocks(source);
            let expected = StyledText::from_markdown(rendered).unwrap();
            assert_eq!(preview.len(), 1);
            assert_eq!(preview[0].text, expected, "{source:?}");
        }
    }
}
