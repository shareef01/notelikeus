export interface AttachmentBlobUploadResult {
  objectKey: string;
  sizeBytes: number;
  mimeType: string;
}

export interface AttachmentBlobStore {
  upload(
    noteId: string,
    attachmentId: string,
    blob: Blob,
    mimeType: string,
  ): Promise<AttachmentBlobUploadResult>;
  download(noteId: string, attachmentId: string): Promise<Blob>;
  delete(noteId: string, attachmentId: string): Promise<void>;
}
