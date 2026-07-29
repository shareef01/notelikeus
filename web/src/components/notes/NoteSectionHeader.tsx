interface NoteSectionHeaderProps {
  title: string;
}

export function NoteSectionHeader({ title }: NoteSectionHeaderProps) {
  return (
    <h3 className="col-span-full pb-1.5 pt-2 text-section-label uppercase text-brand-muted first:pt-0">
      {title}
    </h3>
  );
}
