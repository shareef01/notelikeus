import { describe, expect, it } from 'vitest';
import { getEmptyState } from '@/screens/main/mainEmptyState';

describe('UX-D — Guest empty state truthfulness', () => {
  it('does NOT promise Android or cloud sync to guest users', () => {
    // Calling getEmptyState with signedIn = false
    const guestState = getEmptyState('active', false, false, false);
    expect(guestState.message).toBe('Notes you add appear here');
    // Must NOT promise automatic sync to guests
    expect(guestState.subtitle).not.toMatch(/Synced automatically with your Android device/i);
    expect(guestState.subtitle).toMatch(/browser|local/i);
  });

  it('truthfully mentions device sync to signed-in users', () => {
    // Calling getEmptyState with signedIn = true
    const signedInState = getEmptyState('active', false, false, true);
    expect(signedInState.message).toBe('Notes you add appear here');
    expect(signedInState.subtitle).toMatch(/Synced automatically with your Android device/i);
  });
});
