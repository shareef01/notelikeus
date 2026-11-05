import { useAuthStore } from '@/store/authStore';
import { resolveOwnerId } from './ownerNamespace';

let incarnation = 0;
useAuthStore.subscribe((next, previous) => {
  if (next.user?.uid !== previous.user?.uid || next.guestMode !== previous.guestMode) incarnation++;
});

/** Retains the originating session even across A -> B -> A transitions. */
export function captureOwnerScope() {
  const ownerId = resolveOwnerId();
  const started = incarnation;
  const isActive = () => incarnation === started && resolveOwnerId() === ownerId;
  return {
    ownerId,
    isActive,
    assertActive() {
      if (!isActive()) throw new Error('Account changed; repeat the action for the intended account.');
    },
  };
}
