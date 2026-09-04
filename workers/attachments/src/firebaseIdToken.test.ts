import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  FirebaseIdTokenError,
  resetFirebaseCertCacheForTests,
  verifyFirebaseIdToken,
} from './firebaseIdToken';

const PROJECT = 'notelikeus';
const NOW = 1_800_000_000_000;
const NOW_S = Math.floor(NOW / 1000);
const UID = 'aliceFirebaseUid28charsabcd';

function b64url(bytes: Uint8Array | string): string {
  const raw =
    typeof bytes === 'string'
      ? bytes
      : Array.from(bytes, (b) => String.fromCharCode(b)).join('');
  return btoa(raw).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}

/** DER-encodes a length in the form the certificate wrapper below needs. */
function derLength(n: number): number[] {
  if (n < 0x80) return [n];
  const bytes: number[] = [];
  let v = n;
  while (v > 0) {
    bytes.unshift(v & 0xff);
    v >>= 8;
  }
  return [0x80 | bytes.length, ...bytes];
}

/**
 * Wraps a real SPKI in enough DER to look like the certificate Google publishes, so the parser
 * under test is exercised on the shape it will actually meet rather than on a bare key.
 */
function certificatePemFromSpki(spki: Uint8Array): string {
  const tbsPrefix = [0xa0, 0x03, 0x02, 0x01, 0x02]; // [0] EXPLICIT version v3
  const body = [...tbsPrefix, ...spki];
  const tbs = [0x30, ...derLength(body.length), ...body];
  const sig = [0x03, 0x02, 0x00, 0x00];
  const cert = [0x30, ...derLength(tbs.length + sig.length), ...tbs, ...sig];
  const b64 = btoa(Array.from(cert, (b) => String.fromCharCode(b)).join(''));
  return `-----BEGIN CERTIFICATE-----\n${b64.replace(/(.{64})/g, '$1\n')}\n-----END CERTIFICATE-----\n`;
}

interface Signer {
  kid: string;
  sign(payload: Record<string, unknown>, header?: Record<string, unknown>): Promise<string>;
}

async function makeSigner(kid: string): Promise<{ signer: Signer; pem: string }> {
  const pair = await crypto.subtle.generateKey(
    { name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' },
    true,
    ['sign', 'verify'],
  );
  const spki = new Uint8Array(await crypto.subtle.exportKey('spki', pair.publicKey));
  const signer: Signer = {
    kid,
    async sign(payload, header = {}) {
      const h = b64url(JSON.stringify({ alg: 'RS256', kid, typ: 'JWT', ...header }));
      const p = b64url(JSON.stringify(payload));
      const sig = new Uint8Array(
        await crypto.subtle.sign(
          'RSASSA-PKCS1-v1_5',
          pair.privateKey,
          new TextEncoder().encode(`${h}.${p}`),
        ),
      );
      return `${h}.${p}.${b64url(sig)}`;
    },
  };
  return { signer, pem: certificatePemFromSpki(spki) };
}

function validClaims(overrides: Record<string, unknown> = {}) {
  return {
    sub: UID,
    aud: PROJECT,
    iss: `https://securetoken.google.com/${PROJECT}`,
    iat: NOW_S - 60,
    auth_time: NOW_S - 60,
    exp: NOW_S + 3600,
    email: 'alice@example.com',
    ...overrides,
  };
}

let google: Signer;
let attacker: Signer;
let fetchMock: ReturnType<typeof vi.fn>;

function serveCerts(certs: Record<string, string>, cacheControl = 'public, max-age=3600') {
  fetchMock = vi.fn(async () =>
    new Response(JSON.stringify(certs), {
      status: 200,
      headers: { 'cache-control': cacheControl },
    }),
  );
  vi.stubGlobal('fetch', fetchMock);
}

beforeEach(async () => {
  resetFirebaseCertCacheForTests();
  const g = await makeSigner('google-kid-1');
  const a = await makeSigner('google-kid-1'); // same kid, different key — an impostor
  google = g.signer;
  attacker = a.signer;
  serveCerts({ 'google-kid-1': g.pem });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('verifyFirebaseIdToken — accepts a genuine token', () => {
  it('returns the uid a correctly signed token proves', async () => {
    const token = await google.sign(validClaims());
    const identity = await verifyFirebaseIdToken(token, PROJECT, NOW);
    expect(identity.uid).toBe(UID);
    expect(identity.email).toBe('alice@example.com');
  });

  it('caches Google signing keys instead of refetching per token', async () => {
    await verifyFirebaseIdToken(await google.sign(validClaims()), PROJECT, NOW);
    await verifyFirebaseIdToken(await google.sign(validClaims()), PROJECT, NOW);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('refetches once the cache lifetime from Google has elapsed', async () => {
    await verifyFirebaseIdToken(await google.sign(validClaims()), PROJECT, NOW);
    await verifyFirebaseIdToken(
      await google.sign(validClaims({ exp: NOW_S + 7200 })),
      PROJECT,
      NOW + 3_601_000,
    );
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});

describe('verifyFirebaseIdToken — signature attacks', () => {
  it('rejects a token signed by a key Google does not publish', async () => {
    const forged = await attacker.sign(validClaims());
    await expect(verifyFirebaseIdToken(forged, PROJECT, NOW)).rejects.toThrow(/signature/);
  });

  it('rejects alg: none', async () => {
    const claims = b64url(JSON.stringify(validClaims()));
    const header = b64url(JSON.stringify({ alg: 'none', kid: 'google-kid-1', typ: 'JWT' }));
    await expect(verifyFirebaseIdToken(`${header}.${claims}.`, PROJECT, NOW)).rejects.toThrow(
      /not RS256/,
    );
  });

  it('rejects an HS256 token, so a public key cannot be used as a MAC secret', async () => {
    const header = b64url(JSON.stringify({ alg: 'HS256', kid: 'google-kid-1', typ: 'JWT' }));
    const payload = b64url(JSON.stringify(validClaims()));
    await expect(
      verifyFirebaseIdToken(`${header}.${payload}.${b64url('mac')}`, PROJECT, NOW),
    ).rejects.toThrow(/not RS256/);
  });

  it('rejects a payload swapped after signing', async () => {
    const token = await google.sign(validClaims());
    const [h, , s] = token.split('.');
    const tampered = `${h}.${b64url(JSON.stringify(validClaims({ sub: 'someone-elses-uid' })))}.${s}`;
    await expect(verifyFirebaseIdToken(tampered, PROJECT, NOW)).rejects.toThrow(/signature/);
  });

  it('rejects an unknown key id', async () => {
    const other = await makeSigner('rotated-away-kid');
    await expect(
      verifyFirebaseIdToken(await other.signer.sign(validClaims()), PROJECT, NOW),
    ).rejects.toThrow(/key id/);
  });

  it('rejects a header with no key id', async () => {
    const header = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
    const payload = b64url(JSON.stringify(validClaims()));
    await expect(
      verifyFirebaseIdToken(`${header}.${payload}.${b64url('x')}`, PROJECT, NOW),
    ).rejects.toThrow(/key id/);
  });
});

describe('verifyFirebaseIdToken — claim attacks', () => {
  it('rejects a validly signed token issued for another Firebase project', async () => {
    // Without the audience pin, anyone could mint this from a project they control.
    const token = await google.sign(
      validClaims({ aud: 'attacker-project', iss: 'https://securetoken.google.com/attacker-project' }),
    );
    await expect(verifyFirebaseIdToken(token, PROJECT, NOW)).rejects.toThrow(/audience/);
  });

  it('rejects a mismatched issuer even when the audience is right', async () => {
    const token = await google.sign(validClaims({ iss: 'https://securetoken.google.com/other' }));
    await expect(verifyFirebaseIdToken(token, PROJECT, NOW)).rejects.toThrow(/issuer/);
  });

  it('rejects an expired token', async () => {
    const token = await google.sign(validClaims({ exp: NOW_S - 120 }));
    await expect(verifyFirebaseIdToken(token, PROJECT, NOW)).rejects.toThrow(/expired/);
  });

  it('accepts a token that expired within the clock-skew allowance', async () => {
    const token = await google.sign(validClaims({ exp: NOW_S - 10 }));
    await expect(verifyFirebaseIdToken(token, PROJECT, NOW)).resolves.toMatchObject({ uid: UID });
  });

  it('rejects a token issued in the future', async () => {
    const token = await google.sign(validClaims({ iat: NOW_S + 600 }));
    await expect(verifyFirebaseIdToken(token, PROJECT, NOW)).rejects.toThrow(/future/);
  });

  it('rejects a token with no auth_time', async () => {
    const claims = validClaims();
    delete (claims as Record<string, unknown>).auth_time;
    await expect(verifyFirebaseIdToken(await google.sign(claims), PROJECT, NOW)).rejects.toThrow(
      /auth_time/,
    );
  });

  it('rejects an empty, missing or oversized subject', async () => {
    for (const sub of ['', '   ', 'x'.repeat(129)]) {
      await expect(
        verifyFirebaseIdToken(await google.sign(validClaims({ sub })), PROJECT, NOW),
      ).rejects.toThrow(/subject/);
    }
    const claims = validClaims();
    delete (claims as Record<string, unknown>).sub;
    await expect(verifyFirebaseIdToken(await google.sign(claims), PROJECT, NOW)).rejects.toThrow(
      /subject/,
    );
  });
});

describe('verifyFirebaseIdToken — malformed input and outages', () => {
  it('rejects tokens that are not three-part JWTs', async () => {
    for (const bad of ['', 'a', 'a.b', 'a.b.c.d', '....']) {
      await expect(verifyFirebaseIdToken(bad, PROJECT, NOW)).rejects.toBeInstanceOf(
        FirebaseIdTokenError,
      );
    }
  });

  it('rejects segments that are not base64url JSON', async () => {
    await expect(verifyFirebaseIdToken('!!!.???.###', PROJECT, NOW)).rejects.toThrow(/header/);
  });

  it('refuses to verify anything when no project id is configured', async () => {
    await expect(verifyFirebaseIdToken(await google.sign(validClaims()), '  ', NOW)).rejects.toThrow(
      /project id/,
    );
  });

  it('fails closed when Google cannot be reached', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('nope', { status: 503 })));
    await expect(
      verifyFirebaseIdToken(await google.sign(validClaims()), PROJECT, NOW),
    ).rejects.toThrow(/certificates/);
  });

  it('fails closed when Google returns nothing usable', async () => {
    serveCerts({ 'google-kid-1': 'not a certificate' });
    await expect(
      verifyFirebaseIdToken(await google.sign(validClaims()), PROJECT, NOW),
    ).rejects.toThrow(/no usable signing certificates/);
  });
});
