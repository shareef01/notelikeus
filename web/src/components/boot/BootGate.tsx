import { useEffect, useState } from 'react';
import App from '@/App';
import { BootFailure, bootstrapApp, clearPersistedAppData } from '@/lib/bootstrap';
import { AppSplash } from '@/components/boot/AppSplash';

type BootErrorDetails = {
  message: string;
  help: string;
  canClearData: boolean;
};

type BootState =
  | { status: 'loading' }
  | { status: 'ready' }
  | ({ status: 'error' } & BootErrorDetails);

function describeBootError(error: unknown): BootErrorDetails {
  if (error instanceof BootFailure) {
    switch (error.code) {
      case 'storage':
        return {
          message: error.message,
          help: 'Your browser may have blocked or corrupted local app storage. Clearing local data is the best recovery step.',
          canClearData: true,
        };
      case 'supabase-config':
        return {
          message: error.message,
          help: 'Check the Supabase values for this build. Production needs VITE_SUPABASE_URL pointing at a hosted project and a public VITE_SUPABASE_ANON_KEY (the eyJ… anon JWT, never an sb_… secret key).',
          canClearData: false,
        };
      default:
        break;
    }
  }

  return {
    message: error instanceof Error ? error.message : 'Startup failed',
    help: 'Retry first. If startup keeps failing, clear local data and try again.',
    canClearData: true,
  };
}

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
            ...describeBootError(error),
          });
        }
      });

    return () => {
      cancelled = true;
    };
  }, []);

  if (boot.status === 'loading') {
    return <AppSplash />;
  }

  if (boot.status === 'error') {
    return (
      <BootError
        message={boot.message}
        help={boot.help}
        canClearData={boot.canClearData}
        onRetry={() => {
          setBoot({ status: 'loading' });
          void bootstrapApp()
            .then(() => setBoot({ status: 'ready' }))
            .catch((error) =>
              setBoot({
                status: 'error',
                ...describeBootError(error),
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
                ...describeBootError(error),
              }),
            );
        }}
      />
    );
  }

  return <App />;
}

function BootError({
  message,
  help,
  canClearData,
  onRetry,
  onReset,
}: {
  message: string;
  help: string;
  canClearData: boolean;
  onRetry: () => void;
  onReset: () => void;
}) {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-true-surface px-6 text-center">
      <h1 className="text-lg font-semibold text-brand-primary">Could not start Notelikeus</h1>
      <p className="mt-2 max-w-sm text-sm text-brand-muted">{message}</p>
      <p className="mt-2 max-w-md text-xs leading-5 text-brand-muted/90">{help}</p>
      <div className="mt-6 flex flex-col gap-3 sm:flex-row">
        <button
          type="button"
          onClick={onRetry}
          className="rounded-note bg-brand-primary px-5 py-2.5 text-sm font-semibold text-true-surface"
        >
          Retry
        </button>
        {canClearData ? (
          <button
            type="button"
            onClick={onReset}
            className="rounded-note border border-brand-outline px-5 py-2.5 text-sm font-semibold text-brand-primary"
          >
            Clear data &amp; retry
          </button>
        ) : null}
      </div>
    </div>
  );
}
