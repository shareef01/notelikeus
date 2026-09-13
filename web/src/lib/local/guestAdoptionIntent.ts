/**
 * One-shot adoption intent signal.
 *
 * Invariant: __guest__ is an anonymous storage partition, not an identity.
 * Residual guest notes in IndexedDB must NEVER be adopted by an arbitrary authenticated
 * user who signs in directly on a shared browser.
 *
 * Adoption requires an active intent signal originating from the CURRENT session while
 * it was actively in Guest Mode immediately prior to authentication.
 *
 * Invariant: Stale, failed, or cancelled authentication attempts must NEVER leave a persistent
 * adoption signal that can be consumed by a future unrelated authentication.
 * We store a structured, time-bounded record with nonce and flow metadata, and fail closed
 * if expired (> 10 minutes) or malformed.
 */

export type AdoptionFlow = 'oauth' | 'password' | 'signup' | 'auth-transition';

export interface GuestAdoptionIntent {
  nonce: string;
  startedAt: number;
  flow: AdoptionFlow;
}

const ADOPTION_INTENT_STORAGE_KEY = 'notelikeus-pending-guest-adoption';
/**
 * Maximum lifetime for an adoption intent (10 minutes).
 * Sufficient for a user to complete an external OAuth consent screen,
 * while preventing abandoned sessions from lingering indefinitely.
 */
export const MAX_INTENT_AGE_MS = 10 * 60 * 1000;

let inMemoryIntent: GuestAdoptionIntent | null = null;

function parseAndValidateIntent(raw: string | null): GuestAdoptionIntent | null {
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as GuestAdoptionIntent;
    if (
      typeof parsed === 'object' &&
      parsed !== null &&
      typeof parsed.nonce === 'string' &&
      parsed.nonce.length > 0 &&
      typeof parsed.startedAt === 'number' &&
      Number.isFinite(parsed.startedAt) &&
      Date.now() - parsed.startedAt >= 0 &&
      Date.now() - parsed.startedAt <= MAX_INTENT_AGE_MS
    ) {
      return parsed;
    }
  } catch {
    // Malformed JSON or legacy boolean string -> fail closed
  }
  return null;
}

export function markGuestAdoptionIntent(flow: AdoptionFlow = 'auth-transition'): void {
  const nonce =
    typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random()}`;

  const intent: GuestAdoptionIntent = {
    nonce,
    startedAt: Date.now(),
    flow,
  };

  inMemoryIntent = intent;
  try {
    if (typeof sessionStorage !== 'undefined') {
      sessionStorage.setItem(ADOPTION_INTENT_STORAGE_KEY, JSON.stringify(intent));
    }
  } catch {
    // sessionStorage might be restricted (e.g. private browsing quota)
  }
}

export function clearGuestAdoptionIntent(): void {
  inMemoryIntent = null;
  try {
    if (typeof sessionStorage !== 'undefined') {
      sessionStorage.removeItem(ADOPTION_INTENT_STORAGE_KEY);
    }
  } catch {
    // ignore
  }
}

/**
 * Checks whether an adoption intent is currently valid without consuming it.
 */
export function hasGuestAdoptionIntent(): boolean {
  if (inMemoryIntent && Date.now() - inMemoryIntent.startedAt <= MAX_INTENT_AGE_MS) {
    return true;
  }
  try {
    if (typeof sessionStorage !== 'undefined') {
      const stored = parseAndValidateIntent(sessionStorage.getItem(ADOPTION_INTENT_STORAGE_KEY));
      if (stored) {
        return true;
      }
    }
  } catch {
    // ignore
  }
  return false;
}

/**
 * Checks whether an adoption intent was set and atomically clears it (one-shot).
 */
export function consumeGuestAdoptionIntent(): boolean {
  let valid = false;
  if (inMemoryIntent && Date.now() - inMemoryIntent.startedAt <= MAX_INTENT_AGE_MS) {
    valid = true;
  }
  try {
    if (typeof sessionStorage !== 'undefined') {
      const stored = parseAndValidateIntent(sessionStorage.getItem(ADOPTION_INTENT_STORAGE_KEY));
      if (stored) {
        valid = true;
      }
    }
  } catch {
    // ignore
  }

  clearGuestAdoptionIntent();
  return valid;
}
