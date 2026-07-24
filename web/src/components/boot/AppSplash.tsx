/**
 * The one loading screen. Pixel-matched to the static #boot-splash in index.html (same copy,
 * sizes, colour, padding) so the whole boot — static HTML shell, React boot gate, and the
 * auth-chunk fallback — reads as a single continuous "Loading…" instead of a sequence of
 * differently-styled loading screens.
 */
export function AppSplash() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-true-surface p-6 text-center">
      <p className="text-lg font-semibold text-brand-primary">Notelikeus</p>
      <p className="mt-2 text-sm text-brand-muted">Loading…</p>
    </div>
  );
}
