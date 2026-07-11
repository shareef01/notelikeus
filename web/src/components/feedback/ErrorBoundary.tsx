import { clearPersistedAppData } from '@/lib/bootstrap';
import { Component, type ErrorInfo, type ReactNode } from 'react';

interface Props {
  children: ReactNode;
}

interface State {
  error: Error | null;
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[Notelikeus] Render error:', error, info);
  }

  render() {
    if (this.state.error) {
      return (
        <div className="flex min-h-screen items-center justify-center bg-true-black p-6 text-brand-primary">
          <div className="max-w-md text-center">
            <h1 className="text-xl font-semibold">Something went wrong</h1>
            <p className="mt-3 text-sm text-brand-muted">{this.state.error.message}</p>
            <button
              type="button"
              onClick={() => {
                try {
                  clearPersistedAppData();
                  sessionStorage.clear();
                } catch {
                  // ignore
                }
                window.location.reload();
              }}
              className="mt-4 rounded-note bg-brand-primary px-4 py-2.5 text-sm font-semibold text-true-black"
            >
              Clear data & reload
            </button>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}
