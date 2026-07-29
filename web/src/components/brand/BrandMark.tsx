/**
 * Brand glyph: primary disc with surface-colored bars so contrast holds in every theme
 * (light = dark disc + light bars; dark = light disc + dark bars).
 */
export function BrandMark({ size = 40, className = '' }: { size?: number; className?: string }) {
  const barWidth = Math.max(2, Math.round(size * 0.07));
  const barHeight = Math.round(size * 0.55);
  const gap = Math.max(2, Math.round(size * 0.055));
  const barAlphas = [0.72, 0.88, 1, 0.88, 0.72];

  return (
    <div
      className={`inline-flex items-center justify-center rounded-full bg-brand-primary ring-1 ring-brand-outline/35 ${className}`}
      style={{ width: size, height: size }}
      aria-hidden
    >
      <div className="flex items-center" style={{ gap }}>
        {barAlphas.map((alpha, index) => (
          <span
            key={index}
            className="rounded-full bg-true-surface"
            style={{ width: barWidth, height: barHeight, opacity: alpha }}
          />
        ))}
      </div>
    </div>
  );
}
