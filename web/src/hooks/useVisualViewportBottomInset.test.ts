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




  it('tracks a real keyboard opening and closing', async () => {
    const vv = installViewport(800);
    const inset = await renderInset();

    vv.height = 480; // keyboard up: 320px occluded
    await act(async () => vv.emit('resize'));
    expect(inset()).toBe(320);

    vv.height = 800; // dismissed
    await act(async () => vv.emit('resize'));
    expect(inset()).toBe(0);
  });

  it('applies growing occlusion immediately, because controls must move out from under it', async () => {
    const vv = installViewport(800);
    const inset = await renderInset();

    vv.height = 480;
    await act(async () => vv.emit('resize'));
    expect(inset()).toBe(320);
  });

  /**
   * The actual root cause of the six mobile end-to-end failures, reproduced in CI's own browser
   * image: `vv.height` never moved, but `vv.offsetTop` alternated 42/58 as Playwright scrolled the
   * target into view, and subtracting it turned that into a 16/0 inset. The bar moved between every
   * aim and every landing, so scrolling to reach the control was what moved the control.
   */
  it('is unmoved by visual viewport scrolling', async () => {
    const vv = installViewport(727);
    Object.defineProperty(window, 'innerHeight', { value: 785, configurable: true, writable: true });
    const inset = await renderInset();
    expect(inset()).toBe(58);

    const observed = new Set<number>();
    for (const offsetTop of [42, 58, 42, 58, 0, 58, 42]) {
      vv.offsetTop = offsetTop;
      await act(async () => vv.emit('scroll'));
      observed.add(inset());
    }

    expect([...observed]).toEqual([58]);
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
