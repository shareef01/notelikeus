import { describe, expect, it, vi } from 'vitest';
import {
  assertJsonNestingWithinLimit,
  maxJsonNestingDepth,
  objectNestingDepth,
} from '@/lib/backup/jsonNesting';
import { MAX_JSON_DEPTH } from '@/lib/backup/constants';
import { importNotesFromBackup, readBackupFile } from '@/lib/backup/importBackup';

function nestedArrayJson(depth: number): string {
  return `${'['.repeat(depth)}${']'.repeat(depth)}`;
}

describe('maxJsonNestingDepth', () => {
  it('counts structural braces and ignores braces inside strings', () => {
    expect(maxJsonNestingDepth('{"a":1}')).toBe(1);
    expect(maxJsonNestingDepth('[[]]')).toBe(2);
    expect(maxJsonNestingDepth('{"content":"{ [ { ["}')).toBe(1);
  });

  it('treats escaped quotes as still inside the string', () => {
    expect(maxJsonNestingDepth('{"content":"a\\"[["}')).toBe(1);
    expect(maxJsonNestingDepth('{"content":"a\\\\"}')).toBe(1);
  });

  it('accepts depths at and just below the limit', () => {
    expect(maxJsonNestingDepth(nestedArrayJson(63))).toBe(63);
    expect(maxJsonNestingDepth(nestedArrayJson(64))).toBe(64);
    expect(() => assertJsonNestingWithinLimit(nestedArrayJson(64))).not.toThrow();
  });

  it('rejects depth 65 and pathological depths without overflowing the stack', () => {
    expect(maxJsonNestingDepth(nestedArrayJson(65))).toBe(65);
    expect(() => assertJsonNestingWithinLimit(nestedArrayJson(65))).toThrow(
      /too deeply nested/,
    );
    expect(() => assertJsonNestingWithinLimit(nestedArrayJson(1_000))).toThrow(
      /too deeply nested/,
    );
    expect(() => assertJsonNestingWithinLimit(nestedArrayJson(10_000))).toThrow(
      /too deeply nested/,
    );
  });
});

describe('objectNestingDepth', () => {
  it('walks an already-parsed graph iteratively', () => {
    let value: unknown = null;
    for (let i = 0; i < 10; i++) value = [value];
    expect(objectNestingDepth(value)).toBe(10);
  });
});

describe('readBackupFile nesting guard', () => {
  it('rejects excessive nesting before parse, as a controlled validation error', async () => {
    const file = new File([nestedArrayJson(10_000)], 'hostile.json', {
      type: 'application/json',
    });
    await expect(readBackupFile(file)).rejects.toThrow(/too deeply nested/);
  });

  it('accepts a normal backup at depth well under the limit', async () => {
    const body = JSON.stringify({
      version: 3,
      notes: [{ title: 'ok', content: 'x', timestamp: 1, color: 0 }],
    });
    const file = new File([body], 'ok.json', { type: 'application/json' });
    const parsed = await readBackupFile(file);
    const { result } = importNotesFromBackup(parsed, []);
    expect(result.notesImported).toBe(1);
  });
});

describe('importNotesFromBackup nesting parity', () => {
  it(`rejects a parsed graph deeper than ${MAX_JSON_DEPTH}`, () => {
    let value: unknown = { title: 'x' };
    for (let i = 0; i < MAX_JSON_DEPTH + 2; i++) {
      value = { notes: [value] };
    }
    expect(() => importNotesFromBackup(value, [])).toThrow(/too deeply nested/);
  });
});

describe('stack safety', () => {
  it('does not throw RangeError for a 10_000-deep raw scan', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(() => assertJsonNestingWithinLimit(nestedArrayJson(10_000))).toThrow(
      /too deeply nested/,
    );
    spy.mockRestore();
  });
});
