import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { shareText } from '@/lib/share/shareText';
import { useToastStore } from '@/store/toastStore';

describe('shareText', () => {
  const originalShare = navigator.share;
  const originalClipboard = navigator.clipboard;

  function setNavigator(patch: Partial<Navigator>) {
    for (const [key, value] of Object.entries(patch)) {
      Object.defineProperty(navigator, key, {
        value,
        configurable: true,
        writable: true,
      });
    }
  }

  beforeEach(() => {
    useToastStore.setState({ toasts: [] } as never);
  });

  afterEach(() => {
    setNavigator({ share: originalShare, clipboard: originalClipboard });
    vi.restoreAllMocks();
  });

  it('reports a successful native share without touching the clipboard', async () => {
    const writeText = vi.fn();
    setNavigator({
      share: vi.fn().mockResolvedValue(undefined) as never,
      clipboard: { writeText } as never,
    });

    expect(await shareText({ title: 'n', text: 'body' })).toBe('shared');
    expect(writeText).not.toHaveBeenCalled();
  });

  it('treats the user dismissing the share sheet as a cancellation, not a failure', async () => {
    const writeText = vi.fn();
    const abort = new DOMException('cancelled', 'AbortError');
    setNavigator({
      share: vi.fn().mockRejectedValue(abort) as never,
      clipboard: { writeText } as never,
    });

    expect(await shareText({ title: 'n', text: 'body' })).toBe('cancelled');
    // A cancellation must not silently copy to the clipboard behind the user's back.
    expect(writeText).not.toHaveBeenCalled();
  });

  it('falls back to the clipboard when the native share fails for a real reason', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    setNavigator({
      share: vi.fn().mockRejectedValue(new Error('no handler')) as never,
      clipboard: { writeText } as never,
    });

    expect(await shareText({ title: 'n', text: 'body' })).toBe('copied');
    expect(writeText).toHaveBeenCalledWith('body');
  });

  it('reports a clipboard rejection instead of leaving it unhandled', async () => {
    setNavigator({
      share: undefined as never,
      clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } as never,
    });

    // The regression: this used to reject out of the handler with nothing shown to the user.
    expect(await shareText({ title: 'n', text: 'body' })).toBe('copy-failed');
  });

  it('says sharing is unavailable when the browser offers neither route', async () => {
    setNavigator({ share: undefined as never, clipboard: undefined as never });

    expect(await shareText({ title: 'n', text: 'body' })).toBe('unsupported');
  });

  it('does nothing for an empty note', async () => {
    const writeText = vi.fn();
    setNavigator({ share: undefined as never, clipboard: { writeText } as never });

    expect(await shareText({ title: '', text: '   ' })).toBe('empty');
    expect(writeText).not.toHaveBeenCalled();
  });
});
