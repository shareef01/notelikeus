interface NoteSectionHeaderProps {
  title: string;
}

export function NoteSectionHeader({ title }: NoteSectionHeaderProps) {
  return (
    <h3 className="col-span-full pb-2 text-section-label uppercase tracking-wider text-brand-muted">
      {title}
    </h3>
  );
}
