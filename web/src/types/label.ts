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
