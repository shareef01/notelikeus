import { useEffect, useState } from 'react';
import App from '@/App';
import { bootstrapApp, clearPersistedAppData } from '@/lib/bootstrap';

type BootState =
  | { status: 'loading' }
  | { status: 'ready' }
  | { status: 'error'; message: string };

export function BootGate() {
  const [boot, setBoot] = useState<BootState>({ status: 'loading' });

  useEffect(() => {
    let cancelled = false;

    void bootstrapApp()
      .then(() => {
        if (!cancelled) setBoot({ status: 'ready' });
      })
      .catch((error) => {
        if (!cancelled) {
          setBoot({
            status: 'error',
            message: error instanceof Error ? error.message : 'Startup failed',
          });
        }
      });

    return () => {
      cancelled = true;
    };
  }, []);

  if (boot.status === 'loading') {
    return <BootSplash />;
  }

  if (boot.status === 'error') {
    return (
      <BootError
        message={boot.message}
        onRetry={() => {
          setBoot({ status: 'loading' });
          void bootstrapApp()
            .then(() => setBoot({ status: 'ready' }))
            .catch((error) =>
              setBoot({
                status: 'error',
                message: error instanceof Error ? error.message : 'Startup failed',
              }),
            );
        }}
        onReset={() => {
          clearPersistedAppData();
          setBoot({ status: 'loading' });
          void bootstrapApp()
            .then(() => setBoot({ status: 'ready' }))
            .catch((error) =>
              setBoot({
                status: 'error',
                message: error instanceof Error ? error.message : 'Startup failed',
              }),
            );
        }}
      />
    );
  }

  return <App />;
}

function BootSplash() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-true-surface px-6 text-center">
      <p className="text-lg font-semibold text-brand-primary">Notelikeus</p>
      <p className="mt-2 text-sm text-brand-muted">Loading your notes…</p>
      <div className="mt-6 size-8 animate-pulse rounded-full bg-brand-outline/60" aria-hidden />
    </div>
  );
}

function BootError({
  message,
  onRetry,
  onReset,
}: {
  message: string;
  onRetry: () => void;
  onReset: () => void;
}) {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-true-surface px-6 text-center">
      <h1 className="text-lg font-semibold text-brand-primary">Could not start Notelikeus</h1>
      <p className="mt-2 max-w-sm text-sm text-brand-muted">{message}</p>
      <div className="mt-6 flex flex-col gap-3 sm:flex-row">
        <button
          type="button"
          onClick={onRetry}
          className="rounded-note bg-brand-primary px-5 py-2.5 text-sm font-semibold text-true-surface"
        >
          Retry
        </button>
        <button
          type="button"
          onClick={onReset}
          className="rounded-note border border-brand-outline px-5 py-2.5 text-sm font-semibold text-brand-primary"
        >
          Clear data &amp; retry
        </button>
      </div>
    </div>
  );
}
