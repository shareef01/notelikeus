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

/** Walks up to the repository root and reads a file there. */
function readRepositoryFile(relativePath: string): string {
  let dir = dirname(fileURLToPath(import.meta.url));
  for (let i = 0; i < 8; i++) {
    const candidate = join(dir, relativePath);
    if (existsSync(candidate)) return readFileSync(candidate, 'utf8');
    dir = dirname(dir);
  }
  throw new Error(`Could not locate ${relativePath} from ${import.meta.url}`);
}

/**
 * NL-06: the in-app policy is a hand-kept copy of PRIVACY_POLICY.md, and it had drifted — a month
 * older, no account-deletion section, and both pointed at a store listing the app doesn't have.
 * These checks make the next drift a failing test instead of an audit finding.
 */
describe('PrivacyPolicyDialog stays in step with PRIVACY_POLICY.md', () => {
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

  async function renderDialog(): Promise<HTMLElement> {
    await act(async () => {
      root.render(createElement(PrivacyPolicyDialog, { open: true, onClose: () => {} }));
    });
    return document.body;
  }

  const policy = readRepositoryFile('PRIVACY_POLICY.md');
  const normalise = (heading: string) => heading.trim().toLowerCase();

  it('has a section for every section of the policy', async () => {
    const body = await renderDialog();
    const inApp = new Set([...body.querySelectorAll('h3')].map((h) => normalise(h.textContent ?? '')));
    const sections = [...policy.matchAll(/^## (.+)$/gm)].map((match) => normalise(match[1]!));

    expect(sections.length).toBeGreaterThan(10);
    expect(sections.filter((section) => !inApp.has(section))).toEqual([]);
  });

  it('carries the same last-updated date', async () => {
    const body = await renderDialog();
    const date = /\*\*Last updated:\*\* (.+)/.exec(policy)?.[1]?.trim();

    expect(date).toBeTruthy();
    expect(body.textContent).toContain(`Last updated: ${date}`);
  });

  it('discloses Turnstile and names no store listing', async () => {
    const text = (await renderDialog()).textContent ?? '';

    for (const copy of [policy, text]) {
      expect(copy).toContain('Cloudflare Turnstile');
      expect(copy).toContain('challenges.cloudflare.com');
      expect(copy).not.toMatch(/store listing/i);
    }
  });

  it('keeps the native summary naming every provider the policy lists', () => {
    const strings = readRepositoryFile('composeApp/src/commonMain/composeResources/values/strings.xml');
    const summary = /<string name="privacy_policy_body">([\s\S]*?)<\/string>/.exec(strings)?.[1] ?? '';

    for (const provider of ['Supabase', 'Cloudflare R2', 'Cloudflare Turnstile']) {
      expect(summary).toContain(provider);
    }
  });
});
