import { MAX_JSON_DEPTH } from '@/lib/backup/constants';

/**
 * Maximum nesting depth of `{`/`[` in [jsonStr], ignoring braces inside strings.
 *
 * Matches Kotlin `NoteBackupImporter.maxJsonNestingDepth`: an iterative scan of the raw input
 * so a hostile file cannot overflow the JS call stack before the depth limit is reported.
 * Well above anything a real backup produces, and far below what JSON.parse can survive.
 */
export function maxJsonNestingDepth(jsonStr: string): number {
  let depth = 0;
  let maxDepth = 0;
  let inString = false;
  let escaped = false;
  for (let index = 0; index < jsonStr.length; index++) {
    const ch = jsonStr[index]!;
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (ch === '\\') {
        escaped = true;
      } else if (ch === '"') {
        inString = false;
      }
      continue;
    }
    if (ch === '"') {
      inString = true;
    } else if (ch === '{' || ch === '[') {
      depth++;
      if (depth > maxDepth) maxDepth = depth;
    } else if (ch === '}' || ch === ']') {
      if (depth > 0) depth--;
    }
  }
  return maxDepth;
}

/** Rejects raw JSON whose structural nesting exceeds {@link MAX_JSON_DEPTH}. */
export function assertJsonNestingWithinLimit(jsonStr: string): void {
  if (maxJsonNestingDepth(jsonStr) > MAX_JSON_DEPTH) {
    throw new Error('Backup file is too deeply nested');
  }
}

/**
 * Iterative depth of an already-parsed value. Defence in depth for callers that hold an object
 * graph (bundle path, tests) rather than the raw string the primary guard scans.
 */
export function objectNestingDepth(value: unknown): number {
  let max = 0;
  const stack: { value: unknown; depth: number }[] = [{ value, depth: 1 }];
  while (stack.length > 0) {
    const frame = stack.pop()!;
    if (frame.value === null || typeof frame.value !== 'object') {
      continue;
    }
    if (frame.depth > max) max = frame.depth;
    if (frame.depth > MAX_JSON_DEPTH) return frame.depth;
    const children = Array.isArray(frame.value)
      ? frame.value
      : Object.values(frame.value as Record<string, unknown>);
    for (const child of children) {
      if (child !== null && typeof child === 'object') {
        stack.push({ value: child, depth: frame.depth + 1 });
      }
    }
  }
  return max;
}
