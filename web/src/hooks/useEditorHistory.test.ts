import { expect, it, vi } from 'vitest';
import { navigateEditor, registerEditorBackHandler } from '@/hooks/useEditorHistory';

it('keeps the current editor open when its local write fails before navigation', async () => {
  const didSave = vi.fn().mockResolvedValueOnce(false).mockResolvedValueOnce(true);
  const next = vi.fn();
  const unregister = registerEditorBackHandler(vi.fn(), didSave);
  try {
    await navigateEditor(next);
    expect(next).not.toHaveBeenCalled();
    await navigateEditor(next);
    expect(next).toHaveBeenCalledOnce();
  } finally {
    unregister();
  }
});
