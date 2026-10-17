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
    '/_headers',
  ])('leaves %s to the network', (path) => {
    expect(isDeniedNavigation(at(path))).toBe(true);
  });

  it.each(['/', '/?note=12', '/notes/12', '/captchas', '/captcha-help', '/settings?tab=account'])(
    'answers %s with the app shell',
    (path) => {
      expect(isDeniedNavigation(at(path))).toBe(false);
    },
  );
});
