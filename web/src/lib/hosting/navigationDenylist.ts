/**
 * Navigations the service worker must leave to the network instead of answering with the app shell.
 *
 * Workbox's NavigationRoute tests these against `pathname + search`, not the pathname alone, so a
 * pattern anchored with `$` right after the path never matches a URL that carries a query, and a
 * pattern must stop at the first `?` so a dot in the query does not count.
 *
 * - `/_…`: Pages' own routes.
 * - a last path segment with a dot: real files (`/privacy.html`, `/captcha.html?port=…`), but not
 *   `/?u=https://x.example/a.b`.
 * - `/captcha`: the native apps' Turnstile page (D30). Pages redirects `captcha.html` there, keeping
 *   the `?port=…&state=…` or `?return=app&state=…` query. Answering it with the app shell, in a
 *   browser that has the PWA installed, showed the sign-in screen instead of the check, so desktop
 *   could not get a token.
 */
export const NAVIGATION_DENYLIST: RegExp[] = [
  /^\/_/,
  /^[^?]*\/[^/?]+\.[^/?]+(?:\?|$)/,
  /^\/captcha\/?(?:\?|$)/,
];

/** Whether the service worker leaves this navigation alone, matched the way Workbox matches it. */
export function isDeniedNavigation(url: URL): boolean {
  const pathnameAndSearch = url.pathname + url.search;
  return NAVIGATION_DENYLIST.some((pattern) => pattern.test(pathnameAndSearch));
}
