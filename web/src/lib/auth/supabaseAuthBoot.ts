/**
 * Whether boot must load supabase-js immediately.
 *
 * Never-signed-in visitors (no session hint, no OAuth `?code=`) can render the gate and guest
 * shell without the 50 kB-gzip client. Returning users and the Google redirect still pay on boot
 * so session restore / PKCE exchange are not delayed.
 */
export function shouldStartSupabaseAuthOnBoot(hadSession: boolean, search: string): boolean {
  if (hadSession) return true;
  return new URLSearchParams(search).has('code');
}
