interface NoteSectionHeaderProps {
  title: string;
}

export function NoteSectionHeader({ title }: NoteSectionHeaderProps) {
  return (
    <div className="col-span-full flex items-center gap-3 py-1.5">
      <h3 className="text-section-label uppercase tracking-[1.2px] text-brand-muted/40">
        {title}
      </h3>
      <div className="h-px flex-1 bg-white/[0.03]" />
    </div>
  );
}
