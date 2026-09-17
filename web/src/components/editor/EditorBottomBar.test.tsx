import { describe, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { EditorBottomBar } from '@/components/editor/EditorBottomBar';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

interface RenderOptions {
  timestamp?: number;
  isSaving?: boolean;
  saveFailed?: boolean;
  isSavedLocally?: boolean;
  isSignedIn?: boolean;
  isOnline?: boolean;
  hasPendingAttachments?: boolean;
  reminderTimestamp?: number | null;
  onRetrySave?: () => void;
  onMoreClick?: () => void;
}

function render(options: RenderOptions = {}) {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  const onMoreClick = options.onMoreClick ?? vi.fn();
  act(() => {
    root.render(
      createElement(EditorBottomBar, {
        timestamp: options.timestamp ?? 1_700_000_000_000,
        isSaving: options.isSaving ?? false,
        saveFailed: options.saveFailed ?? false,
        isSavedLocally: options.isSavedLocally ?? true,
        isSignedIn: options.isSignedIn ?? false,
        isOnline: options.isOnline ?? true,
        hasPendingAttachments: options.hasPendingAttachments ?? false,
        reminderTimestamp: options.reminderTimestamp ?? null,
        onRetrySave: options.onRetrySave,
        onMoreClick,
        contentColor: '#ffffff',
      }),
    );
  });
  return {
    container,
    onMoreClick,
    cleanup: () => {
      act(() => {
        root.unmount();
      });
      container.remove();
    },
  };
}

describe('EditorBottomBar', () => {
  it('shows saving locally when a write is in flight', () => {
    const { container, cleanup } = render({ isSaving: true });
    expect(container.textContent).toContain('Saving locally…');
    cleanup();
  });

  it('shows local save failed with a retry action when save fails', () => {
    const onRetry = vi.fn();
    const { container, cleanup } = render({ saveFailed: true, onRetrySave: onRetry });
    expect(container.textContent).toContain('Local save failed');
    const retryBtn = Array.from(container.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Retry'),
    );
    expect(retryBtn).toBeDefined();
    act(() => {
      retryBtn?.click();
    });
    expect(onRetry).toHaveBeenCalledTimes(1);
    cleanup();
  });

  it('shows saved locally with edited time for guest / unauthenticated notes', () => {
    const { container, cleanup } = render({ isSignedIn: false });
    expect(container.textContent).toContain('Saved locally •');
    cleanup();
  });

  it('shows saved locally and synced when online and authenticated', () => {
    const { container, cleanup } = render({ isSignedIn: true, isOnline: true });
    expect(container.textContent).toContain('Saved locally • Synced');
    cleanup();
  });

  it('shows saved locally and offline when signed in but network is offline', () => {
    const { container, cleanup } = render({ isSignedIn: true, isOnline: false });
    expect(container.textContent).toContain('Saved locally • Offline');
    cleanup();
  });

  it('shows saved locally and sync pending when attachments are awaiting upload', () => {
    const { container, cleanup } = render({
      isSignedIn: true,
      isOnline: true,
      hasPendingAttachments: true,
    });
    expect(container.textContent).toContain('Saved locally • Sync pending');
    cleanup();
  });

  it('formats reminder date and time when reminder is set', () => {
    const { container, cleanup } = render({
      reminderTimestamp: 1_700_000_000_000 + 86_400_000,
    });
    expect(container.textContent).toContain('Reminder');
    cleanup();
  });

  it('triggers onMoreClick when clicking more options button', () => {
    const { container, onMoreClick, cleanup } = render();
    const moreBtn = container.querySelector('button[aria-label="More options"]');
    expect(moreBtn).toBeDefined();
    act(() => {
      (moreBtn as HTMLButtonElement)?.click();
    });
    expect(onMoreClick).toHaveBeenCalledTimes(1);
    cleanup();
  });

  // F3: Truthful local save indicator tests
  it('shows "Not saved yet" for unauthenticated guest when isSavedLocally is false', () => {
    const { container, cleanup } = render({
      isSavedLocally: false,
      isSignedIn: false,
      isSaving: false,
      saveFailed: false,
    });
    expect(container.textContent).toContain('Not saved yet');
    expect(container.textContent).not.toContain('Saved locally');
    cleanup();
  });

  it('shows "Not saved yet" for authenticated online user when isSavedLocally is false', () => {
    const { container, cleanup } = render({
      isSavedLocally: false,
      isSignedIn: true,
      isOnline: true,
      isSaving: false,
      saveFailed: false,
    });
    expect(container.textContent).toContain('Not saved yet');
    expect(container.textContent).not.toContain('Saved locally');
    expect(container.textContent).not.toContain('Synced');
    cleanup();
  });

  it('prioritizes saveFailed over isSavedLocally false', () => {
    const { container, cleanup } = render({
      saveFailed: true,
      isSavedLocally: false,
      isSaving: false,
    });
    expect(container.textContent).toContain('Local save failed');
    cleanup();
  });

  it('prioritizes isSaving over isSavedLocally false', () => {
    const { container, cleanup } = render({
      isSaving: true,
      isSavedLocally: false,
      saveFailed: false,
    });
    expect(container.textContent).toContain('Saving locally…');
    cleanup();
  });
});
