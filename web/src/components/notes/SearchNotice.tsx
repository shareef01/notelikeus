interface SearchNoticeProps {
  query: string;
}

/**
 * Says that the visible notes are near misses, not exact matches.
 *
 * Copy matches native `search_did_you_mean`. Shown only when the fuzzy fallback ran — a list of
 * near matches looks exactly like a list of matches, and leaving that unsaid would make a typo
 * look like a successful search.
 */
export function SearchNotice({ query }: SearchNoticeProps) {
  return (
    <p
      role="status"
      className="px-shell py-2 text-sm text-brand-muted"
    >
      {`No exact match for “${query}” — showing near matches`}
    </p>
  );
}

/**
 * Names the operators the search box could not use, as native `search_unknown_operator` does.
 *
 * `labe:work` is neither a filter nor searched for as text; without this the list would simply
 * ignore part of what was typed.
 */
export function IgnoredOperatorsNotice({ operators }: { operators: string[] }) {
  if (operators.length === 0) return null;
  return (
    <p role="status" className="px-shell py-2 text-sm text-brand-muted">
      {`Ignored: ${operators.join(', ')}`}
    </p>
  );
}
