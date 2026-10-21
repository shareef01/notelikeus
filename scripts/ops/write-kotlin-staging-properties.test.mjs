import assert from 'node:assert/strict';
import test from 'node:test';
import {
  FORBIDDEN_PROPERTY_KEYS,
  clientKeyProblem,
  parseDotEnv,
  stagingPropertiesFromEnv,
  mergeLocalProperties,
  writeKotlinStagingProperties,
} from './write-kotlin-staging-properties.mjs';

const SAMPLE_ENV = `
# Staging — copy to web/.env
VITE_SUPABASE_URL=https://example.supabase.co
VITE_SUPABASE_ANON_KEY=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.example
VITE_ATTACHMENTS_WORKER_URL=https://notelikeus-attachments.example.workers.dev
`;

test('parses quoted env and ignores comments', () => {
  const env = parseDotEnv(`
# comment
export VITE_SUPABASE_URL="https://quoted.supabase.co"
VITE_SUPABASE_ANON_KEY='eyJexample'
`);
  assert.equal(env.VITE_SUPABASE_URL, 'https://quoted.supabase.co');
  assert.equal(env.VITE_SUPABASE_ANON_KEY, 'eyJexample');
});

test('maps staging env and ignores production allow flags', () => {
  const updates = stagingPropertiesFromEnv(parseDotEnv(SAMPLE_ENV));
  assert.equal(updates['notelikeus.supabaseUrl'], 'https://example.supabase.co');
  assert.equal(
    updates['notelikeus.attachmentsWorkerUrl'],
    'https://notelikeus-attachments.example.workers.dev',
  );
  for (const key of FORBIDDEN_PROPERTY_KEYS) {
    assert.equal(updates[key], undefined);
  }
});

test('rejects secret API keys and missing fields', () => {
  assert.throws(
    () =>
      stagingPropertiesFromEnv({
        VITE_SUPABASE_URL: 'https://example.supabase.co',
        VITE_SUPABASE_ANON_KEY: 'sb_secret_not_for_clients',
        VITE_ATTACHMENTS_WORKER_URL: 'https://worker.example',
      }),
    /sb_/,
  );
  assert.throws(
    () =>
      stagingPropertiesFromEnv({
        VITE_SUPABASE_URL: 'https://example.supabase.co',
        VITE_SUPABASE_ANON_KEY: 'eyJok',
      }),
    /ATTACHMENTS_WORKER_URL/,
  );
});

test('accepts a publishable key and the legacy anon JWT', () => {
  for (const key of ['sb_publishable_abc123', 'eyJhbGciOiJIUzI1NiJ9.payload.signature']) {
    assert.equal(clientKeyProblem(key), null);
    const updates = stagingPropertiesFromEnv({
      VITE_SUPABASE_URL: 'https://example.supabase.co',
      VITE_SUPABASE_ANON_KEY: key,
      VITE_ATTACHMENTS_WORKER_URL: 'https://worker.example',
    });
    assert.equal(updates['notelikeus.supabaseAnonKey'], key);
  }
  assert.match(clientKeyProblem('sb_secret_x'), /secret API key/);
  assert.match(clientKeyProblem('sb_publishable_'), /secret API key/);
  assert.match(clientKeyProblem('plain'), /neither/);
});

test('refuses a legacy service_role JWT and passes the anon JWT', () => {
  const part = (value) => Buffer.from(JSON.stringify(value)).toString('base64url');
  const jwt = (role) => `${part({ alg: 'HS256', typ: 'JWT' })}.${part({ iss: 'supabase', role })}.c2ln`;
  assert.match(clientKeyProblem(jwt('service_role')), /service_role JWT/);
  assert.equal(clientKeyProblem(jwt('anon')), null);
  assert.throws(
    () =>
      stagingPropertiesFromEnv({
        VITE_SUPABASE_URL: 'https://example.supabase.co',
        VITE_SUPABASE_ANON_KEY: jwt('service_role'),
        VITE_ATTACHMENTS_WORKER_URL: 'https://worker.example',
      }),
    /service_role JWT/,
  );
});

test('merges without dropping oauth secret or adding allow-production', () => {
  const existing = [
    'sdk.dir=/opt/android-sdk',
    'notelikeus.oauthClientSecret=keep-me',
    'notelikeus.supabaseUrl=https://keep.example',
    '',
  ].join('\n');
  const next = writeKotlinStagingProperties({
    envText: SAMPLE_ENV,
    existingPropertiesText: existing,
  });
  assert.match(next, /sdk\.dir=\/opt\/android-sdk/);
  assert.match(next, /notelikeus\.oauthClientSecret=keep-me/);
  assert.match(next, /notelikeus\.supabaseUrl=https:\/\/example\.supabase\.co/);
  assert.doesNotMatch(next, /allowSupabaseProduction/);
  assert.doesNotMatch(next, /ALLOW_SUPABASE_PRODUCTION/);
});

test('mergeLocalProperties updates in place and appends missing keys', () => {
  const merged = mergeLocalProperties('alpha=1\n', { alpha: '2', beta: '3' });
  assert.equal(merged, 'alpha=2\nbeta=3\n');
});
