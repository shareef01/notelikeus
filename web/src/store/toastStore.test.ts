import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useToastStore } from '@/store/toastStore';

beforeEach(() => {
  useToastStore.getState().dismiss();
});

describe('toastStore', () => {
  it('shows a default-tone message', () => {
    useToastStore.getState().show('Saved');
    expect(useToastStore.getState().message).toMatchObject({ text: 'Saved', tone: 'default' });
  });

  it('carries a tone and an action', () => {
    const onAction = vi.fn();
    useToastStore.getState().show('Deleted', 'error', 'Undo', onAction);
    const message = useToastStore.getState().message;
    expect(message).toMatchObject({ text: 'Deleted', tone: 'error', actionLabel: 'Undo' });

    message?.onAction?.();
    expect(onAction).toHaveBeenCalledOnce();
  });

  it('gives each toast a new id so repeats re-render', () => {
    useToastStore.getState().show('Saved');
    const first = useToastStore.getState().message?.id;
    useToastStore.getState().show('Saved');
    expect(useToastStore.getState().message?.id).not.toBe(first);
  });

  it('replaces the previous toast rather than queueing', () => {
    useToastStore.getState().show('First');
    useToastStore.getState().show('Second');
    expect(useToastStore.getState().message?.text).toBe('Second');
  });

  it('dismisses to null', () => {
    useToastStore.getState().show('Saved');
    useToastStore.getState().dismiss();
    expect(useToastStore.getState().message).toBeNull();
  });
});
