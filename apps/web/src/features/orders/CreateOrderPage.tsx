import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { listInventory } from '../../api/inventory';
import { createOrder } from '../../api/orders';
import { queryKeys } from '../../api/queryKeys';
import { ErrorBanner } from '../../components/ErrorBanner';
import { Loading } from '../../components/Loading';
import type { CreateOrderResponse, InventoryItem } from '../../types';
import { formatMoney } from './format';

interface Line {
  itemId: string;
  quantity: number;
}

const DEFAULT_CUSTOMER = 'customer-1';

export function CreateOrderPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [customerId, setCustomerId] = useState(DEFAULT_CUSTOMER);
  const [lines, setLines] = useState<Line[]>([]);

  /**
   * One idempotency key per intended order, not per HTTP attempt.
   *
   * If the first POST times out, the user's retry must carry the *same* key so the backend
   * recognises it as the same order instead of reserving stock twice. The key is therefore
   * minted once and only discarded when the order succeeds or the basket changes — at which
   * point it is a genuinely different order.
   */
  const idempotencyKey = useRef<string | null>(null);

  const inventory = useQuery({
    queryKey: queryKeys.inventory,
    queryFn: () => listInventory(50),
  });

  const catalog = inventory.data ?? [];
  const catalogById = new Map(catalog.map((item) => [item.itemId, item]));

  function mutateLines(next: Line[]) {
    // The basket changed, so this is a different order than any in-flight attempt described.
    idempotencyKey.current = null;
    setLines(next);
  }

  const submit = useMutation({
    mutationFn: () => {
      idempotencyKey.current ??= crypto.randomUUID();
      return createOrder({ customerId: customerId.trim(), items: lines }, idempotencyKey.current);
    },
    onSuccess: (response: CreateOrderResponse) => {
      idempotencyKey.current = null;
      void queryClient.invalidateQueries({ queryKey: queryKeys.orders });
      void queryClient.invalidateQueries({ queryKey: queryKeys.inventory });
      navigate(`/orders/${response.orderId}`, {
        state: { replayed: response.replayed },
      });
    },
  });

  const total = lines.reduce((sum, line) => {
    const unitPrice = catalogById.get(line.itemId)?.unitPrice ?? 0;
    return sum + unitPrice * line.quantity;
  }, 0);

  const canSubmit = customerId.trim() !== '' && lines.length > 0 && !submit.isPending;

  return (
    <section className="page">
      <header className="page__header">
        <div>
          <h1>New order</h1>
          <p className="page__subtitle">
            Stock is reserved transactionally at checkout — this page never claims a reservation
            before the server confirms one.
          </p>
        </div>
      </header>

      {submit.isError && (
        <ErrorBanner error={submit.error} onRetry={canSubmit ? () => submit.mutate() : undefined} />
      )}

      <div className="layout-split">
        <div className="panel">
          <h2 className="panel__title">Basket</h2>

          <label className="field">
            <span className="field__label">Customer</span>
            <input
              className="input"
              value={customerId}
              onChange={(event) => setCustomerId(event.target.value)}
              placeholder="customer-1"
            />
          </label>

          {lines.length === 0 ? (
            <p className="empty-state">Add an item from the catalog to get started.</p>
          ) : (
            <table className="table">
              <thead>
                <tr>
                  <th>Item</th>
                  <th className="table__number">Qty</th>
                  <th className="table__number">Line total</th>
                  <th aria-label="Remove" />
                </tr>
              </thead>
              <tbody>
                {lines.map((line, index) => {
                  const item = catalogById.get(line.itemId);
                  const available = item?.availableQuantity ?? 0;
                  return (
                    <tr key={line.itemId}>
                      <td>
                        {item?.itemName ?? line.itemId}
                        <small className="table__meta">
                          {formatMoney(item?.unitPrice)} · {available} available
                        </small>
                      </td>
                      <td className="table__number">
                        <input
                          className="input input--compact"
                          type="number"
                          min={1}
                          value={line.quantity}
                          aria-label={`Quantity for ${line.itemId}`}
                          onChange={(event) => {
                            const quantity = Number(event.target.value);
                            const next = [...lines];
                            next[index] = { ...line, quantity: Number.isNaN(quantity) ? 1 : quantity };
                            mutateLines(next);
                          }}
                        />
                      </td>
                      <td className="table__number">
                        {formatMoney((item?.unitPrice ?? 0) * line.quantity)}
                      </td>
                      <td>
                        <button
                          type="button"
                          className="button button--ghost"
                          onClick={() => mutateLines(lines.filter((_, i) => i !== index))}
                        >
                          Remove
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}

          <div className="panel__footer">
            <span className="total">
              Total <strong>{formatMoney(total)}</strong>
            </span>
            <button
              type="button"
              className="button button--primary"
              disabled={!canSubmit}
              onClick={() => submit.mutate()}
            >
              {submit.isPending ? 'Reserving stock…' : 'Place order'}
            </button>
          </div>
        </div>

        <div className="panel">
          <h2 className="panel__title">Catalog</h2>
          {inventory.isError && (
            <ErrorBanner error={inventory.error} onRetry={() => void inventory.refetch()} />
          )}
          {inventory.isPending ? (
            <Loading label="Loading catalog…" />
          ) : catalog.length === 0 ? (
            <p className="empty-state">
              No inventory seeded. Run <code>make load-test-seed</code> or POST to{' '}
              <code>/api/v1/inventory/seed</code>.
            </p>
          ) : (
            <ul className="catalog">
              {catalog.map((item) => (
                <CatalogRow
                  key={item.itemId}
                  item={item}
                  inBasket={lines.some((line) => line.itemId === item.itemId)}
                  onAdd={() => mutateLines([...lines, { itemId: item.itemId, quantity: 1 }])}
                />
              ))}
            </ul>
          )}
        </div>
      </div>
    </section>
  );
}

interface CatalogRowProps {
  item: InventoryItem;
  inBasket: boolean;
  onAdd: () => void;
}

function CatalogRow({ item, inBasket, onAdd }: CatalogRowProps) {
  const soldOut = item.availableQuantity <= 0;
  return (
    <li className="catalog__row">
      <div>
        <strong>{item.itemName ?? item.itemId}</strong>
        <small className="catalog__meta">
          {formatMoney(item.unitPrice)} · {item.availableQuantity} available
          {item.reservedQuantity > 0 && ` · ${item.reservedQuantity} reserved`}
        </small>
      </div>
      <button type="button" className="button" disabled={inBasket || soldOut} onClick={onAdd}>
        {inBasket ? 'In basket' : soldOut ? 'Sold out' : 'Add'}
      </button>
    </li>
  );
}
