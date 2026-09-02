import { isSupabaseBackendEnabled } from '@/lib/supabase/client';

export function loadAttachmentsWorkerUrl(): string {
  return import.meta.env.VITE_ATTACHMENTS_WORKER_URL?.trim() || '';
}

/** Requires the active remote backend to be Supabase and a worker URL. */
export function isR2AttachmentsEnabled(): boolean {
  return isSupabaseBackendEnabled() && loadAttachmentsWorkerUrl().length > 0;
}
