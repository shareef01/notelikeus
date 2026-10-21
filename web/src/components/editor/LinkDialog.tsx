import { useEffect, useId, useState } from 'react';
import {
  ModalDialog,
  dialogCancelButtonClass,
  dialogPrimaryButtonClass,
} from '@/components/ui/ModalDialog';
import { INPUT_FOCUS } from '@/lib/ui/focusStyles';

interface LinkDialogProps {
  open: boolean;
  onCancel: () => void;
  onConfirm: (url: string) => void;
}

/**
 * Built on ModalDialog, which it used to copy by hand without the page scroll lock. The fields sit
 * in a form so Enter adds the link, as it does in every other "type and confirm" dialog.
 */
export function LinkDialog({ open, onCancel, onConfirm }: LinkDialogProps) {
  const [url, setUrl] = useState('');
  const titleId = useId();

  useEffect(() => {
    if (open) setUrl('');
  }, [open]);

  return (
    <ModalDialog open={open} onClose={onCancel} ariaLabelledBy={titleId}>
      {/* noValidate: "example.com" was always accepted here; native URL validation would refuse it. */}
      <form
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          if (url.trim()) onConfirm(url.trim());
        }}
      >
        <h2 id={titleId} className="text-lg font-semibold">
          Add link
        </h2>
        <input
          type="url"
          value={url}
          onChange={(event) => setUrl(event.target.value)}
          placeholder="https://example.com"
          aria-label="Link URL"
          className={`mt-4 w-full rounded-note border border-brand-outline/50 bg-transparent px-4 py-3 text-sm text-brand-primary ${INPUT_FOCUS}`}
        />
        <div className="mt-5 flex justify-end gap-2">
          <button type="button" onClick={onCancel} className={dialogCancelButtonClass}>
            Cancel
          </button>
          <button type="submit" disabled={!url.trim()} className={dialogPrimaryButtonClass}>
            OK
          </button>
        </div>
      </form>
    </ModalDialog>
  );
}
