interface NoteSectionHeaderProps {
  title: string;
}

export function NoteSectionHeader({ title }: NoteSectionHeaderProps) {
  return (
    <h3 className="col-span-full px-1 pb-1 pt-2 text-section-label uppercase text-brand-muted/65">
      {title}
    </h3>
  );
}
