import { describe, expect, it } from 'vitest';
import { selectSyncPhase, useSyncStore } from '@/store/syncStore';

describe('syncStore', () => {
  it('does not report synced merely because the browser is online', () => {
    useSyncStore.getState().reset();
    useSyncStore.getState().markOnline(true);
    expect(selectSyncPhase(useSyncStore.getState())).toBe('idle');
  });

  it('reports synced only after a successful reconciliation', () => {
    useSyncStore.getState().reset();
    useSyncStore.getState().markOnline(true);
    useSyncStore.getState().markReconcileSuccess(3);
    expect(selectSyncPhase(useSyncStore.getState())).toBe('synced');
    expect(useSyncStore.getState().lastSyncedRemoteCount).toBe(3);
  });

  it('reports offline-with-local-changes when dirty and offline', () => {
    useSyncStore.getState().reset();
    useSyncStore.getState().markOnline(false);
    useSyncStore.getState().markPendingLocalMutations(true);
    expect(selectSyncPhase(useSyncStore.getState())).toBe('offline-with-local-changes');
  });

  it('reports error after a failed sync', () => {
    useSyncStore.getState().reset();
    useSyncStore.getState().markOnline(true);
    useSyncStore.getState().markError('transport failed');
    expect(selectSyncPhase(useSyncStore.getState())).toBe('error');
  });
});
