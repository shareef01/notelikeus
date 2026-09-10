import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { useVisualViewportBottomInset } from '@/hooks/useVisualViewportBottomInset';

type FakeViewport = {
  height: number;
  offsetTop: number;
  addEventListener: (type: string, handler: () => void) => void;
  removeEventListener: (type: string, handler: () => void) => void;
  emit: (type: string) => void;
};

function installViewport(height: number): FakeViewport {
  const handlers: Record<string, Set<() => void>> = {};
  const vv: FakeViewport = {
    height,
    offsetTop: 0,
    addEventListener: (type, handler) => {
      (handlers[type] ??= new Set()).add(handler);
    },
    removeEventListener: (type, handler) => {
      handlers[type]?.delete(handler);
    },
    emit: (type) => {
      for (const handler of handlers[type] ?? []) handler();
    },
  };
  Object.defineProperty(window, 'visualViewport', {
    value: vv,
    configurable: true,
    writable: true,
  });
  return vv;
}

let root: Root | null = null;

async function renderInset(): Promise<() => number> {
  let latest = -1;
  function Probe() {
    latest = useVisualViewportBottomInset();
    return null;
  }
  const container = document.createElement('div');
  await act(async () => {
    root = createRoot(container);
    root.render(React.createElement(Probe));
  });
  return () => latest;
}

/**
 * The inset positions the editor's floating action bar. If it moves, the bar moves — and a control
 * that moves between the moment a tap is aimed and the moment it lands cannot be tapped at all.
 */
describe('useVisualViewportBottomInset', () => {
  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    Object.defineProperty(window, 'innerHeight', { value: 800, configurable: true, writable: true });
  });

  afterEach(async () => {
    const current = root;
    root = null;
    if (current) await act(async () => current.unmount());
  });

  it('reports no inset when the viewports agree', async () => {
    installViewport(800);
    const inset = await renderInset();
    expect(inset()).toBe(0);
  });

  /**
   * The exact regression. A CI trace caught the raw value alternating 0 → 16 → 0 → 16 across 57
   * DOM snapshots, dragging the action bar back and forth 16px and making "More options"
   * permanently unclickable in the mobile suite.
   */
  it('holds still through the 16px oscillation that made the action bar unclickable', async () => {
    const vv = installViewport(800);
    const inset = await renderInset();

    const observed = new Set<number>();
    for (let i = 0; i < 20; i++) {
      vv.height = i % 2 === 0 ? 784 : 800; // 16px of jitter, back and forth
      await act(async () => vv.emit('resize'));
      observed.add(inset());
    }

    expect([...observed]).toEqual([0]);
  });

  it('ignores browser-chrome sized occlusion that is not a keyboard', async () => {
    const vv = installViewport(800);
    const inset = await renderInset();

    for (const height of [742, 784, 800, 758]) {
      vv.height = height;
      await act(async () => vv.emit('resize'));
      expect(inset()).toBe(0);
    }
  });

  it('still tracks a real keyboard opening and closing', async () => {
    const vv = installViewport(800);
    const inset = await renderInset();

    vv.height = 480; // keyboard up: 320px occluded
    await act(async () => vv.emit('resize'));
    expect(inset()).toBe(320);

    vv.height = 800; // keyboard dismissed
    await act(async () => vv.emit('resize'));
    expect(inset()).toBe(0);
  });

  it('does not let scrolling drag the inset around once a keyboard is up', async () => {
    const vv = installViewport(480);
    const inset = await renderInset();
    expect(inset()).toBe(320);

    // Playwright scrolls a target into view before every click attempt, and on mobile that moves
    // the visual viewport. Small shifts must not move the bar the click is aimed at.
    for (const offsetTop of [0, 16, 40, 0, 24]) {
      vv.offsetTop = offsetTop;
      await act(async () => vv.emit('scroll'));
      expect(inset()).toBe(320);
    }
  });

  it('reports nothing when the browser has no visual viewport', async () => {
    Object.defineProperty(window, 'visualViewport', {
      value: undefined,
      configurable: true,
      writable: true,
    });
    const inset = await renderInset();
    expect(inset()).toBe(0);
  });
});
