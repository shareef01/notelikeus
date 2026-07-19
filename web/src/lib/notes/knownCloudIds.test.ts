import { beforeEach, describe, expect, it } from 'vitest';
import {
  clearKnownCloudIds,
  KNOWN_CLOUD_IDS_STORAGE_KEY,
  loadKnownCloudIds,
  saveKnownCloudIds,
} from '@/lib/notes/knownCloudIds';

describe('knownCloudIds persistence', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('round-trips ids through localStorage', () => {
    saveKnownCloudIds(new Set(['1', '2']));
    expect([...loadKnownCloudIds()].sort()).toEqual(['1', '2']);
    expect(localStorage.getItem(KNOWN_CLOUD_IDS_STORAGE_KEY)).toContain('1');
  });

  it('clearKnownCloudIds removes storage', () => {
    saveKnownCloudIds(new Set(['9']));
    clearKnownCloudIds();
    expect(loadKnownCloudIds().size).toBe(0);
    expect(localStorage.getItem(KNOWN_CLOUD_IDS_STORAGE_KEY)).toBeNull();
  });

  it('ignores corrupt storage', () => {
    localStorage.setItem(KNOWN_CLOUD_IDS_STORAGE_KEY, '{not-json');
    expect(loadKnownCloudIds().size).toBe(0);
  });
});
