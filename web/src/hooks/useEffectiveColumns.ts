import { useMemo } from 'react';
import { useLocation } from 'react-router-dom';
import { useUiStore, type ViewColumns } from '@/store/uiStore';
import { useMediaQuery } from '@/hooks/useMediaQuery';

export type EffectiveColumns = 1 | 2 | 3 | 4;

/**
 * Maps the user's view preference onto a column count that keeps note cards
 * well-proportioned at every breakpoint. Desktop never falls back to a single
 * full-bleed column (that turns short notes into wide ribbons).
 */
export function useEffectiveColumns(preference: ViewColumns): EffectiveColumns {
  const isTablet = useMediaQuery('(min-width: 640px)');
  const isDesktop = useMediaQuery('(min-width: 1024px)');
  const isWide = useMediaQuery('(min-width: 1280px)');
  const isVeryWide = useMediaQuery('(min-width: 1536px)');

  return useMemo(() => {
    // Phones: always a single readable stack.
    if (!isTablet) return 1;

    // Tablets: list stays 1; grid prefs share 2 columns.
    if (!isDesktop) {
      return preference === 1 ? 1 : 2;
    }

    // Desktop+: promote list → 2 so cards keep a card shape.
    if (preference === 1) return 2;
    if (preference === 2) return isWide ? 3 : 2;
    return isVeryWide ? 4 : 3;
  }, [preference, isTablet, isDesktop, isWide, isVeryWide]);
}
