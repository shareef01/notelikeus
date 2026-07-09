import { Fragment } from 'react';

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** Wrap matching substrings in a highlight mark for search results. */
export function highlightSearchText(text: string, query: string) {
  const trimmed = query.trim();
  if (!trimmed) return text;

  const regex = new RegExp(`(${escapeRegExp(trimmed)})`, 'gi');
  const parts = text.split(regex);
  if (parts.length === 1) return text;

  const needle = trimmed.toLowerCase();
  return parts.map((part, index) =>
    part.toLowerCase() === needle ? (
      <mark
        key={index}
        className="rounded bg-brand-primary/35 px-0.5 text-inherit not-italic"
      >
        {part}
      </mark>
    ) : (
      <Fragment key={index}>{part}</Fragment>
    ),
  );
}
