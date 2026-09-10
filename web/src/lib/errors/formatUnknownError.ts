const FALLBACK = 'Something went wrong. Please try again.';

function usableMessage(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  if (!trimmed || trimmed === '[object Object]') return null;
  return trimmed;
}

/**
 * Turns unknown thrown values into a short user-facing string.
 *
 * Never returns `"[object Object]"` — that is what `String(supabaseError)` produces
 * for PostgREST / Auth plain objects that are thrown without wrapping.
 */
export function formatUnknownError(
  error: unknown,
  fallback: string = FALLBACK,
): string {
  if (error == null) return fallback;

  if (typeof error === 'string') {
    return usableMessage(error) ?? fallback;
  }

  if (error instanceof Error) {
    return usableMessage(error.message) ?? fallback;
  }

  if (typeof error === 'object') {
    const record = error as Record<string, unknown>;
    for (const key of [
      'message',
      'error_description',
      'error',
      'msg',
      'detail',
      'statusText',
      'reason',
    ] as const) {
      const fromField = usableMessage(record[key]);
      if (fromField) return fromField;
    }

    if ('cause' in record && record.cause != null) {
      const nested = formatUnknownError(record.cause, '');
      if (nested) return nested;
    }

    // IndexedDB / DOMException-like shapes without being Error instances.
    const name = usableMessage(record.name);
    const code = typeof record.code === 'number' || typeof record.code === 'string'
      ? String(record.code)
      : null;
    if (name && code) return `${name} (${code})`;
    if (name) return name;
  }

  return fallback;
}

/** Wrap unknown failures so callers that require `Error` never get a useless message. */
export function toError(error: unknown, fallback?: string): Error {
  if (error instanceof Error) {
    const message = usableMessage(error.message);
    if (message) return error;
    return new Error(formatUnknownError(error, fallback));
  }
  return new Error(formatUnknownError(error, fallback));
}
