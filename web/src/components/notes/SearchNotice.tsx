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
      className="px-4 py-2 text-sm text-brand-on-surface-variant"
    >
      {`No exact match for “${query}” — showing near matches`}
    </p>
  );
}
