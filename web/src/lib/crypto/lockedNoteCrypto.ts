import type { ChecklistItem } from '@/types/checklist';

const LOCK_KEY_STORAGE = 'notelikeus-lock-key';
const APP_KEY_INFO = 'notelikeus-locked-notes-v1';

export type LockedSecrets = {
  title: string;
  content: string;
  checklist: ChecklistItem[];
};

/** Persisted ciphertext for a locked note's sensitive fields. */
export type LockedBlob = {
  v: 1;
  iv: string;
  ct: string;
};

function bytesToBase64(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function base64ToBytes(value: string): Uint8Array<ArrayBuffer> {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

async function importRawKey(raw: Uint8Array<ArrayBuffer>): Promise<CryptoKey> {
  return crypto.subtle.importKey('raw', raw, { name: 'AES-GCM' }, false, [
    'encrypt',
    'decrypt',
  ]);
}

/** Device-local AES key for locked-note payloads. Cleared on sign-out with user data. */
export async function getOrCreateLockKey(): Promise<CryptoKey> {
  try {
    const existing = localStorage.getItem(LOCK_KEY_STORAGE);
    if (existing) {
      return importRawKey(base64ToBytes(existing));
    }
  } catch {
    // fall through and create a new key
  }

  const raw = crypto.getRandomValues(new Uint8Array(32));
  try {
    localStorage.setItem(LOCK_KEY_STORAGE, bytesToBase64(raw));
  } catch {
    // memory-only key for this session if storage is blocked
  }
  return importRawKey(raw);
}

export function clearLockKey(): void {
  try {
    localStorage.removeItem(LOCK_KEY_STORAGE);
  } catch {
    // ignore
  }
}

export async function encryptLockedSecrets(secrets: LockedSecrets): Promise<LockedBlob> {
  const key = await getOrCreateLockKey();
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const plaintext = new TextEncoder().encode(JSON.stringify(secrets));
  const ciphertext = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv, additionalData: new TextEncoder().encode(APP_KEY_INFO) },
    key,
    plaintext,
  );
  return {
    v: 1,
    iv: bytesToBase64(iv),
    ct: bytesToBase64(new Uint8Array(ciphertext)),
  };
}

export async function decryptLockedSecrets(blob: LockedBlob): Promise<LockedSecrets> {
  if (blob.v !== 1) {
    throw new Error('Unsupported locked note blob version');
  }
  const key = await getOrCreateLockKey();
  const iv = base64ToBytes(blob.iv);
  const ct = base64ToBytes(blob.ct);
  const plaintext = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv, additionalData: new TextEncoder().encode(APP_KEY_INFO) },
    key,
    ct,
  );
  const parsed = JSON.parse(new TextDecoder().decode(plaintext)) as LockedSecrets;
  return {
    title: typeof parsed.title === 'string' ? parsed.title : '',
    content: typeof parsed.content === 'string' ? parsed.content : '',
    checklist: Array.isArray(parsed.checklist) ? parsed.checklist : [],
  };
}

export function isLockedBlob(value: unknown): value is LockedBlob {
  if (!value || typeof value !== 'object') return false;
  const blob = value as Record<string, unknown>;
  return blob.v === 1 && typeof blob.iv === 'string' && typeof blob.ct === 'string';
}
