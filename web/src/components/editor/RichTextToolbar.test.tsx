import { describe, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { RichTextToolbar } from '@/components/editor/RichTextToolbar';
import { LinkDialog } from '@/components/editor/LinkDialog';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

function render(element: ReturnType<typeof createElement>) {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => root.render(element));
  return {
    container,
    cleanup: () => {
      act(() => root.unmount());
      container.remove();
    },
  };
}

describe('RichTextToolbar', () => {
  const handlers = () => ({
    onBold: vi.fn(),
    onItalic: vi.fn(),
    onBullet: vi.fn(),
    onChecklist: vi.fn(),
    onLink: vi.fn(),
    onAddImage: vi.fn(),
  });

  it('acts on click, which Enter, Space and screen readers fire, not on pointerdown alone', () => {
    const props = handlers();
    const { container, cleanup } = render(
      createElement(RichTextToolbar, { contentColor: '#000', ...props }),
    );
    const bold = container.querySelector<HTMLButtonElement>('button[aria-label="Bold"]')!;

    const down = new PointerEvent('pointerdown', { bubbles: true, cancelable: true });
    act(() => {
      bold.dispatchEvent(down);
    });
    expect(props.onBold).not.toHaveBeenCalled();
    // Still cancelled, so a click does not take focus from the note body.
    expect(down.defaultPrevented).toBe(true);

    act(() => bold.click());
    expect(props.onBold).toHaveBeenCalledTimes(1);

    for (const [label, handler] of [
      ['Italic', props.onItalic],
      ['Link', props.onLink],
      ['Bullet list', props.onBullet],
      ['Checklist', props.onChecklist],
      ['Add image', props.onAddImage],
    ] as const) {
      act(() => container.querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`)!.click());
      expect(handler, label).toHaveBeenCalledTimes(1);
    }
    cleanup();
  });

  it('names the Bold and Italic shortcuts', () => {
    const { container, cleanup } = render(
      createElement(RichTextToolbar, { contentColor: '#000', ...handlers() }),
    );
    expect(container.querySelector('button[aria-label="Bold"]')?.getAttribute('aria-keyshortcuts')).toBe('Control+B');
    expect(container.querySelector('button[aria-label="Italic"]')?.getAttribute('aria-keyshortcuts')).toBe('Control+I');
    cleanup();
  });
});

describe('LinkDialog', () => {
  it('adds the link on Enter, and accepts an address without a scheme', () => {
    const onConfirm = vi.fn();
    const { cleanup } = render(createElement(LinkDialog, { open: true, onCancel: vi.fn(), onConfirm }));
    const input = document.querySelector<HTMLInputElement>('input[aria-label="Link URL"]')!;
    const setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
    act(() => {
      setValue.call(input, ' example.com ');
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });
    act(() => {
      input.form!.requestSubmit();
    });
    expect(onConfirm).toHaveBeenCalledWith('example.com');
    cleanup();
  });

  it('does not submit an empty address', () => {
    const onConfirm = vi.fn();
    const { cleanup } = render(createElement(LinkDialog, { open: true, onCancel: vi.fn(), onConfirm }));
    const input = document.querySelector<HTMLInputElement>('input[aria-label="Link URL"]')!;
    act(() => {
      input.form!.requestSubmit();
    });
    expect(onConfirm).not.toHaveBeenCalled();
    cleanup();
  });
});
