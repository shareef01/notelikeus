/** Notes carrying each label, by label id: active and archived notes, not the trash. Matches native. */
export function labelNoteCounts(notes: ReadonlyArray<{ isTrashed: boolean; labels: ReadonlyArray<{ id: string }> }>) {
  const counts = new Map<string, number>();
  for (const note of notes) {
    if (note.isTrashed) continue;
    for (const id of new Set(note.labels.map((label) => label.id))) counts.set(id, (counts.get(id) ?? 0) + 1);
  }
  return counts;
}

export const noteCountText = (count: number) => (count === 0 ? 'No notes' : count === 1 ? '1 note' : `${count} notes`);
