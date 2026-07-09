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
        <div
          style={{
            minHeight: '100vh',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            padding: 24,
            background: '#000',
            color: '#f2f2f2',
            fontFamily: 'system-ui, sans-serif',
          }}
        >
          <div style={{ maxWidth: 420, textAlign: 'center' }}>
            <h1 style={{ fontSize: 20, marginBottom: 12 }}>Something went wrong</h1>
            <p style={{ color: '#b0b0b0', fontSize: 14, marginBottom: 16 }}>
              {this.state.error.message}
            </p>
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
              style={{
                background: '#f2f2f2',
                color: '#000',
                border: 'none',
                borderRadius: 12,
                padding: '10px 16px',
                fontWeight: 600,
                cursor: 'pointer',
              }}
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
