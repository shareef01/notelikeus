interface NoteSectionHeaderProps {
  title: string;
}

export function NoteSectionHeader({ title }: NoteSectionHeaderProps) {
  return (
    <h3 className="col-span-full py-2 text-section-label uppercase text-brand-muted">
      {title}
    </h3>
  );
}
