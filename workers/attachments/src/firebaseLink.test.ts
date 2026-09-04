import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { WorkerEnv } from './auth';
import { handleAttachmentRequest } from './index';
import { resetFirebaseCertCacheForTests } from './firebaseIdToken';

const PROJECT = 'notelikeus';
const OWNER = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const UID = 'aliceFirebaseUid28charsabcd';
const PATH = '/v1/identity/firebase-link';

function b64url(raw: string | Uint8Array): string {
  const s = typeof raw === 'string' ? raw : Array.from(raw, (b) => String.fromCharCode(b)).join('');
  return btoa(s).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}

function derLength(n: number): number[] {
  if (n < 0x80) return [n];
  const out: number[] = [];
  let v = n;
  while (v > 0) {
    out.unshift(v & 0xff);
    v >>= 8;
  }
  return [0x80 | out.length, ...out];
}

function certificatePem(spki: Uint8Array): string {
  const body = [0xa0, 0x03, 0x02, 0x01, 0x02, ...spki];
  const tbs = [0x30, ...derLength(body.length), ...body];
  const sig = [0x03, 0x02, 0x00, 0x00];
  const cert = [0x30, ...derLength(tbs.length + sig.length), ...tbs, ...sig];
  const b64 = btoa(Array.from(cert, (b) => String.fromCharCode(b)).join(''));
  return `-----BEGIN CERTIFICATE-----\n${b64.replace(/(.{64})/g, '$1\n')}\n-----END CERTIFICATE-----\n`;
}

let signToken: (claims: Record<string, unknown>) => Promise<string>;
let certPem: string;
let rpcCalls: { body: unknown; apikey: string | null }[];
let rpcStatus: { ok: boolean; body: string };

async function setupKeys() {
  const pair = await crypto.subtle.generateKey(
    { name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' },
    true,
    ['sign', 'verify'],
  );
  certPem = certificatePem(new Uint8Array(await crypto.subtle.exportKey('spki', pair.publicKey)));
  signToken = async (claims) => {
    const h = b64url(JSON.stringify({ alg: 'RS256', kid: 'k1', typ: 'JWT' }));
    const p = b64url(JSON.stringify(claims));
    const sig = new Uint8Array(
      await crypto.subtle.sign('RSASSA-PKCS1-v1_5', pair.privateKey, new TextEncoder().encode(`${h}.${p}`)),
    );
    return `${h}.${p}.${b64url(sig)}`;
  };
}

function claims(overrides: Record<string, unknown> = {}) {
  const now = Math.floor(Date.now() / 1000);
  return {
    sub: UID,
    aud: PROJECT,
    iss: `https://securetoken.google.com/${PROJECT}`,
    iat: now - 30,
    auth_time: now - 30,
    exp: now + 3600,
    ...overrides,
  };
}

let env: WorkerEnv;

function installFetch(validSupabaseTokens: Record<string, string>) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      if (url.includes('/robot/v1/metadata/x509/')) {
        return new Response(JSON.stringify({ k1: certPem }), {
          headers: { 'cache-control': 'max-age=3600' },
        });
      }
      if (url.includes('/auth/v1/user')) {
        const token = ((init?.headers as Record<string, string>)?.Authorization ?? '').replace('Bearer ', '');
        const id = validSupabaseTokens[token];
        return id ? new Response(JSON.stringify({ id })) : new Response('no', { status: 401 });
      }
      if (url.includes('/rest/v1/rpc/link_verified_firebase_uid')) {
        const headers = init?.headers as Record<string, string>;
        rpcCalls.push({ body: JSON.parse(String(init?.body)), apikey: headers?.apikey ?? null });
        return new Response(rpcStatus.body, { status: rpcStatus.ok ? 200 : 400 });
      }
      return new Response('unexpected', { status: 500 });
    }),
  );
}

function post(body: unknown, token = OWNER) {
  return new Request(`https://worker.example${PATH}`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: typeof body === 'string' ? body : JSON.stringify(body),
  });
}

beforeEach(async () => {
  resetFirebaseCertCacheForTests();
  rpcCalls = [];
  rpcStatus = { ok: true, body: '{"verified":true}' };
  await setupKeys();
  installFetch({ [OWNER]: OWNER });
  env = {
    ATTACHMENTS_BUCKET: {} as R2Bucket,
    SUPABASE_URL: 'https://project.supabase.co',
    SUPABASE_ANON_KEY: 'anon-key',
    FIREBASE_PROJECT_ID: PROJECT,
    SUPABASE_SERVICE_ROLE_KEY: 'service-role-key',
  };
});

afterEach(() => vi.unstubAllGlobals());

describe('firebase link route', () => {
  it('links the uid the verified token names, for the authenticated Supabase user', async () => {
    const response = await handleAttachmentRequest(post({ firebaseIdToken: await signToken(claims()) }), env);

    expect(response.status).toBe(200);
    expect(await response.json()).toMatchObject({ verified: true, firebaseUid: UID });
    expect(rpcCalls).toHaveLength(1);
    expect(rpcCalls[0]?.body).toEqual({ p_firebase_uid: UID, p_owner_id: OWNER });
  });

  it('takes the uid from the token, never from the request body', async () => {
    await handleAttachmentRequest(
      post({ firebaseIdToken: await signToken(claims()), firebaseUid: 'someone-elses-uid', p_owner_id: 'x' }),
      env,
    );
    expect(rpcCalls[0]?.body).toEqual({ p_firebase_uid: UID, p_owner_id: OWNER });
  });

  it('requires a Supabase session', async () => {
    const anonymous = new Request(`https://worker.example${PATH}`, { method: 'POST', body: '{}' });
    expect((await handleAttachmentRequest(anonymous, env)).status).toBe(401);
    expect(rpcCalls).toHaveLength(0);
  });

  it('rejects a Supabase token Supabase does not recognise', async () => {
    const response = await handleAttachmentRequest(
      post({ firebaseIdToken: await signToken(claims()) }, 'not-a-session'),
      env,
    );
    expect(response.status).toBe(401);
    expect(rpcCalls).toHaveLength(0);
  });

  it('never calls the privileged RPC when the Firebase token does not verify', async () => {
    const forged = `${b64url(JSON.stringify({ alg: 'none', kid: 'k1' }))}.${b64url(JSON.stringify(claims()))}.`;
    const response = await handleAttachmentRequest(post({ firebaseIdToken: forged }), env);

    expect(response.status).toBe(401);
    expect(await response.json()).toMatchObject({ error: 'invalid_firebase_token' });
    expect(rpcCalls).toHaveLength(0);
  });

  it('never calls the privileged RPC for a token from another Firebase project', async () => {
    const other = await signToken(
      claims({ aud: 'attacker-project', iss: 'https://securetoken.google.com/attacker-project' }),
    );
    expect((await handleAttachmentRequest(post({ firebaseIdToken: other }), env)).status).toBe(401);
    expect(rpcCalls).toHaveLength(0);
  });

  it('reports a uid already proven by another account as a conflict', async () => {
    rpcStatus = { ok: false, body: 'firebase_uid already verified for another account' };
    const response = await handleAttachmentRequest(
      post({ firebaseIdToken: await signToken(claims()) }),
      env,
    );
    expect(response.status).toBe(409);
    expect(await response.json()).toMatchObject({ error: 'firebase_uid_owned_by_another_account' });
  });

  it('is disabled, not broken, when the service-role key is not configured', async () => {
    delete env.SUPABASE_SERVICE_ROLE_KEY;
    const response = await handleAttachmentRequest(
      post({ firebaseIdToken: await signToken(claims()) }),
      env,
    );
    expect(response.status).toBe(501);
    expect(await response.json()).toMatchObject({ verified: false });
    expect(rpcCalls).toHaveLength(0);
  });

  it('is disabled when no Firebase project is configured', async () => {
    delete env.FIREBASE_PROJECT_ID;
    expect((await handleAttachmentRequest(post({ firebaseIdToken: 'x' }), env)).status).toBe(501);
  });

  it('rejects a non-POST method', async () => {
    const get = new Request(`https://worker.example${PATH}`, {
      headers: { Authorization: `Bearer ${OWNER}` },
    });
    expect((await handleAttachmentRequest(get, env)).status).toBe(405);
  });

  it('rejects malformed and oversized bodies', async () => {
    expect((await handleAttachmentRequest(post('not json'), env)).status).toBe(400);
    expect((await handleAttachmentRequest(post({}), env)).status).toBe(400);
    expect((await handleAttachmentRequest(post({ firebaseIdToken: 42 }), env)).status).toBe(400);
    expect(
      (await handleAttachmentRequest(post({ firebaseIdToken: 'x'.repeat(9000) }), env)).status,
    ).toBe(413);
    expect(rpcCalls).toHaveLength(0);
  });

  it('uses the service-role key only for this RPC', async () => {
    await handleAttachmentRequest(post({ firebaseIdToken: await signToken(claims()) }), env);
    expect(rpcCalls[0]?.apikey).toBe('service-role-key');
  });
});
