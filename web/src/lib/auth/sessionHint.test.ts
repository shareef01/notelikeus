import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  SESSION_HINT_STORAGE_KEY,
  forgetSignedIn,
  hadSessionLastLoad,
  rememberSignedIn,
} from '@/lib/auth/sessionHint';

beforeEach(() => {
  localStorage.clear();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('session hint', () => {
  it('starts out unset', () => {
    expect(hadSessionLastLoad()).toBe(false);
  });

  it('round-trips through localStorage', () => {
    rememberSignedIn();
    expect(localStorage.getItem(SESSION_HINT_STORAGE_KEY)).toBe('1');
    expect(hadSessionLastLoad()).toBe(true);

    forgetSignedIn();
    expect(localStorage.getItem(SESSION_HINT_STORAGE_KEY)).toBeNull();
    expect(hadSessionLastLoad()).toBe(false);
  });

  it('ignores an unrecognized stored value', () => {
    localStorage.setItem(SESSION_HINT_STORAGE_KEY, 'yes');
    expect(hadSessionLastLoad()).toBe(false);
  });

  it('falls back to the blocking gate when storage throws', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota');
    });
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied');
    });
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('denied');
    });

    expect(() => rememberSignedIn()).not.toThrow();
    expect(() => forgetSignedIn()).not.toThrow();
    expect(hadSessionLastLoad()).toBe(false);
  });
});
