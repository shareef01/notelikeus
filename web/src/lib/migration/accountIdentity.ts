const LINK_STORAGE_KEY = 'notelikeus-firebase-supabase-link';
const KNOWN_FIREBASE_UID_KEY = 'notelikeus-known-firebase-uid';

const SUPABASE_UUID_REGEX =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export interface FirebaseSupabaseLink {
  firebaseUid: string;
  supabaseUid: string;
  linkedAt: number;
}

export function isSupabaseUuid(value: string): boolean {
  return SUPABASE_UUID_REGEX.test(value);
}

export function isLikelyFirebaseUid(value: string): boolean {
  return value.length > 0 && !isSupabaseUuid(value) && value !== '__guest__';
}

export function accountsMatch(
  previousId: string | null,
  currentId: string,
  linkedFirebaseUid: string | null,
): boolean {
  if (previousId == null) return true;
  if (previousId === currentId) return true;
  if (linkedFirebaseUid == null) return false;
  return (
    (previousId === linkedFirebaseUid && isSupabaseUuid(currentId)) ||
    (currentId === linkedFirebaseUid && isSupabaseUuid(previousId))
  );
}

export function loadLocalFirebaseSupabaseLink(): FirebaseSupabaseLink | null {
  try {
    const raw = localStorage.getItem(LINK_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<FirebaseSupabaseLink>;
    if (
      typeof parsed.firebaseUid === 'string' &&
      typeof parsed.supabaseUid === 'string' &&
      parsed.firebaseUid.length > 0 &&
      parsed.supabaseUid.length > 0
    ) {
      return {
        firebaseUid: parsed.firebaseUid,
        supabaseUid: parsed.supabaseUid,
        linkedAt: typeof parsed.linkedAt === 'number' ? parsed.linkedAt : Date.now(),
      };
    }
  } catch {
    // ignore corrupt storage
  }
  return null;
}

export function saveLocalFirebaseSupabaseLink(link: FirebaseSupabaseLink): void {
  try {
    localStorage.setItem(LINK_STORAGE_KEY, JSON.stringify(link));
  } catch {
    // quota / private mode
  }
}

export function rememberKnownFirebaseUid(firebaseUid: string): void {
  if (!isLikelyFirebaseUid(firebaseUid)) return;
  try {
    localStorage.setItem(KNOWN_FIREBASE_UID_KEY, firebaseUid);
  } catch {
    // ignore
  }
}

export function loadKnownFirebaseUid(): string | null {
  try {
    const value = localStorage.getItem(KNOWN_FIREBASE_UID_KEY);
    return value && isLikelyFirebaseUid(value) ? value : null;
  } catch {
    return null;
  }
}

export const FIREBASE_SUPABASE_LINK_STORAGE_KEY = LINK_STORAGE_KEY;
export const KNOWN_FIREBASE_UID_STORAGE_KEY = KNOWN_FIREBASE_UID_KEY;
