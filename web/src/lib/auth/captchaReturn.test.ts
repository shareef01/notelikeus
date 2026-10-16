import { describe, expect, it } from 'vitest';
import { captchaReturnUrl, parseCaptchaReturn } from '@/lib/auth/captchaReturn';

const STATE = 'abcdefghijklmnop_-0123';

describe('parseCaptchaReturn', () => {
  it('accepts a desktop loopback request', () => {
    expect(parseCaptchaReturn(`?port=51234&state=${STATE}`)).toEqual({
      kind: 'loopback',
      port: 51234,
      state: STATE,
    });
  });

  it('accepts an Android app request', () => {
    expect(parseCaptchaReturn(`?return=app&state=${STATE}`)).toEqual({ kind: 'app', state: STATE });
  });

  it.each([
    ['no state', '?port=51234'],
    ['a short state', '?port=51234&state=abc'],
    ['a state with other characters', `?port=51234&state=${STATE}%2F..`],
    ['a privileged port', `?port=80&state=${STATE}`],
    ['a port below 1024', `?port=1023&state=${STATE}`],
    ['a port above 65535', `?port=65536&state=${STATE}`],
    ['a port that is not a number', `?port=8080x&state=${STATE}`],
    ['a leading-zero port', `?port=08080&state=${STATE}`],
    ['both return modes', `?port=51234&return=app&state=${STATE}`],
    ['another return mode', `?return=https://evil.example&state=${STATE}`],
    ['nothing', ''],
  ])('refuses %s', (_name, search) => {
    expect(parseCaptchaReturn(search)).toBeNull();
  });

  it('ignores a host, scheme or path in the query', () => {
    const target = parseCaptchaReturn(
      `?port=51234&state=${STATE}&host=evil.example&redirect=https://evil.example/`,
    );
    expect(captchaReturnUrl(target!, 't')).toBe(`http://127.0.0.1:51234/?state=${STATE}&token=t`);
  });
});

describe('captchaReturnUrl', () => {
  it('sends the token to loopback for desktop, escaped', () => {
    expect(captchaReturnUrl({ kind: 'loopback', port: 51234, state: STATE }, 'a.b&c=d')).toBe(
      `http://127.0.0.1:51234/?state=${STATE}&token=a.b%26c%3Dd`,
    );
  });

  it('sends the token to the app scheme for Android', () => {
    expect(captchaReturnUrl({ kind: 'app', state: STATE }, 'tok')).toBe(
      `notelikeus-captcha://token?state=${STATE}&token=tok`,
    );
  });
});
