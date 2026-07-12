import { useMemo } from 'react';
import { useLocation } from 'react-router-dom';
import { useUiStore, type ViewColumns } from '@/store/uiStore';
import { useMediaQuery } from '@/hooks/useMediaQuery';

/** Combines user column preference with viewport limits and pane states. */
export function useEffectiveColumns(preference: ViewColumns): ViewColumns {
  const location = useLocation();
  const isTablet = useMediaQuery('(min-width: 640px)');
  const isDesktop = useMediaQuery('(min-width: 1024px)');
  const isWide = useMediaQuery('(min-width: 1440px)');
  const isUltraWide = useMediaQuery('(min-width: 1920px)');

  const editorOpen = location.pathname.startsWith('/note/');
  const sidebarCollapsed = useUiStore((s) => s.sidebarCollapsed);

  return useMemo(() => {
    if (!isTablet) return 1;

    // Mobile/Tablet logic
    if (!isDesktop) return Math.min(preference, 2) as ViewColumns;

    // Desktop logic
    if (editorOpen) {
      // When editor is open, space for list is limited
      return (isWide ? Math.min(preference, 2) : 1) as ViewColumns;
    }

    // Full screen list (no editor)
    let maxColumns: ViewColumns = 3;
    if (isUltraWide) maxColumns = 6;
    else if (isWide) maxColumns = 4;

    // If sidebar is collapsed, we might afford one more column or just more breathing room
    if (sidebarCollapsed && isWide) maxColumns = Math.min(maxColumns + 1, 6) as ViewColumns;

    return Math.min(preference, maxColumns) as ViewColumns;
  }, [preference, isTablet, isDesktop, isWide, isUltraWide, editorOpen, sidebarCollapsed]);
}
