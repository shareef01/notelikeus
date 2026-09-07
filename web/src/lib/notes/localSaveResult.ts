/**
 * Outcome of writing the editor's contents to IndexedDB.
 *
 * Local durability and cloud sync are separate concerns, and conflating them is how a network
 * failure ends up reported as a lost note. Only IndexedDB accepting the write produces `saved`;
 * an attachment upload or Supabase push failing afterwards leaves the save `saved` and is
 * surfaced as pending sync instead.
 */
export type LocalSaveResult =
  | { status: 'saved'; noteId: string }
  | { status: 'unchanged' }
  | { status: 'failed'; error: unknown };

export function isLocalSaveFailure(
  result: LocalSaveResult,
): result is { status: 'failed'; error: unknown } {
  return result.status === 'failed';
}
