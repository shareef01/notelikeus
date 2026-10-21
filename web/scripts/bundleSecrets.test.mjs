import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { findSecretsInDir, findSecretsInText } from './bundleSecrets.mjs';

const part = (value) => Buffer.from(JSON.stringify(value)).toString('base64url');

/** An unsigned JWT with the given claims; the scan never checks signatures. */
function jwt(claims) {
  return `${part({ alg: 'HS256', typ: 'JWT' })}.${part(claims)}.c2lnbmF0dXJl`;
}

// Joined at runtime so the repository holds no string that secret scanners read as a real key.
const SECRET_KEY = ['sb', 'secret', 'TESTONLYnotARealKey0123456789ab'].join('_');

test('finds a secret API key and a service_role JWT inlined in code', () => {
  assert.deepEqual(findSecretsInText(`const k="${SECRET_KEY}";`), ['Supabase secret API key (sb_secret_…)']);
  assert.deepEqual(findSecretsInText(`a("${jwt({ iss: 'supabase', role: 'service_role' })}")`), ['service_role JWT']);
  assert.deepEqual(
    findSecretsInText(`${SECRET_KEY} ${jwt({ role: 'service_role' })} ${jwt({ role: 'service_role' })}`),
    ['Supabase secret API key (sb_secret_…)', 'service_role JWT'],
  );
});

test('passes the public client keys and prose that names a secret key', () => {
  assert.deepEqual(findSecretsInText(`const k="${jwt({ iss: 'supabase', role: 'anon' })}";`), []);
  assert.deepEqual(findSecretsInText('const k="sb_publishable_abc123DEF456ghi789jkl0";'), []);
  // BootGate's help text.
  assert.deepEqual(findSecretsInText('never an sb_secret_… or service_role key'), []);
  assert.deepEqual(findSecretsInText('VITE_SUPABASE_ANON_KEY looks like sb_secret_x'), []);
  // A JWT-shaped string whose payload is not JSON.
  assert.deepEqual(findSecretsInText('eyJhbGciOiJIUzI1NiJ9.eyJpayload.signature'), []);
});

test('never returns the secret value itself', () => {
  const kinds = findSecretsInText(`${SECRET_KEY} ${jwt({ role: 'service_role' })}`).join(' ');
  assert.ok(!kinds.includes(SECRET_KEY));
  assert.ok(!kinds.includes('eyJ'));
});

test('scans nested text files and skips binaries', (t) => {
  const dir = mkdtempSync(join(tmpdir(), 'bundle-secrets-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  mkdirSync(join(dir, 'assets'));
  writeFileSync(join(dir, 'index.html'), '<!doctype html><title>ok</title>');
  writeFileSync(join(dir, 'assets', 'index-abc.js'), `const k="${SECRET_KEY}";`);
  writeFileSync(join(dir, 'assets', 'supabase-def.js.map'), `{"x":"${jwt({ role: 'service_role' })}"}`);
  writeFileSync(join(dir, 'assets', 'icon.png'), SECRET_KEY);

  assert.deepEqual(
    findSecretsInDir(dir).toSorted((a, b) => a.file.localeCompare(b.file)),
    [
      { file: 'assets/index-abc.js', kinds: ['Supabase secret API key (sb_secret_…)'] },
      { file: 'assets/supabase-def.js.map', kinds: ['service_role JWT'] },
    ],
  );
});

test('a clean directory has no findings', (t) => {
  const dir = mkdtempSync(join(tmpdir(), 'bundle-secrets-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  writeFileSync(join(dir, 'app.js'), `const k="${jwt({ role: 'anon' })}";`);
  assert.deepEqual(findSecretsInDir(dir), []);
});
