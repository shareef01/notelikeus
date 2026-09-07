import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { WorkerEnv } from './auth';
import { sweepOrphanedDeletedAttachments } from './sweep';

const OWNER = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const OBJECT_KEY = `owners/${OWNER}/notes/1/att1`;

function fakeBucket() {
  const objects = new Map<string, Uint8Array>();
  return {
    objects,
    async put(key: string, body: Uint8Array) {
      objects.set(key, body);
    },
    failNextDelete: false,
    async delete(key: string) {
      if (this.failNextDelete) throw new Error('r2 unavailable');
      objects.delete(key);
    },
  };
}

let bucket: ReturnType<typeof fakeBucket>;
let env: WorkerEnv;
let rpcCalls: Array<{ url: string; body: unknown }>;

function mockRpcs(handlers: {
  list?: unknown;
  listStatus?: number;
  /** Claim response. Defaults to granting the claim for the canonical key of the listed row. */
  claim?: unknown;
  claimStatus?: number;
  purge?: unknown;
  purgeStatus?: number;
}) {
  rpcCalls = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string | URL, init?: RequestInit) => {
      const url = String(input);
      const body = JSON.parse(String(init?.body ?? '{}'));
      rpcCalls.push({ url, body });
      if (url.includes('list_orphaned_deleted_attachments')) {
        return new Response(JSON.stringify(handlers.list ?? []), {
          status: handlers.listStatus ?? 200,
        });
      }
      if (url.includes('claim_orphaned_attachment_for_delete')) {
        const claim =
          handlers.claim ??
          {
            claimed: true,
            object_key: `owners/${body.p_owner_id}/notes/${body.p_note_id}/${body.p_attachment_id}`,
          };
        return new Response(JSON.stringify(claim), { status: handlers.claimStatus ?? 200 });
      }
      if (url.includes('purge_orphaned_deleted_attachment')) {
        return new Response(JSON.stringify(handlers.purge ?? { status: 'applied' }), {
          status: handlers.purgeStatus ?? 200,
        });
      }
      return new Response('not found', { status: 404 });
    }),
  );
}

beforeEach(() => {
  bucket = fakeBucket();
  env = {
    ATTACHMENTS_BUCKET: bucket as unknown as R2Bucket,
    SUPABASE_URL: 'https://project.supabase.co',
    SUPABASE_ANON_KEY: 'anon-key',
    SUPABASE_SERVICE_ROLE_KEY: 'service-role-key',
  };
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('orphaned attachment sweep', () => {
  it('does nothing without a service-role key', async () => {
    delete env.SUPABASE_SERVICE_ROLE_KEY;
    mockRpcs({ list: [{ owner_id: OWNER, note_id: '1', attachment_id: 'att1', object_key: OBJECT_KEY }] });
    await bucket.put(OBJECT_KEY, new Uint8Array([1]));

    expect(await sweepOrphanedDeletedAttachments(env)).toEqual({
      scanned: 0,
      deleted: 0,
      skipped: 0,
    });
    expect(bucket.objects.has(OBJECT_KEY)).toBe(true);
    expect(rpcCalls).toEqual([]);
  });

  it('throws when the list RPC fails with configured service role', async () => {
    mockRpcs({ listStatus: 500 });
    await bucket.put(OBJECT_KEY, new Uint8Array([1]));

    await expect(sweepOrphanedDeletedAttachments(env)).rejects.toThrow(
      /Sweep RPC failed: list_orphaned_deleted_attachments \(500\)/,
    );
    expect(bucket.objects.has(OBJECT_KEY)).toBe(true);
  });

  it('throws on upstream RPC network failure or malformed JSON', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        throw new Error('network down');
      }),
    );
    await expect(sweepOrphanedDeletedAttachments(env)).rejects.toThrow(
      /Sweep RPC network failure: list_orphaned_deleted_attachments/,
    );

    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('not valid json', { status: 200 })),
    );
    await expect(sweepOrphanedDeletedAttachments(env)).rejects.toThrow(
      /Sweep RPC malformed JSON: list_orphaned_deleted_attachments/,
    );
  });

  it('deletes a canonical orphan and purges metadata', async () => {
    mockRpcs({
      list: [{ owner_id: OWNER, note_id: '1', attachment_id: 'att1', object_key: OBJECT_KEY }],
      purge: { status: 'applied' },
    });
    await bucket.put(OBJECT_KEY, new Uint8Array([1]));

    expect(await sweepOrphanedDeletedAttachments(env)).toEqual({
      scanned: 1,
      deleted: 1,
      skipped: 0,
    });
    expect(bucket.objects.has(OBJECT_KEY)).toBe(false);
    expect(rpcCalls[1]?.url).toContain('claim_orphaned_attachment_for_delete');
    expect(rpcCalls[2]?.url).toContain('purge_orphaned_deleted_attachment');
    expect(rpcCalls[1]?.body).toEqual({
      p_owner_id: OWNER,
      p_note_id: '1',
      p_attachment_id: 'att1',
    });
  });

  it('skips a row whose object key is not the canonical owner path', async () => {
    mockRpcs({
      list: [{
        owner_id: OWNER,
        note_id: '1',
        attachment_id: 'att1',
        object_key: 'owners/other/notes/1/att1',
      }],
    });
    await bucket.put(OBJECT_KEY, new Uint8Array([1]));

    expect(await sweepOrphanedDeletedAttachments(env)).toEqual({
      scanned: 1,
      deleted: 0,
      skipped: 1,
    });
    expect(bucket.objects.has(OBJECT_KEY)).toBe(true);
    expect(rpcCalls.some((call) => call.url.includes('purge_'))).toBe(false);
  });

  it('does not purge metadata when R2 delete fails', async () => {
    mockRpcs({
      list: [{ owner_id: OWNER, note_id: '1', attachment_id: 'att1', object_key: OBJECT_KEY }],
    });
    bucket.failNextDelete = true;

    const result = await sweepOrphanedDeletedAttachments(env);
    expect(result.deleted).toBe(0);
    expect(result.skipped).toBe(1);
    expect(rpcCalls.some((call) => call.url.includes('purge_'))).toBe(false);
  });

  it('leaves the blob orphaned when purge says the note is live', async () => {
    mockRpcs({
      list: [{ owner_id: OWNER, note_id: '1', attachment_id: 'att1', object_key: OBJECT_KEY }],
      purge: { status: 'skipped', reason: 'note_live' },
    });
    await bucket.put(OBJECT_KEY, new Uint8Array([1]));

    expect(await sweepOrphanedDeletedAttachments(env)).toEqual({
      scanned: 1,
      deleted: 0,
      skipped: 1,
    });
    expect(bucket.objects.has(OBJECT_KEY)).toBe(false);
  });
  it('does not touch the object when a restore beat the claim', async () => {
    // The race this protocol exists for. The listing is a snapshot; by the time the sweeper acts
    // the note can be live again. Deleting on the strength of the snapshot left the restored
    // note with metadata pointing at bytes that were already gone.
    mockRpcs({
      list: [{ owner_id: OWNER, note_id: '1', attachment_id: 'att1', object_key: OBJECT_KEY }],
      claim: { claimed: false, reason: 'restored' },
    });
    await bucket.put(OBJECT_KEY, new Uint8Array([1]));

    expect(await sweepOrphanedDeletedAttachments(env)).toEqual({
      scanned: 1,
      deleted: 0,
      skipped: 1,
    });
    expect(bucket.objects.has(OBJECT_KEY)).toBe(true);
    expect(rpcCalls.some((call) => call.url.includes('purge_'))).toBe(false);
  });

  it('does not touch the object when the note came back live', async () => {
    mockRpcs({
      list: [{ owner_id: OWNER, note_id: '1', attachment_id: 'att1', object_key: OBJECT_KEY }],
      claim: { claimed: false, reason: 'note_live' },
    });
    await bucket.put(OBJECT_KEY, new Uint8Array([1]));

    expect((await sweepOrphanedDeletedAttachments(env)).deleted).toBe(0);
    expect(bucket.objects.has(OBJECT_KEY)).toBe(true);
  });

  it('claims before deleting, never after', async () => {
    mockRpcs({
      list: [{ owner_id: OWNER, note_id: '1', attachment_id: 'att1', object_key: OBJECT_KEY }],
    });
    let keyExistedAtClaim: boolean | null = null;
    const realDelete = bucket.delete.bind(bucket);
    bucket.delete = async (key: string) => {
      keyExistedAtClaim = rpcCalls.some((call) =>
        call.url.includes('claim_orphaned_attachment_for_delete'),
      );
      return realDelete(key);
    };
    await bucket.put(OBJECT_KEY, new Uint8Array([1]));

    await sweepOrphanedDeletedAttachments(env);

    expect(keyExistedAtClaim).toBe(true);
  });

  it('refuses a claim whose returned key is not the canonical owner path', async () => {
    // A compromised or buggy database must not be able to point the sweeper at another owner.
    mockRpcs({
      list: [{ owner_id: OWNER, note_id: '1', attachment_id: 'att1', object_key: OBJECT_KEY }],
      claim: { claimed: true, object_key: 'owners/someone-else/notes/1/att1' },
    });
    await bucket.put(OBJECT_KEY, new Uint8Array([1]));

    expect((await sweepOrphanedDeletedAttachments(env)).skipped).toBe(1);
    expect(bucket.objects.has(OBJECT_KEY)).toBe(true);
  });

  it('keeps the claim so a failed R2 delete is retried, not lost', async () => {
    mockRpcs({
      list: [{ owner_id: OWNER, note_id: '1', attachment_id: 'att1', object_key: OBJECT_KEY }],
    });
    bucket.delete = async () => {
      throw new Error('R2 unavailable');
    };

    expect((await sweepOrphanedDeletedAttachments(env)).skipped).toBe(1);
    // Purging here would drop the only record of an object that is still stored.
    expect(rpcCalls.some((call) => call.url.includes('purge_'))).toBe(false);
  });
});
