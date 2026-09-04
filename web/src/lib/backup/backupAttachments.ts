import { getAttachmentBlobStore } from '@/lib/attachments/attachmentBlobStoreRegistry';
import {
  createAttachmentId,
  isPendingAttachment,
  isR2Attachment,
  MAX_ATTACHMENT_BYTES,
  pendingStoragePath,
  ATTACHMENT_PENDING_PREFIX,
} from '@/lib/attachments/attachmentPaths';
import { peekPendingAttachment, storePendingAttachment } from '@/lib/attachments/pendingAttachmentStore';
import type { Attachment } from '@/types/attachment';
import type { Note } from '@/types/note';

export const MIN_BACKUP_VERSION_WITH_ATTACHMENTS = 3;
export const MAX_NOTE_BACKUP_ATTACHMENTS = 20;

const ALLOWED_IMAGE_MIMES = new Set([
  'image/jpeg',
  'image/jpg',
  'image/png',
  'image/webp',
  'image/gif',
]);

export interface BackupAttachmentDto {
  id?: string;
  type?: string;
  mimeType?: string;
  sizeBytes?: number;
  dataBase64: string;
  extension?: string;
}

export function extensionFromMime(mimeType: string): string {
  switch (mimeType.toLowerCase()) {
    case 'image/png':
      return 'png';
    case 'image/webp':
      return 'webp';
    case 'image/gif':
      return 'gif';
    default:
      return 'jpg';
  }
}

export function isAllowedBackupImageMime(mimeType: string): boolean {
  return ALLOWED_IMAGE_MIMES.has(mimeType.toLowerCase());
}

export function bytesToBase64(bytes: Uint8Array): string {
  let binary = '';
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

export function base64ToBytes(dataBase64: string): Uint8Array | null {
  try {
    const binary = atob(dataBase64.trim());
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i += 1) {
      bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
  } catch {
    return null;
  }
}

async function readAttachmentBlob(note: Note, attachment: Attachment): Promise<Blob | null> {
  if (isPendingAttachment(attachment.storagePath)) {
    const pendingId = attachment.storagePath.slice(ATTACHMENT_PENDING_PREFIX.length);
    return peekPendingAttachment(pendingId)?.blob ?? null;
  }
  if (isR2Attachment(attachment.storagePath)) {
    try {
      return await getAttachmentBlobStore().download(note.id, attachment.id);
    } catch {
      return null;
    }
  }
  return null;
}

export async function attachmentsToBackupDtos(note: Note): Promise<BackupAttachmentDto[]> {
  const exported: BackupAttachmentDto[] = [];
  for (const attachment of note.attachments) {
    if (exported.length >= MAX_NOTE_BACKUP_ATTACHMENTS) break;
    if (attachment.type && attachment.type !== 'image') continue;
    const mimeType = attachment.mimeType?.trim() || 'image/jpeg';
    if (!isAllowedBackupImageMime(mimeType)) continue;
    const blob = await readAttachmentBlob(note, attachment);
    if (!blob || blob.size === 0 || blob.size > MAX_ATTACHMENT_BYTES) continue;
    const bytes = new Uint8Array(await blob.arrayBuffer());
    exported.push({
      id: attachment.id,
      type: 'image',
      mimeType,
      sizeBytes: bytes.byteLength,
      dataBase64: bytesToBase64(bytes),
      extension: extensionFromMime(mimeType),
    });
  }
  return exported;
}

export function attachmentsFromBackupDtos(
  dtos: unknown,
  noteId: number,
  backupVersion: number,
): Attachment[] {
  if (backupVersion < MIN_BACKUP_VERSION_WITH_ATTACHMENTS || !Array.isArray(dtos)) {
    return [];
  }
  const restored: Attachment[] = [];
  for (const raw of dtos) {
    if (restored.length >= MAX_NOTE_BACKUP_ATTACHMENTS) break;
    if (!raw || typeof raw !== 'object') continue;
    const dto = raw as BackupAttachmentDto;
    if (typeof dto.dataBase64 !== 'string') continue;
    if (dto.type && dto.type !== 'image') continue;
    const mimeType = dto.mimeType?.trim() || 'image/jpeg';
    if (!isAllowedBackupImageMime(mimeType)) continue;
    const bytes = base64ToBytes(dto.dataBase64);
    if (!bytes || bytes.byteLength === 0 || bytes.byteLength > MAX_ATTACHMENT_BYTES) continue;
    const id = dto.id?.trim() || createAttachmentId();
    const copy = new Uint8Array(bytes.byteLength);
    copy.set(bytes);
    const blob = new Blob([copy], { type: mimeType });
    storePendingAttachment(id, blob, mimeType);
    restored.push({
      id,
      noteId,
      storagePath: pendingStoragePath(id),
      type: 'image',
      mimeType,
      sizeBytes: bytes.byteLength,
    });
  }
  return restored;
}
