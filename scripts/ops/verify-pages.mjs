#!/usr/bin/env node
/**
 * Builds the web bundle with placeholder Supabase credentials and verifies the Pages artifacts.
 *
 * This exists because the npm script it replaces used a `VAR=value command` prefix, which is
 * POSIX shell syntax. npm runs scripts through `cmd.exe` on Windows, where that is not a variable
 * assignment but an unrecognised command — so `npm run pages:verify` failed on the one platform
 * the desktop app is built for, while CI (ubuntu) stayed green and hid it.
 *
 * Setting the variables here rather than reaching for a dependency keeps the check runnable from
 * a fresh clone with nothing installed but the project's own packages.
 *
 * The placeholders are deliberate: the verification is about the artifacts' shape — `_headers`,
 * `_redirects`, the CSP the build pins — not about reaching a real project. A build with real
 * credentials is what the deploy job does, separately.
 */
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

const PLACEHOLDER_ENV = {
  VITE_SUPABASE_URL: 'https://placeholder.supabase.co',
  // Structurally a JWT so the client's own env validation accepts it; signed by nobody.
  VITE_SUPABASE_ANON_KEY: 'eyJhbGciOiJIUzI1NiJ9.payload.signature',
};

/**
 * Runs one step, inheriting stdio so its output is the output of this script.
 *
 * The command is passed as a single string with `shell: true` rather than as an argv array.
 * On Windows `npm` is a batch file, which Node refuses to spawn without a shell; and passing an
 * argv array *with* a shell is what Node deprecates, because it concatenates the arguments into a
 * command line without escaping them. Every string below is a literal in this file — there is no
 * caller-supplied input to escape — so the single-string form is both correct and warning-free.
 */
function run(commandLine, env = {}) {
  const result = spawnSync(commandLine, {
    cwd: repoRoot,
    stdio: 'inherit',
    env: { ...process.env, ...env },
    shell: true,
  });
  if (result.error) {
    console.error(`Failed to run: ${commandLine}`);
    console.error(result.error.message);
    process.exit(1);
  }
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}

run('npm --prefix web run build', PLACEHOLDER_ENV);
run('node web/scripts/verifyPagesArtifacts.mjs');
run('node cloudflare/scripts/verify-headers-parity.mjs');
