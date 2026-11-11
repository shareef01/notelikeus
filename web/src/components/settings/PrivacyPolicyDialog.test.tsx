import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { PrivacyPolicyDialog } from '@/components/settings/PrivacyPolicyDialog';

function readRepositoryPolicy(): string {
  let dir = dirname(fileURLToPath(import.meta.url));
  for (let i = 0; i < 8; i++) {
    const candidate = join(dir, 'PRIVACY_POLICY.md');
    if (existsSync(candidate)) return readFileSync(candidate, 'utf8');
    dir = dirname(dir);
  }
  throw new Error('Could not locate PRIVACY_POLICY.md from ' + import.meta.url);
}

/**
 * NL-03: the in-app policy said signing out clears this device's notes, which web does not do —
 * it keeps them, sealed and kept for the account. The in-app copy and PRIVACY_POLICY.md are two
 * hand-kept texts, so the sign-out sentence is checked against both.
 */
describe('PrivacyPolicyDialog sign-out wording', () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(() => {
    act(() => root.unmount());
    container.remove();
  });

  async function renderedPolicy(): Promise<string> {
    await act(async () => {
      root.render(createElement(PrivacyPolicyDialog, { open: true, onClose: () => {} }));
    });
    return document.body.textContent ?? '';
  }

  it('says web keeps the notes in the browser for the account', async () => {
    const text = await renderedPolicy();

    expect(text).toContain('On Web, they stay in the browser, encrypted and kept for that account only');
    expect(text).not.toContain('clears locally cached notes');
  });

  it('states local data isolation exactly as PRIVACY_POLICY.md does', async () => {
    const text = await renderedPolicy();
    const policy = readRepositoryPolicy().replace(/\*\*/g, '');
    const isolation = /Local Data Isolation: (.+)/i.exec(policy)?.[1]?.trim();

    expect(isolation).toBeTruthy();
    expect(text).toContain(isolation!);
  });
});
