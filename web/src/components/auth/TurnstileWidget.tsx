import { loadTurnstile, type TurnstileApi } from '@/lib/auth/turnstile';
import { useEffect, useLayoutEffect, useRef } from 'react';

interface TurnstileWidgetProps {
  siteKey: string;
  /** Called with a fresh token, and with null when the token expires or the check fails. */
  onToken: (token: string | null) => void;
  /** Called when Cloudflare's script cannot be loaded at all. */
  onLoadError: () => void;
  /**
   * Bumped by the parent after each code request: a token is single-use, so the widget runs a new
   * check for the next request.
   */
  resetKey: number;
}

/** Cloudflare Turnstile's check, rendered explicitly so it can be reset between code requests. */
export function TurnstileWidget({ siteKey, onToken, onLoadError, resetKey }: TurnstileWidgetProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const widget = useRef<{ api: TurnstileApi; id: string } | null>(null);
  // Latest callbacks, so the render effect below runs once per site key, not per parent render.
  const callbacks = useRef({ onToken, onLoadError });
  useLayoutEffect(() => {
    callbacks.current = { onToken, onLoadError };
  });

  useEffect(() => {
    let cancelled = false;
    loadTurnstile()
      .then((api) => {
        const container = containerRef.current;
        if (cancelled || !container) return;
        const id = api.render(container, {
          sitekey: siteKey,
          action: 'email-code',
          theme: 'auto',
          callback: (token) => callbacks.current.onToken(token),
          'expired-callback': () => callbacks.current.onToken(null),
          'error-callback': () => callbacks.current.onToken(null),
        });
        if (id !== undefined) widget.current = { api, id };
      })
      .catch(() => {
        if (!cancelled) callbacks.current.onLoadError();
      });
    return () => {
      cancelled = true;
      widget.current?.api.remove(widget.current.id);
      widget.current = null;
    };
  }, [siteKey]);

  useEffect(() => {
    if (resetKey === 0 || !widget.current) return;
    callbacks.current.onToken(null);
    widget.current.api.reset(widget.current.id);
  }, [resetKey]);

  return <div ref={containerRef} className="flex min-h-[65px] justify-center" data-testid="turnstile" />;
}
