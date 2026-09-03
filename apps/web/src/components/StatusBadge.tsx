import type { OrderStatus } from '../types';
import { statusPresentation } from '../features/orders/status';

interface Props {
  status: OrderStatus;
  title?: string | undefined;
}

export function StatusBadge({ status, title }: Props) {
  const { label, tone, description } = statusPresentation(status);
  return (
    <span className={`badge badge--${tone}`} title={title ?? description}>
      {label}
    </span>
  );
}
