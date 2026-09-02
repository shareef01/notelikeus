import { GUEST_OWNER_ID } from '@/lib/local/constants';
import { useAuthStore } from '@/store/authStore';

/** Resolves the IndexedDB owner namespace for the current session. */
export function resolveOwnerId(): string | null {
  const { user, guestMode } = useAuthStore.getState();
  if (user?.uid) return user.uid;
  if (guestMode) return GUEST_OWNER_ID;
  return null;
}

export function isGuestOwner(ownerId: string): boolean {
  return ownerId === GUEST_OWNER_ID;
}
