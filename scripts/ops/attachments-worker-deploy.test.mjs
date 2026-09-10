import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

/**
 * The deploy workflow generates `wrangler.toml` at run time — the file is gitignored because it
 * carries an account id — so nothing in the repository shows what the deployed Worker is actually
 * bound to. That is how ATTACHMENT_RATE_LIMITER came to exist in `src/rateLimit.ts`, be documented
 * in `wrangler.toml.example`, and be bound by no owner-operated deployment: the throttling code
 * shipped and then quietly did nothing, because `rateLimit.ts` is a no-op without the binding.
 *
 * These tests run the workflow's own generator and assert on what it writes.
 */

const WORKFLOW = fileURLToPath(
  new URL('../../.github/workflows/attachments-worker.yml', import.meta.url),
);

const workflow = readFileSync(WORKFLOW, 'utf8');

/** Pulls the inline `node --input-type=module -e '...'` generator out of the workflow. */
function generatorSource() {
  const match = workflow.match(/node --input-type=module -e '([\s\S]*?)'\n/);
  assert.ok(match, 'the deploy workflow must still generate wrangler.toml from an inline script');
  return match[1].replace(
    'import { writeFileSync } from "node:fs";',
    'const writeFileSync = (path, contents) => { globalThis.__written = contents; };',
  );
}

function generateWranglerToml(env) {
  const previous = { ...process.env };
  Object.assign(process.env, {
    CLOUDFLARE_ACCOUNT_ID: 'account-id',
    R2_BUCKET: 'notelikeus-attachments-dev',
    SUPABASE_URL: 'https://project.supabase.co',
    ALLOWED_ORIGINS: '',
    RATE_LIMIT_REQUESTS: '120',
    RATE_LIMIT_PERIOD: '60',
    ...env,
  });
  try {
    globalThis.__written = undefined;
    // eslint-disable-next-line no-eval
    (0, eval)(generatorSource());
    return String(globalThis.__written);
  } finally {
    process.env = previous;
  }
}

test('binds ATTACHMENT_RATE_LIMITER with the operator-chosen values', () => {
  const toml = generateWranglerToml({ RATE_LIMIT_REQUESTS: '90', RATE_LIMIT_PERIOD: '10' });

  assert.match(toml, /\[\[unsafe\.bindings\]\]/);
  assert.match(toml, /name = "ATTACHMENT_RATE_LIMITER"/);
  assert.match(toml, /type = "ratelimit"/);
  // Numbers, not strings: a quoted limit is a wrangler deploy failure.
  assert.match(toml, /simple = \{ limit = 90, period = 10 \}/);
});

test('defaults to 120 requests per 60 seconds', () => {
  assert.match(generateWranglerToml({}), /simple = \{ limit = 120, period = 60 \}/);
});

test('omits the binding entirely when throttling is set to 0', () => {
  const toml = generateWranglerToml({ RATE_LIMIT_REQUESTS: '0' });

  assert.doesNotMatch(toml, /ATTACHMENT_RATE_LIMITER/);
  assert.doesNotMatch(toml, /unsafe\.bindings/);
  // Everything else the Worker needs must still be there.
  assert.match(toml, /binding = "ATTACHMENTS_BUCKET"/);
  assert.match(toml, /\[triggers\]/);
});

test('keeps the binding above [triggers] so the cron stays its own table', () => {
  const toml = generateWranglerToml({});

  assert.ok(
    toml.indexOf('[[unsafe.bindings]]') < toml.indexOf('[triggers]'),
    'crons must not end up inside the rate-limit table',
  );
});

test('writes no secret into wrangler.toml', () => {
  const toml = generateWranglerToml({});

  // Only the project URL and the CORS list are vars; the anon key and the service-role key are
  // Worker secrets, because a var is echoed into the deploy log.
  assert.doesNotMatch(toml, /ANON_KEY/);
  assert.doesNotMatch(toml, /SERVICE_ROLE/);
});

test('rejects a rate-limit period Cloudflare does not accept', () => {
  const guard = workflow.match(/rate_limit_period must be 10 or 60[^\n]*/);
  assert.ok(guard, 'the dispatch guard must still reject an unsupported window');
  assert.match(workflow, /\$RATE_LIMIT_PERIOD" != "10" \] && \[ "\$RATE_LIMIT_PERIOD" != "60"/);
});

test('uploads the service-role key only when the repository has one', () => {
  // The `secrets:` list is uploaded unconditionally, so naming an unset secret there would store
  // an empty value — and an empty service-role key is not the same as no key.
  assert.match(workflow, /id: sweep/);
  assert.match(workflow, /if: steps\.sweep\.outputs\.configured == 'true'/);
  assert.match(workflow, /the cron stays a deliberate no-op/);

  const echoedValue = /echo[^\n]*\$SUPABASE_SERVICE_ROLE_KEY(?!")/.test(workflow);
  assert.equal(echoedValue, false, 'the service-role key value must never be echoed');
});
