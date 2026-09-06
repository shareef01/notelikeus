import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import type { Plugin } from 'vite';
import {
  applyAttachmentsConnectSrc,
  attachmentsConnectSrcOrigin,
} from './attachmentsConnectSrc.ts';

/** Rewrites `dist/_headers` connect-src to the Worker origin for this build, never `*.workers.dev`. */
export function pinAttachmentsCspPlugin(workerUrl: string): Plugin {
  let headersPath = resolve('dist/_headers');

  return {
    name: 'notelikeus-pin-attachments-csp',
    apply: 'build',
    enforce: 'post',
    configResolved(config) {
      headersPath = resolve(config.root, config.build.outDir, '_headers');
    },
    closeBundle() {
      const origin = attachmentsConnectSrcOrigin(workerUrl);
      if (!existsSync(headersPath)) {
        if (origin) {
          throw new Error(`Cannot pin attachments CSP: missing ${headersPath}`);
        }
        return;
      }

      const next = applyAttachmentsConnectSrc(readFileSync(headersPath, 'utf8'), origin);
      writeFileSync(headersPath, next);
    },
  };
}
