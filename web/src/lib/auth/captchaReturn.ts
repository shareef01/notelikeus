/**
 * Where the native challenge page (`/captcha.html`, D30) sends a Turnstile token.
 *
 * Android and desktop have no Turnstile SDK, so they open this page and receive the token back:
 * - desktop opens it in the system browser with `?port=<n>&state=<s>` and listens on
 *   `http://127.0.0.1:<n>/`;
 * - Android opens it in a WebView with `?return=app&state=<s>` and catches the
 *   `notelikeus-captcha:` navigation.
 *
 * The destination is built here from a fixed host and scheme. Nothing taken from the query names a
 * host, so the page cannot be used to send a token, or a person, anywhere else.
 */

export const APP_RETURN_SCHEME = 'notelikeus-captcha';

/** Random, URL-safe, and long enough not to be guessed by another page on the machine. */
const STATE_PATTERN = /^[A-Za-z0-9_-]{16,128}$/;
const PORT_PATTERN = /^[1-9][0-9]{3,4}$/;

export type CaptchaReturn =
  | { kind: 'loopback'; port: number; state: string }
  | { kind: 'app'; state: string };

/** Parses the page's query string; null when it is not a request this page will answer. */
export function parseCaptchaReturn(search: string): CaptchaReturn | null {
  const params = new URLSearchParams(search);
  const state = params.get('state') ?? '';
  if (!STATE_PATTERN.test(state)) return null;

  const port = params.get('port');
  const ret = params.get('return');
  if (port !== null && ret === null) {
    if (!PORT_PATTERN.test(port)) return null;
    const value = Number(port);
    if (value < 1024 || value > 65535) return null;
    return { kind: 'loopback', port: value, state };
  }
  if (ret === 'app' && port === null) return { kind: 'app', state };
  return null;
}

/** The URL that hands [token] back to the app that opened the page. */
export function captchaReturnUrl(target: CaptchaReturn, token: string): string {
  const query = `state=${encodeURIComponent(target.state)}&token=${encodeURIComponent(token)}`;
  return target.kind === 'loopback'
    ? `http://127.0.0.1:${target.port}/?${query}`
    : `${APP_RETURN_SCHEME}://token?${query}`;
}
