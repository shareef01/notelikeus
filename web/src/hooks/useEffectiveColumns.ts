import { useMemo } from 'react';
import type { ViewColumns } from '@/store/uiStore';
import { useMediaQuery } from '@/hooks/useMediaQuery';

/** Combines user column preference with viewport limits. */
export function useEffectiveColumns(preference: ViewColumns): ViewColumns {
  const isTablet = useMediaQuery('(min-width: 640px)');
  const isDesktop = useMediaQuery('(min-width: 1024px)');

  return useMemo(() => {
    if (!isTablet) return 1;
    if (!isDesktop) return (Math.min(preference, 2) as ViewColumns);
    return preference;
  }, [preference, isTablet, isDesktop]);
}
