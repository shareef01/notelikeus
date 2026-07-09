export interface ChecklistItem {
  id: string;
  text: string;
  isChecked: boolean;
  position: number;
}

export function createChecklistItem(
  partial: Partial<ChecklistItem> & Pick<ChecklistItem, 'text' | 'position'>,
): ChecklistItem {
  return {
    id: partial.id ?? `chk-${crypto.randomUUID()}`,
    text: partial.text,
    isChecked: partial.isChecked ?? false,
    position: partial.position,
  };
}

export function sortChecklistItems(items: ChecklistItem[]): ChecklistItem[] {
  return [...items].sort((a, b) => {
    if (a.isChecked !== b.isChecked) return a.isChecked ? 1 : -1;
    return a.position - b.position;
  });
}
