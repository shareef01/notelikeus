import { ATTACHMENT_R2_PREFIX } from '@/lib/attachments/attachmentPaths';
import { labelFromName } from '@/types/label';
import type { Note } from '@/types/note';

export interface SupabaseNotePayload {
  type?: string;
  note_id: string;
  local_id: number;
  revision?: number;
  title?: string;
  content?: string;
  client_timestamp?: number;
  color?: number;
  is_pinned?: boolean;
  is_archived?: boolean;
  is_trashed?: boolean;
  position?: number;
  reminder_timestamp?: number | null;
  labels?: Array<{ name?: string }>;
  checklist?: Array<{ text?: string; isChecked?: boolean; position?: number }>;
  server_updated_at?: number;
}

export interface SupabaseTombstonePayload {
  type?: string;
  note_id: string;
  revision?: number;
  deleted_at?: number;
}

function asString(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback;
}

function asInteger(value: unknown, fallback: number): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) return fallback;
  return Math.trunc(value);
}

function asBoolean(value: unknown, fallback = false): boolean {
  return typeof value === 'boolean' ? value : fallback;
}

export function supabaseNoteToNote(payload: SupabaseNotePayload): Note {
  const labels = Array.isArray(payload.labels)
    ? payload.labels
        .map((entry) => asString(entry?.name).trim())
        .filter((name) => name.length > 0)
        .map((name) => labelFromName(name))
    : [];

  const checklist = Array.isArray(payload.checklist)
    ? payload.checklist.map((item, index) => ({
        id: `chk-${payload.note_id}-${index}`,
        text: asString(item?.text),
        isChecked: asBoolean(item?.isChecked),
        position: asInteger(item?.position, index),
      }))
    : [];

  return {
    id: payload.note_id,
    localId: asInteger(payload.local_id, Number(payload.note_id)),
    title: asString(payload.title),
    content: asString(payload.content),
    timestamp: asInteger(payload.client_timestamp, Date.now()),
    color: asInteger(payload.color, 0xff1a1a1a | 0),
    isPinned: asBoolean(payload.is_pinned),
    isArchived: asBoolean(payload.is_archived),
    isTrashed: asBoolean(payload.is_trashed),
    position: asInteger(payload.position, 0),
    reminderTimestamp:
      payload.reminder_timestamp == null
        ? null
        : asInteger(payload.reminder_timestamp, 0),
    serverUpdatedAt:
      payload.server_updated_at == null
        ? null
        : asInteger(payload.server_updated_at, 0),
    labels,
    attachments: [],
    checklist,
  };
}

/**
 * The `apply_note_change` arguments for [note]. With [attachmentIds] (the default) they include
 * `p_attachment_ids`, the 15-argument overload: exactly the uploaded (`r2:`) attachments this version
 * references, which the commit promotes from provisional in the same transaction. A `pending:` image
 * has no server row yet. `restore_note` has no such parameter, and PostgREST matches overloads by
 * argument names, so its caller passes `attachmentIds: false`.
 */
export function noteToSupabaseRpcArgs(
  note: Note,
  baseRevision: number | null,
  { attachmentIds = true }: { attachmentIds?: boolean } = {},
) {
  const args = {
    p_note_id: note.id,
    p_local_id: note.localId,
    p_base_revision: baseRevision,
    p_title: note.title,
    p_content: note.content,
    p_client_timestamp: note.timestamp,
    p_color: note.color,
    p_is_pinned: note.isPinned,
    p_is_archived: note.isArchived,
    p_is_trashed: note.isTrashed,
    p_position: note.position,
    p_reminder_timestamp: note.reminderTimestamp,
    p_labels: note.labels.map((label) => ({ name: label.name })),
    p_checklist: note.checklist.map((item) => ({
      text: item.text,
      isChecked: item.isChecked,
      position: item.position,
    })),
  };
  if (!attachmentIds) return args;
  return {
    ...args,
    p_attachment_ids: note.attachments
      .filter((attachment) => attachment.storagePath.startsWith(ATTACHMENT_R2_PREFIX))
      .map((attachment) => attachment.id),
  };
}

export function parseTombstoneMap(
  tombstones: SupabaseTombstonePayload[],
): Record<string, number> {
  const map: Record<string, number> = {};
  for (const row of tombstones) {
    if (!row.note_id) continue;
    const deletedAt = asInteger(row.deleted_at, Date.now());
    map[row.note_id] = deletedAt;
  }
  return map;
}
