/**
 * Of the ids the cloud held last time, the ones whose absence now is *unaccounted for*.
 *
 * Both cloud read paths — the subscription's baseline load and the reconcile sync — have to
 * answer the same question before they can act on an empty snapshot: is this the cloud actually
 * being empty, or a fetch that failed open? A fetch that fails open (an expired token, a truncated
 * page, a cached default) loses data in both directions: the reconcile treats every absent id as
 * deleted-elsewhere, and the upload path reads the same empty result as "no remote is newer".
 *
 * A tombstone is the explanation. A genuine remote delete leaves one, and cloud tombstones are
 * merged before this is asked, so an id with a tombstone is accounted for and an id without one is
 * not. Counting *every* previously-known id instead — which `syncNotesWithCloud` used to do — means
 * a user who deletes their last note on another device, or empties the trash there, looks exactly
 * like a failed read and has every later sync refused.
 *
 * Pure on purpose: the tombstone lookup differs between the two call sites (one has the snapshot's
 * tombstone ids in hand, the other has already merged them into the store), and neither store
 * access belongs in the rule itself.
 */
export function unexplainedMissingCloudIds(
  knownCloudIds: Iterable<string>,
  presentIds: ReadonlySet<string>,
  isTombstoned: (id: string) => boolean,
): string[] {
  const seen = new Set<string>();
  const missing: string[] = [];
  for (const id of knownCloudIds) {
    if (seen.has(id)) continue;
    seen.add(id);
    if (presentIds.has(id)) continue;
    if (isTombstoned(id)) continue;
    missing.push(id);
  }
  return missing;
}
