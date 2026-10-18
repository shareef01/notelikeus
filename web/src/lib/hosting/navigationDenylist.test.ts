import { describe, expect, it } from 'vitest';
import { isDeniedNavigation } from './navigationDenylist';

const at = (path: string) => new URL(path, 'https://notelikeus.pages.dev');

describe('isDeniedNavigation', () => {
  it.each([
    '/captcha',
    '/captcha/',
    '/captcha?port=50123&state=abcdefghijklmnop1234',
    '/captcha?return=app&state=abcdefghijklmnop1234',
    '/captcha.html?port=50123&state=abcdefghijklmnop1234',
    '/privacy.html',
    '/privacy.html?from=settings',
    '/assets/app.3f2a.js?v=1',
    '/_headers',
  ])('leaves %s to the network', (path) => {
    expect(isDeniedNavigation(at(path))).toBe(true);
  });

  it.each([
    '/',
    '/?note=12',
    '/notes/12',
    '/captchas',
    '/captcha-help',
    '/settings?tab=account',
    '/?u=https://x.example/a.b',
    '/notes/12?file=report.pdf',
    '/share?u=https%3A%2F%2Fx.example%2Fa.b',
  ])(
    'answers %s with the app shell',
    (path) => {
      expect(isDeniedNavigation(at(path))).toBe(false);
    },
  );
});
