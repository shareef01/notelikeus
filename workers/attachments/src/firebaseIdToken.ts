/**
 * Firebase ID token verification.
 *
 * This is the only thing that turns "a Supabase session says it owns Firebase uid F" into evidence.
 * `link_firebase_uid` cannot check it — nothing about an authenticated Supabase session says
 * anything about a legacy Firebase identity — so the whole ownership invariant rests here:
 *
 *     Supabase account A may claim Firebase uid F only when the app can prove the same user owns
 *     authenticated Firebase identity F.
 *
 * A Firebase ID token is an RS256 JWT signed by Google for one specific project. Verifying it means
 * checking the signature against Google's *published* keys and then checking every registered claim
 * — a token that is validly signed but issued for another project, or expired, or for a different
 * subject, proves nothing about this one.
 *
 * Deliberately hand-rolled on WebCrypto rather than pulled from npm: this runs in a Cloudflare
 * Worker, it is about 150 lines, and a dependency in the trust path here is worth more scrutiny
 * than it saves.
 */

/** Google's published x509 certificates for Firebase ID tokens, keyed by `kid`. */
const GOOGLE_CERT_URL =
  'https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com';

/** Tokens live an hour; tolerate a little clock drift in both directions, and no more. */
const CLOCK_SKEW_SECONDS = 60;

/** Fallback cache lifetime when Google's response carries no usable `max-age`. */
const DEFAULT_CERT_TTL_MS = 60 * 60 * 1000;

export interface VerifiedFirebaseIdentity {
  /** The Firebase Auth uid the token proves ownership of. */
  uid: string;
  email: string | null;
  expiresAt: number;
}

export class FirebaseIdTokenError extends Error {
  constructor(readonly reason: string) {
    super(`Firebase ID token rejected: ${reason}`);
    this.name = 'FirebaseIdTokenError';
  }
}

interface JwtHeader {
  alg?: unknown;
  kid?: unknown;
}

interface FirebaseClaims {
  sub?: unknown;
  aud?: unknown;
  iss?: unknown;
  exp?: unknown;
  iat?: unknown;
  auth_time?: unknown;
  email?: unknown;
}

function base64UrlToBytes(value: string): Uint8Array {
  const padded = value.replaceAll('-', '+').replaceAll('_', '/');
  const binary = atob(padded.padEnd(padded.length + ((4 - (padded.length % 4)) % 4), '='));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function decodeJsonSegment<T>(segment: string, what: string): T {
  try {
    return JSON.parse(new TextDecoder().decode(base64UrlToBytes(segment))) as T;
  } catch {
    throw new FirebaseIdTokenError(`${what} is not valid base64url JSON`);
  }
}

/** PEM certificate → the SPKI public key inside it, as a WebCrypto key. */
async function importCertificatePublicKey(pem: string): Promise<CryptoKey> {
  const body = pem
    .replace(/-----BEGIN [A-Z ]+-----/g, '')
    .replace(/-----END [A-Z ]+-----/g, '')
    .replace(/\s+/g, '');
  const der = base64UrlToBytes(body.replaceAll('+', '-').replaceAll('/', '_'));
  const spki = spkiFromCertificate(der);
  return crypto.subtle.importKey(
    'spki',
    // Copy into a fresh buffer: the slice may be a view over a larger allocation.
    spki.slice().buffer as ArrayBuffer,
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['verify'],
  );
}

/**
 * Extracts the SubjectPublicKeyInfo from a DER X.509 certificate.
 *
 * WebCrypto imports SPKI, not certificates, and Google publishes certificates. Rather than write a
 * full ASN.1 parser, this walks the DER far enough to find the one SEQUENCE that begins with the
 * RSA algorithm identifier — that is the SPKI, and it is the only place that OID appears in a
 * certificate of this shape.
 */
function spkiFromCertificate(der: Uint8Array): Uint8Array {
  // OID 1.2.840.113549.1.1.1 (rsaEncryption), DER-encoded, with its NULL parameters.
  const marker = [0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01];
  for (let i = 0; i + marker.length <= der.length; i += 1) {
    let hit = true;
    for (let j = 0; j < marker.length; j += 1) {
      if (der[i + j] !== marker[j]) {
        hit = false;
        break;
      }
    }
    if (!hit) continue;
    // The SPKI SEQUENCE starts just before the algorithm SEQUENCE. Its length is long-form
    // (a public key is always > 127 bytes), so read the length-of-length byte back.
    for (let start = i - 4; start >= 0 && start >= i - 8; start -= 1) {
      if (der[start] !== 0x30) continue;
      const lengthByte = der[start + 1] ?? 0;
      if ((lengthByte & 0x80) === 0) continue;
      const lengthBytes = lengthByte & 0x7f;
      let length = 0;
      for (let k = 0; k < lengthBytes; k += 1) length = (length << 8) | (der[start + 2 + k] ?? 0);
      const end = start + 2 + lengthBytes + length;
      if (end <= der.length && start + 2 + lengthBytes <= i) return der.subarray(start, end);
    }
  }
  throw new FirebaseIdTokenError('certificate does not contain an RSA public key');
}

interface CertCache {
  keys: Map<string, CryptoKey>;
  expiresAt: number;
}

let certCache: CertCache | null = null;

/** Test hook — drops the cached signing keys. */
export function resetFirebaseCertCacheForTests(): void {
  certCache = null;
}

function cacheTtlMs(response: Response): number {
  const maxAge = /max-age=(\d+)/.exec(response.headers.get('cache-control') ?? '')?.[1];
  const seconds = maxAge ? Number(maxAge) : Number.NaN;
  return Number.isFinite(seconds) && seconds > 0 ? seconds * 1000 : DEFAULT_CERT_TTL_MS;
}

async function googleSigningKeys(now: number): Promise<Map<string, CryptoKey>> {
  if (certCache && certCache.expiresAt > now) return certCache.keys;

  const response = await fetch(GOOGLE_CERT_URL);
  if (!response.ok) {
    throw new FirebaseIdTokenError('could not fetch Google signing certificates');
  }
  const ttl = cacheTtlMs(response);
  const certificates = (await response.json()) as Record<string, string>;
  const keys = new Map<string, CryptoKey>();
  for (const [kid, pem] of Object.entries(certificates)) {
    try {
      keys.set(kid, await importCertificatePublicKey(pem));
    } catch {
      // One unusable certificate must not take the whole key set down with it.
    }
  }
  if (keys.size === 0) {
    throw new FirebaseIdTokenError('Google returned no usable signing certificates');
  }
  certCache = { keys, expiresAt: now + ttl };
  return keys;
}

/**
 * Verifies [idToken] and returns the identity it proves, or throws {@link FirebaseIdTokenError}.
 *
 * [projectId] is the Firebase project the token must have been issued for. It is not a secret
 * (it ships in every web build) but it is load-bearing: without pinning it, a validly signed token
 * from *any* Firebase project on earth would verify, and anyone could mint one for a project they
 * control and claim whatever uid it names.
 */
export async function verifyFirebaseIdToken(
  idToken: string,
  projectId: string,
  now: number = Date.now(),
): Promise<VerifiedFirebaseIdentity> {
  if (!projectId.trim()) throw new FirebaseIdTokenError('no Firebase project id configured');

  const parts = idToken.trim().split('.');
  if (parts.length !== 3) throw new FirebaseIdTokenError('not a three-part JWT');
  const [headerB64, payloadB64, signatureB64] = parts as [string, string, string];

  const header = decodeJsonSegment<JwtHeader>(headerB64, 'header');
  // Pinning the algorithm is the whole defence against algorithm confusion: `none` would skip
  // verification, and `HS256` would have us "verify" an attacker-chosen MAC against a public key.
  if (header.alg !== 'RS256') throw new FirebaseIdTokenError('algorithm is not RS256');
  if (typeof header.kid !== 'string' || !header.kid) {
    throw new FirebaseIdTokenError('header has no key id');
  }

  const keys = await googleSigningKeys(now);
  const key = keys.get(header.kid);
  if (!key) throw new FirebaseIdTokenError('key id is not a current Google signing key');

  const signed = new TextEncoder().encode(`${headerB64}.${payloadB64}`);
  const signature = base64UrlToBytes(signatureB64);
  const valid = await crypto.subtle.verify(
    'RSASSA-PKCS1-v1_5',
    key,
    signature.slice().buffer as ArrayBuffer,
    signed.slice().buffer as ArrayBuffer,
  );
  if (!valid) throw new FirebaseIdTokenError('signature does not verify');

  const claims = decodeJsonSegment<FirebaseClaims>(payloadB64, 'payload');
  const nowSeconds = Math.floor(now / 1000);

  if (claims.aud !== projectId) throw new FirebaseIdTokenError('audience is not this project');
  if (claims.iss !== `https://securetoken.google.com/${projectId}`) {
    throw new FirebaseIdTokenError('issuer is not this project');
  }
  if (typeof claims.exp !== 'number' || claims.exp + CLOCK_SKEW_SECONDS <= nowSeconds) {
    throw new FirebaseIdTokenError('token has expired');
  }
  if (typeof claims.iat !== 'number' || claims.iat - CLOCK_SKEW_SECONDS > nowSeconds) {
    throw new FirebaseIdTokenError('token was issued in the future');
  }
  // Firebase always sets auth_time; a token without it is not one of ours.
  if (typeof claims.auth_time !== 'number' || claims.auth_time - CLOCK_SKEW_SECONDS > nowSeconds) {
    throw new FirebaseIdTokenError('auth_time is missing or in the future');
  }
  if (typeof claims.sub !== 'string' || claims.sub.trim() === '' || claims.sub.length > 128) {
    throw new FirebaseIdTokenError('subject is missing or malformed');
  }

  return {
    uid: claims.sub,
    email: typeof claims.email === 'string' ? claims.email : null,
    expiresAt: claims.exp * 1000,
  };
}
