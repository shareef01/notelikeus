#!/usr/bin/env node
/**
 * Fails when a Kotlin declaration is added that nothing calls.
 *
 * This exists because that exact shape has shipped twice. `adoptGuestStagedAttachments` was written
 * to move a signed-out session's staged images into the account at sign-in, was documented as
 * necessary, and was called from nowhere — so every picture attached before signing in became a
 * permanent broken image. `clearStagingCache` was the same story, one file away.
 *
 * detekt does not cover this. Its unused-declaration rules are scoped to *private* members, and
 * both of those are public methods on a public class, so a linter would have reported a clean
 * codebase either time. Whole-project reachability is the property that matters here, and it needs
 * a whole-project scan.
 *
 * The check is a baseline, not a gate on the current state: `unreferenced-kotlin.baseline.json`
 * records what is already known to be unreferenced, and only a *new* entry fails. Deleting dead
 * code shrinks the baseline and the script says so, so the list cannot quietly rot upward.
 *
 * Known limits, so nobody reads a pass as proof of reachability:
 *
 *  - It matches on names, not symbols. A declaration whose name also appears as a parameter, a
 *    local, or a string elsewhere reads as referenced. Verified against this repository's own
 *    history: it flags a method whose only occurrence is its own declaration, which is the shape
 *    both real bugs took.
 *  - A reference from a test counts as a reference, so production-dead-but-tested code passes.
 *    That is the weaker case — it fails loudly in review rather than silently in the field.
 *
 *   node scripts/ops/unreferenced-kotlin.mjs            check against the baseline
 *   node scripts/ops/unreferenced-kotlin.mjs --write    record the current state
 */

import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(HERE, '../..');
const BASELINE = join(HERE, 'unreferenced-kotlin.baseline.json');

/**
 * Names that are reached by something other than a Kotlin call site, so absence of a reference
 * says nothing. Keep this short and justified — it is the one place the check can be silenced.
 */
const REACHED_ELSEWHERE = [
  /^[A-Z]/, // @Preview composables and similar, invoked by tooling rather than by code
];

/** Declarations whose name alone makes them entry points rather than dead ends. */
const NEVER_FLAG = new Set(['main', 'toString', 'equals', 'hashCode', 'invoke']);

function trackedFiles() {
  return execFileSync('git', ['ls-files'], { cwd: REPO_ROOT, encoding: 'utf8' })
    .split('\n')
    .filter(Boolean);
}

function read(relativePath) {
  try {
    return readFileSync(join(REPO_ROOT, relativePath), 'utf8');
  } catch {
    return '';
  }
}

const DECLARATION = /^[ \t]*(?:(?:public|internal|private|protected)[ \t]+)?(?:suspend[ \t]+)?fun[ \t]+([A-Za-z_][A-Za-z0-9_]*)[ \t]*[(<]/gm;

export function findUnreferenced(files, readFile = read) {
  const kotlin = files.filter((f) => f.endsWith('.kt') || f.endsWith('.kts'));
  // Reference corpus is every Kotlin file in the repository. Scoping it to one source tree is how
  // a first pass at this produced false positives: callers live in androidApp/ and in build scripts.
  const corpus = kotlin.map(readFile).join('\n');

  const isMainSource = (f) => !/(^|\/)(src\/[a-zA-Z]*[Tt]est)\//.test(f) && !f.endsWith('Test.kt');
  const declarations = new Map();

  for (const file of kotlin.filter(isMainSource)) {
    const text = readFile(file);
    for (const match of text.matchAll(DECLARATION)) {
      const name = match[1];
      if (NEVER_FLAG.has(name)) continue;
      if (/^\s*override\b/.test(match[0])) continue;
      if (REACHED_ELSEWHERE.some((pattern) => pattern.test(name))) continue;
      if (!declarations.has(name)) declarations.set(name, []);
      declarations.get(name).push(file);
    }
  }

  const unreferenced = [];
  for (const [name, sites] of declarations) {
    const uses = corpus.match(new RegExp(`\\b${name}\\b`, 'g'))?.length ?? 0;
    // Every declaration counts as one reference to itself.
    if (uses <= sites.length) unreferenced.push({ name, file: sites[0] });
  }
  return unreferenced.sort((a, b) => a.name.localeCompare(b.name));
}

function main() {
  const found = findUnreferenced(trackedFiles());
  const names = found.map((entry) => entry.name);

  if (process.argv.includes('--write')) {
    writeFileSync(BASELINE, `${JSON.stringify({ unreferenced: names }, null, 2)}\n`);
    console.log(`Recorded ${names.length} unreferenced declaration(s) in the baseline.`);
    return;
  }

  const baseline = existsSync(BASELINE)
    ? new Set(JSON.parse(readFileSync(BASELINE, 'utf8')).unreferenced)
    : new Set();

  const added = found.filter((entry) => !baseline.has(entry.name));
  const removed = [...baseline].filter((name) => !names.includes(name));

  if (removed.length > 0) {
    console.log(`${removed.length} baseline entry/entries no longer unreferenced: ${removed.join(', ')}`);
    console.log('Run with --write to shrink the baseline.\n');
  }

  if (added.length === 0) {
    console.log(`No new unreferenced Kotlin declarations (${names.length} known, baselined).`);
    return;
  }

  console.error(`${added.length} Kotlin declaration(s) that nothing calls:\n`);
  for (const { name, file } of added) console.error(`  ${name.padEnd(34)} ${file}`);
  console.error(
    '\nEither wire it up, delete it, or — if something outside Kotlin reaches it — add it to' +
      '\nunreferenced-kotlin.baseline.json with a note saying what does.',
  );
  process.exitCode = 1;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) main();
