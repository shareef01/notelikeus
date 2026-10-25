import { describe, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { Button } from './Button';
import { Badge } from './Badge';
import { EmptyState } from './EmptyState';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

describe('UI Primitives (Button, Badge, EmptyState)', () => {
  it('renders Button with variants and triggers onClick', () => {
    const container = document.createElement('div');
    const root = createRoot(container);
    const handleClick = vi.fn();

    act(() => {
      root.render(
        createElement(Button, { variant: 'primary', size: 'md', onClick: handleClick }, 'Click me'),
      );
    });

    const button = container.querySelector('button');
    expect(button).not.toBeNull();
    expect(button?.textContent).toBe('Click me');
    button?.click();
    expect(handleClick).toHaveBeenCalledTimes(1);

    act(() => {
      root.unmount();
    });
  });

  it('renders Button with secondary and danger variants and disabled state', () => {
    const container = document.createElement('div');
    const root = createRoot(container);

    act(() => {
      root.render(
        createElement(Button, { variant: 'danger', size: 'sm', disabled: true }, 'Delete note'),
      );
    });

    const button = container.querySelector('button');
    expect(button?.disabled).toBe(true);
    expect(button?.className).toContain('cursor-not-allowed');

    act(() => {
      root.unmount();
    });
  });

  it('renders Badge with variants and size tokens', () => {
    const container = document.createElement('div');
    const root = createRoot(container);

    act(() => {
      root.render(createElement(Badge, { variant: 'caution', size: 'sm' }, 'Conflict'));
    });

    const badge = container.querySelector('span');
    expect(badge?.textContent).toBe('Conflict');
    expect(badge?.className).toContain('amber-500');

    act(() => {
      root.unmount();
    });
  });

  it('renders EmptyState with title, description, and action button', () => {
    const container = document.createElement('div');
    const root = createRoot(container);

    act(() => {
      root.render(
        createElement(EmptyState, {
          title: 'No labels yet',
          description: 'Create labels to organize your notes',
          action: createElement('button', null, 'Add label'),
        }),
      );
    });

    expect(container.textContent).toContain('No labels yet');
    expect(container.textContent).toContain('Create labels to organize your notes');
    expect(container.querySelector('button')?.textContent).toBe('Add label');

    act(() => {
      root.unmount();
    });
  });
});
