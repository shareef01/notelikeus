const UUID_REGEX =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function newCloudId(): string {
  return crypto.randomUUID();
}

export function isValidCloudId(value: string | null | undefined): boolean {
  return typeof value === 'string' && value.length > 0 && UUID_REGEX.test(value);
}

export function ensureCloudId(cloudId?: string | null): string {
  return isValidCloudId(cloudId) ? cloudId! : newCloudId();
}

export function resolveCloudIdFromDocument(
  documentId: string,
  data: { cloudId?: string },
): string {
  if (isValidCloudId(data.cloudId)) return data.cloudId!;
  if (isValidCloudId(documentId)) return documentId;
  return newCloudId();
}

export function noteCloudDocumentId(note: { cloudId?: string }): string {
  return ensureCloudId(note.cloudId);
}
