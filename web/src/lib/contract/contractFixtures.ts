import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Locates the repository's `contracts/` directory by walking up from this file.
 *
 * Resolved from the module's own location rather than `process.cwd()`, so the fixtures load the
 * same way whether vitest is invoked from `web/` or from the repository root.
 */
function contractsRoot(): string {
  let dir = dirname(fileURLToPath(import.meta.url));
  for (let depth = 0; depth < 12; depth++) {
    try {
      readFileSync(join(dir, 'contracts', 'README.md'));
      return join(dir, 'contracts');
    } catch {
      const parent = dirname(dir);
      if (parent === dir) break;
      dir = parent;
    }
  }
  throw new Error('Could not locate the contracts/ directory from ' + import.meta.url);
}

/** Reads and parses `contracts/<relativePath>`. */
export function readContractFixture<T = unknown>(relativePath: string): T {
  return JSON.parse(readFileSync(join(contractsRoot(), relativePath), 'utf8')) as T;
}
