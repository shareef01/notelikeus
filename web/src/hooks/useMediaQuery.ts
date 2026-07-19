import { useEffect, useState } from 'react';

export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => {
    if (typeof window === 'undefined') return false;
    return window.matchMedia(query).matches;
  });

  useEffect(() => {
    const media = window.matchMedia(query);
    const onChange = () => setMatches(media.matches);
    onChange();
    media.addEventListener('change', onChange);
    return () => media.removeEventListener('change', onChange);
  }, [query]);

  return matches;
}

export function useIsDesktop(): boolean {
  return useMediaQuery('(min-width: 1024px)');
}

/** Tablet and up — permanent drawer, desktop-style editor chrome (float/dock). */
export function useIsTabletUp(): boolean {
  return useMediaQuery('(min-width: 768px)');
}

export function useIsWide(): boolean {
  return useMediaQuery('(min-width: 1280px)');
}
