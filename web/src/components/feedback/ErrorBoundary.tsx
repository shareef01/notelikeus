import { clearPersistedAppData } from '@/lib/bootstrap';
import { Component, type ErrorInfo, type ReactNode } from 'react';

interface Props {
  children: ReactNode;
  /** Soft recovery for overlays (editor/auth) — dismiss without wiping storage. */
  onDismiss?: () => void;
  /** When false, hide the destructive clear-data action (nested route boundaries). */
  allowClearData?: boolean;
  /** Compact overlay style instead of full-viewport takeover. */
  variant?: 'page' | 'overlay';
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

  private clearError = () => {
    this.setState({ error: null });
  };

  render() {
    if (this.state.error) {
      const allowClear = this.props.allowClearData !== false;
      const isOverlay = this.props.variant === 'overlay';

      return (
        <div
          style={{
            minHeight: isOverlay ? undefined : '100vh',
            height: isOverlay ? '100%' : undefined,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            padding: 24,
            background: isOverlay ? 'rgba(0,0,0,0.92)' : '#000',
            color: '#f2f2f2',
            fontFamily: 'system-ui, sans-serif',
            position: isOverlay ? 'fixed' : undefined,
            inset: isOverlay ? 0 : undefined,
            zIndex: isOverlay ? 80 : undefined,
          }}
        >
          <div style={{ maxWidth: 420, textAlign: 'center' }}>
            <h1 style={{ fontSize: 20, marginBottom: 12 }}>Something went wrong</h1>
            <p style={{ color: '#b0b0b0', fontSize: 14, marginBottom: 10 }}>
              {this.state.error.message}
            </p>
            <p style={{ color: '#8a8a8a', fontSize: 12, lineHeight: 1.45, marginBottom: 16 }}>
              Try reloading first. If this keeps happening, clearing local app data can recover
              from corrupted browser storage, but it will remove unsynced local state.
            </p>
            <div
              style={{
                display: 'flex',
                gap: 8,
                justifyContent: 'center',
                flexWrap: 'wrap',
              }}
            >
              {this.props.onDismiss ? (
                <button
                  type="button"
                  onClick={() => {
                    this.clearError();
                    this.props.onDismiss?.();
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
                  Close
                </button>
              ) : (
                <button
                  type="button"
                  onClick={() => {
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
                  Reload
                </button>
              )}
              {allowClear ? (
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
                    background: 'transparent',
                    color: '#f2f2f2',
                    border: '1px solid #555',
                    borderRadius: 12,
                    padding: '10px 16px',
                    fontWeight: 600,
                    cursor: 'pointer',
                  }}
                >
                  Clear data & reload
                </button>
              ) : null}
            </div>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}
