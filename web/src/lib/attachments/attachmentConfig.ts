import { isSupabaseBackendEnabled } from '@/lib/supabase/client';

export function loadAttachmentsWorkerUrl(): string {
  return import.meta.env.VITE_ATTACHMENTS_WORKER_URL?.trim() || '';
}

/**
 * Dev-only. Requires Supabase backend + attachments worker URL.
 * Phase 9 will wire this into note editor UI.
 */
export function isR2AttachmentsEnabled(): boolean {
  if (import.meta.env.PROD && !import.meta.env.VITE_E2E) return false;
  return isSupabaseBackendEnabled() && loadAttachmentsWorkerUrl().length > 0;
}
