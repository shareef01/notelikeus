import type { WorkerEnv } from './auth';
import { FirebaseIdTokenError, verifyFirebaseIdToken } from './firebaseIdToken';

/** A Firebase ID token is ~1 KB; anything much larger is not one. */
const MAX_LINK_BODY_BYTES = 8 * 1024;

export const FIREBASE_LINK_PATH = '/v1/identity/firebase-link';

/**
 * Proves a Firebase uid belongs to the caller, then records the link as verified.
 *
 * The caller is already an authenticated Supabase user ([ownerId] comes from their access token,
 * checked by the attachment handler). What this adds is the other half of the identity: a Firebase
 * ID token signed by Google for the configured project, whose `sub` is the uid being claimed.
 * Together those two facts are the invariant `link_firebase_uid` cannot establish on its own —
 * one user, holding both identities at once.
 *
 * The uid is taken from the verified token's `sub`, never from the request body. A client cannot
 * name the uid it wants; it can only present a token, and the token decides.
 */
export async function handleFirebaseLinkRequest(
  request: Request,
  env: WorkerEnv,
  ownerId: string,
): Promise<Response> {
  if (request.method !== 'POST') {
    return new Response('Method Not Allowed', { status: 405 });
  }
  if (!env.FIREBASE_PROJECT_ID?.trim() || !env.SUPABASE_SERVICE_ROLE_KEY?.trim()) {
    // Staging without these configured still works — clients fall back to an unverified claim,
    // which is no longer exclusive and so cannot lock anyone out.
    return Response.json(
      { error: 'verified_link_unavailable', verified: false },
      { status: 501 },
    );
  }

  const raw = await request.text();
  if (raw.length > MAX_LINK_BODY_BYTES) {
    return new Response('Payload Too Large', { status: 413 });
  }

  let idToken: unknown;
  try {
    idToken = (JSON.parse(raw) as { firebaseIdToken?: unknown }).firebaseIdToken;
  } catch {
    return Response.json({ error: 'invalid_json' }, { status: 400 });
  }
  if (typeof idToken !== 'string' || !idToken.trim()) {
    return Response.json({ error: 'firebaseIdToken required' }, { status: 400 });
  }

  let uid: string;
  try {
    ({ uid } = await verifyFirebaseIdToken(idToken, env.FIREBASE_PROJECT_ID.trim()));
  } catch (error) {
    if (error instanceof FirebaseIdTokenError) {
      // The reason is safe to return: it describes the token the caller just sent, and never
      // contains the token, Google's keys, or anything about another account.
      return Response.json({ error: 'invalid_firebase_token', reason: error.reason }, { status: 401 });
    }
    return Response.json({ error: 'verification_failed' }, { status: 502 });
  }

  const response = await fetch(
    `${env.SUPABASE_URL.replace(/\/$/, '')}/rest/v1/rpc/link_verified_firebase_uid`,
    {
      method: 'POST',
      headers: {
        apikey: env.SUPABASE_SERVICE_ROLE_KEY,
        Authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ p_firebase_uid: uid, p_owner_id: ownerId }),
    },
  );

  if (!response.ok) {
    // 23505: another account has already *proven* this uid. That is a real conflict — two people
    // cannot both own one Firebase identity — and the client should stop rather than retry.
    const body = await response.text();
    const conflict = body.includes('already verified for another account');
    return Response.json(
      { error: conflict ? 'firebase_uid_owned_by_another_account' : 'link_failed' },
      { status: conflict ? 409 : 502 },
    );
  }

  return Response.json({ verified: true, firebaseUid: uid });
}
