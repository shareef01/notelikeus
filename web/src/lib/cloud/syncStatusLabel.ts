import type { CloudSyncStatus } from '@/hooks/useCloudSync';

export function formatSyncStatus(status: CloudSyncStatus): string {
  switch (status) {
    case 'unknown':
      return 'Not signed in';
    case 'ready':
      return 'Ready to sync';
    case 'syncing':
      return 'Syncing…';
    case 'synced':
      return 'Up to date';
    case 'error':
      return 'Sync error';
    default:
      return status;
  }
}
