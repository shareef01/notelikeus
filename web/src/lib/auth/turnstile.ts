/**
 * Cloudflare Turnstile, the CAPTCHA in front of the sign-in code email (audit S-02, D30).
 *
 * The hosted Supabase project refuses `POST /auth/v1/otp` without a Turnstile token once
 * `security_captcha_enabled` is on. A build without `VITE_TURNSTILE_SITE_KEY` (local dev, unit
 * tests, e2e against the local stack, where `[auth.captcha]` is off) renders no widget and sends
 * no token.
 */

export const TURNSTILE_SCRIPT_URL =
  'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';

/** The subset of `window.turnstile` this app calls. */
export interface TurnstileApi {
  render(
    container: HTMLElement,
    options: {
      sitekey: string;
      action?: string;
      theme?: 'auto' | 'light' | 'dark';
      callback?: (token: string) => void;
      'expired-callback'?: () => void;
      'error-callback'?: () => void;
    },
  ): string | undefined;
  reset(widgetId?: string): void;
  remove(widgetId?: string): void;
}

declare global {
  interface Window {
    turnstile?: TurnstileApi;
  }
}

/** The public Turnstile site key of this build, or null when the build has no CAPTCHA. */
export function turnstileSiteKey(): string | null {
  const key = import.meta.env.VITE_TURNSTILE_SITE_KEY?.trim();
  return key ? key : null;
}

let loading: Promise<TurnstileApi> | null = null;

/** Loads Cloudflare's script once, explicit-render, and resolves with `window.turnstile`. */
export function loadTurnstile(): Promise<TurnstileApi> {
  if (window.turnstile) return Promise.resolve(window.turnstile);
  if (loading) return loading;
  loading = new Promise<TurnstileApi>((resolve, reject) => {
    const script = document.createElement('script');
    script.src = TURNSTILE_SCRIPT_URL;
    script.async = true;
    script.onload = () => {
      if (window.turnstile) resolve(window.turnstile);
      else reject(new Error('Turnstile did not load'));
    };
    script.onerror = () => reject(new Error('Turnstile did not load'));
    document.head.appendChild(script);
  }).catch((error: unknown) => {
    loading = null;
    throw error;
  });
  return loading;
}
