interface NoteSectionHeaderProps {
  title: string;
}

export function NoteSectionHeader({ title }: NoteSectionHeaderProps) {
  return (
    <h3 className="col-span-full pb-2 pt-3 text-section-label uppercase tracking-wider text-brand-muted first:pt-0">
      {title}
    </h3>
  );
}
