interface Props {
  label?: string | undefined;
}

export function Loading({ label = 'Loading…' }: Props) {
  return (
    <div className="loading" role="status">
      <span className="loading__spinner" aria-hidden="true" />
      <span>{label}</span>
    </div>
  );
}

export function EmptyState({ children }: { children: React.ReactNode }) {
  return <p className="empty-state">{children}</p>;
}
