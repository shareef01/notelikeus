import { describe, expect, it } from 'vitest';
import { getEmptyState } from '@/screens/main/mainEmptyState';

describe('UX-D — Guest empty state truthfulness', () => {
  it('does NOT promise Android or cloud sync to guest users', () => {
    // Calling getEmptyState with signedIn = false
    const guestState = getEmptyState('active', false, false, false);
    expect(guestState.message).toBe('Notes you add appear here');
    // Must NOT promise automatic sync to guests
    expect(guestState.subtitle).not.toMatch(/Synced automatically/i);
    expect(guestState.subtitle).toMatch(/browser|local/i);
  });

  it('truthfully mentions device sync to signed-in users', () => {
    // Calling getEmptyState with signedIn = true
    const signedInState = getEmptyState('active', false, false, true);
    expect(signedInState.message).toBe('Notes you add appear here');
    // Android, Windows and the web all sync, so name none of them.
    expect(signedInState.subtitle).toBe('Synced automatically across your devices');
  });
});

describe('Archive and Trash empty states', () => {
  it('explains what the archive is for', () => {
    expect(getEmptyState('archived', false, false, false).subtitle).toBe(
      'Archived notes stay here, out of your main list',
    );
  });

  it('does not suggest that deleting a note is permanent', () => {
    // Deleting moves a note to Trash; only emptying the trash removes it for good.
    const trash = getEmptyState('trashed', false, false, false);
    expect(trash.subtitle).toBe('Deleted notes wait here until you empty the trash');
    expect(trash.subtitle).not.toMatch(/permanent/i);
  });
});
