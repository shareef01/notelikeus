export function BrandMark({ size = 40, className = '' }: { size?: number; className?: string }) {
  const barWidth = Math.max(2, Math.round(size * 0.075));
  const barHeight = Math.round(size * 0.5);
  const gap = Math.round(size * 0.08);
  return (
    <div
      className={`inline-flex items-center justify-center rounded-full bg-brand-primary ${className}`}
      style={{ width: size, height: size }}
      aria-hidden
    >
      <div className="flex items-center" style={{ gap }}>
        {[0, 1, 2].map((i) => (
          <span
            key={i}
            className="rounded-full bg-true-black"
            style={{ width: barWidth, height: barHeight }}
          />
        ))}
      </div>
    </div>
  );
}
