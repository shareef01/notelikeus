/**
 * Brand glyph: primary disc with surface-colored bars so contrast holds in every theme
 * (light = dark disc + light bars; dark = light disc + dark bars).
 *
 * Five full-opacity bars, sized so the cluster stays crisp at small sidebar sizes
 * (bar width ≈ 8% of the mark, gap ≈ 6%, height ≈ 50%). Opacity fades are
 * deliberately not used — they smudged into a dark blob at collapsed size.
 */
export function BrandMark({ size = 40, className = '' }: { size?: number; className?: string }) {
  const barWidth = Math.max(2, Math.round(size * 0.08));
  const barHeight = Math.round(size * 0.5);
  const gap = Math.max(2, Math.round(size * 0.06));
  const bars = [0, 1, 2, 3, 4];

  return (
    <div
      className={`inline-flex shrink-0 items-center justify-center rounded-full bg-brand-primary ring-1 ring-brand-secondary/50 ${className}`}
      style={{ width: size, height: size }}
      aria-hidden
    >
      <div className="flex items-center" style={{ gap }}>
        {bars.map((index) => (
          <span
            key={index}
            className="rounded-full bg-true-surface"
            style={{ width: barWidth, height: barHeight }}
          />
        ))}
      </div>
    </div>
  );
}
