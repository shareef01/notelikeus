/**
 * Attachment metadata — mirrors Android Attachment model.
 * Binary payload lives in object storage (`storagePath`: `r2:` or `pending:` prefix on Web).
 */
export interface Attachment {
  id: string;
  noteId: number;
  storagePath: string;
  type: string;
  mimeType?: string;
  sizeBytes?: number;
}

export interface AttachmentCloudMap {
  storagePath: string;
  type: string;
  mimeType?: string;
  sizeBytes?: number;
}
