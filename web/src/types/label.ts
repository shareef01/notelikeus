export interface Label {
  /** Local-only id for UI; cloud stores label names embedded on notes. */
  id: string;
  name: string;
}

export function labelFromName(name: string, id?: string): Label {
  return {
    id: id ?? `label-${name.trim().toLowerCase().replace(/\s+/g, '-')}`,
    name: name.trim(),
  };
}

/** Distinct labels across the given notes, deduped case-insensitively and sorted by name. */
export function collectUniqueLabels(notes: readonly { labels: readonly Label[] }[]): Label[] {
  const map = new Map<string, Label>();
  for (const note of notes) {
    for (const label of note.labels) {
      map.set(label.name.toLowerCase(), label);
    }
  }
  return Array.from(map.values()).sort((a, b) => a.name.localeCompare(b.name));
}
