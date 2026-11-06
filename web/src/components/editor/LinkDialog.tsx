import { useEffect, useId, useState } from 'react';
import {
  ModalDialog,
  dialogCancelButtonClass,
  dialogPrimaryButtonClass,
} from '@/components/ui/ModalDialog';
import { INPUT_FOCUS } from '@/lib/ui/focusStyles';
import { toSafeHref } from '@/lib/text/markdown';

interface LinkDialogProps {
  open: boolean;
  onCancel: () => void;
  onConfirm: (url: string) => void;
  initialUrl?: string;
  onRemove?: () => void;
}

/**
 * Built on ModalDialog, which it used to copy by hand without the page scroll lock. The fields sit
 * in a form so Enter adds the link, as it does in every other "type and confirm" dialog.
 */
export function LinkDialog({ open, onCancel, onConfirm, initialUrl = '', onRemove }: LinkDialogProps) {
  const [url, setUrl] = useState('');
  const [error, setError] = useState('');
  const titleId = useId();

  useEffect(() => {
    if (open) { setUrl(initialUrl); setError(''); }
  }, [open, initialUrl]);

  return (
    <ModalDialog open={open} onClose={onCancel} ariaLabelledBy={titleId}>
      {/* noValidate: "example.com" was always accepted here; native URL validation would refuse it. */}
      <form
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          const href = toSafeHref(url.trim());
          try {
            const parsed = new URL(href);
            if (!['http:', 'https:', 'mailto:'].includes(parsed.protocol) ||
                (parsed.protocol === 'mailto:' ? !parsed.pathname : !parsed.hostname)) throw new Error();
            onConfirm(href);
          } catch { setError('Enter a valid website or email link.'); }
        }}
      >
        <h2 id={titleId} className="text-lg font-semibold">
          {initialUrl ? 'Edit link' : 'Add link'}
        </h2>
        <input
          type="url"
          value={url}
          onChange={(event) => setUrl(event.target.value)}
          placeholder="https://example.com"
          aria-label="Link URL"
          aria-invalid={!!error}
          aria-describedby={error ? `${titleId}-error` : undefined}
          className={`mt-4 w-full rounded-note border border-brand-outline/50 bg-transparent px-4 py-3 text-sm text-brand-primary ${INPUT_FOCUS}`}
        />
        {error && <p id={`${titleId}-error`} role="alert" className="mt-2 text-sm text-red-700 dark:text-red-300">{error}</p>}
        <div className="mt-5 flex flex-wrap justify-end gap-2">
          {initialUrl && onRemove && <button type="button" onClick={onRemove} className={dialogCancelButtonClass}>Remove link</button>}
          <button type="button" onClick={onCancel} className={dialogCancelButtonClass}>
            Cancel
          </button>
          <button type="submit" disabled={!url.trim()} className={dialogPrimaryButtonClass}>
            {initialUrl ? 'Update link' : 'OK'}
          </button>
        </div>
      </form>
    </ModalDialog>
  );
}
