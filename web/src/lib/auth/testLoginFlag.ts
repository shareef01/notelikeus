/**
 * Email/password sign-in is a development affordance, not a product feature — the app signs in
 * with Google. It is also enabled for the end-to-end build, which is a production build in every
 * other respect and needs a way to reach a signed-in state against local Supabase.
 *
 * `VITE_E2E` is set only by web/.env.e2e, which only `--mode e2e` loads, so a normal `vite build`
 * cannot turn this on.
 *
 * Kept out of `emailAuth.ts` so the auth gate can render without pulling supabase-js.
 */
export const testLoginBuildEnabled =
  import.meta.env.DEV === true || Boolean(import.meta.env.VITE_E2E);

export function isTestLoginEnabled(): boolean {
  return testLoginBuildEnabled;
}
