import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import type { Plugin } from 'vite';
import {
  applyAttachmentsConnectSrc,
  attachmentsConnectSrcOrigin,
} from './attachmentsConnectSrc.ts';
import {
  applySupabaseConnectSrc,
  requireSupabaseConnectSrcTokens,
} from './supabaseConnectSrc.ts';

/**
 * Rewrites `dist/_headers` connect-src to this build's Supabase host and Worker origin.
 * Never ships `*.supabase.co`, `*.workers.dev`, or the `[REDACTED]` placeholder host.
 */
export function pinAttachmentsCspPlugin(workerUrl: string, supabaseUrl: string): Plugin {
  let headersPath = resolve('dist/_headers');

  return {
    name: 'notelikeus-pin-connect-src-csp',
    apply: 'build',
    enforce: 'post',
    configResolved(config) {
      headersPath = resolve(config.root, config.build.outDir, '_headers');
    },
    closeBundle() {
      const supabase = requireSupabaseConnectSrcTokens(supabaseUrl);
      const workerOrigin = attachmentsConnectSrcOrigin(workerUrl);
      if (!existsSync(headersPath)) {
        throw new Error(`Cannot pin CSP: missing ${headersPath}`);
      }

      const withSupabase = applySupabaseConnectSrc(
        readFileSync(headersPath, 'utf8'),
        supabase,
      );
      const next = applyAttachmentsConnectSrc(withSupabase, workerOrigin);
      if (next.includes('[REDACTED]') || next.includes('https://*.supabase.co') || next.includes('https://*.workers.dev')) {
        throw new Error('Pinned CSP still contains a wildcard or placeholder host');
      }
      writeFileSync(headersPath, next);
    },
  };
}
