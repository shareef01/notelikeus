const RP_NAME = 'Notelikeus';
const CREDENTIAL_STORAGE_KEY = 'notelikeus-device-auth-credential-id';

/**
 * Device-local presence check for revealing a hidden note — the web equivalent
 * of Android's BiometricPrompt gate on the same feature. Uses WebAuthn against
 * the platform authenticator (Touch ID / Windows Hello / Android biometrics via
 * the browser) with no server involved: the OS-level prompt is the actual
 * security boundary, so there's nothing to verify server-side for this purpose.
 * The credential never leaves this device or browser profile.
 */
export async function isDeviceAuthAvailable(): Promise<boolean> {
  if (typeof window === 'undefined' || !window.PublicKeyCredential) return false;
  try {
    return await PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable();
  } catch {
    return false;
  }
}

function getStoredCredentialId(): string | null {
  try {
    return localStorage.getItem(CREDENTIAL_STORAGE_KEY);
  } catch {
    return null;
  }
}

function storeCredentialId(id: string): void {
  try {
    localStorage.setItem(CREDENTIAL_STORAGE_KEY, id);
  } catch {
    // ignore — worst case, the next reveal re-registers a credential
  }
}

function randomChallenge(): Uint8Array<ArrayBuffer> {
  return crypto.getRandomValues(new Uint8Array(32));
}

function bufferToBase64url(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function base64urlToBuffer(value: string): ArrayBuffer {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/');
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=');
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

function describeWebAuthnError(error: unknown): string {
  if (error instanceof DOMException) {
    switch (error.name) {
      case 'NotAllowedError':
        return 'Device verification was cancelled or timed out.';
      case 'InvalidStateError':
        return 'This device is already set up — try again.';
      case 'NotSupportedError':
      case 'SecurityError':
        return 'Device verification isn’t available in this browser.';
      default:
        return 'Device verification failed. Please try again.';
    }
  }
  return 'Device verification failed. Please try again.';
}

async function registerDeviceCredential(userId: string, userLabel: string): Promise<void> {
  let credential: Credential | null;
  try {
    credential = await navigator.credentials.create({
      publicKey: {
        rp: { name: RP_NAME },
        user: {
          id: new TextEncoder().encode(userId),
          name: userLabel,
          displayName: userLabel,
        },
        challenge: randomChallenge(),
        pubKeyCredParams: [
          { type: 'public-key', alg: -7 }, // ES256
          { type: 'public-key', alg: -257 }, // RS256
        ],
        authenticatorSelection: {
          authenticatorAttachment: 'platform',
          userVerification: 'required',
        },
        timeout: 60_000,
      },
    });
  } catch (error) {
    throw new Error(describeWebAuthnError(error));
  }

  const publicKeyCredential = credential as PublicKeyCredential | null;
  if (!publicKeyCredential) {
    throw new Error('Device verification setup was cancelled.');
  }
  storeCredentialId(bufferToBase64url(publicKeyCredential.rawId));
}

async function verifyDeviceCredential(credentialId: string): Promise<void> {
  let assertion: Credential | null;
  try {
    assertion = await navigator.credentials.get({
      publicKey: {
        challenge: randomChallenge(),
        allowCredentials: [{ id: base64urlToBuffer(credentialId), type: 'public-key' }],
        userVerification: 'required',
        timeout: 60_000,
      },
    });
  } catch (error) {
    throw new Error(describeWebAuthnError(error));
  }

  if (!assertion) {
    throw new Error('Device verification was cancelled.');
  }
}

/**
 * Registers a device credential on first use, or verifies against the existing
 * one. Resolves only once the OS-level biometric/PIN check has actually
 * succeeded; throws (with a user-facing message) otherwise.
 */
export async function requireDeviceAuth(userId: string, userLabel: string): Promise<void> {
  const existingId = getStoredCredentialId();
  if (!existingId) {
    await registerDeviceCredential(userId, userLabel);
    return;
  }
  await verifyDeviceCredential(existingId);
}
