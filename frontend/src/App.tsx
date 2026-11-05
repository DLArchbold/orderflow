import { FormEvent, useEffect, useState } from 'react';
import { Order, StockInfo, cancelOrder, createOrder, getStock, listOrders } from './api';

export function App() {
  const [orders, setOrders] = useState<Order[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [sku, setSku] = useState('SKU-1');
  const [quantity, setQuantity] = useState(1);

  const [stock, setStock] = useState<StockInfo | null>(null);

  // stock of the typed SKU; a failed lookup just hides the line, ordering does not depend on it
  useEffect(() => {
    let cancelled = false;
    getStock(sku)
      .then((s) => !cancelled && setStock(s))
      .catch(() => !cancelled && setStock(null));
    return () => {
      cancelled = true;
    };
  }, [sku, orders]);

  const refresh = () =>
    listOrders()
      .then((data) => {
        setOrders(data);
        setError(null);
      })
      .catch((e: Error) => setError(e.message));

  useEffect(() => {
    void refresh();
  }, []);

  const run = (action: Promise<unknown>) =>
    action.then(refresh).catch((e: Error) => setError(e.message));

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    void run(createOrder([{ sku, quantity }]));
  };

  return (
    <main className="app">
      <h1>OrderFlow</h1>

      <form onSubmit={onSubmit} className="form">
        <input value={sku} onChange={(e) => setSku(e.target.value)} placeholder="SKU" required />
        <input type="number" min={1} value={quantity} onChange={(e) => setQuantity(Number(e.target.value))} />
        <button type="submit">Create order</button>
      </form>
      {stock && (
        <p aria-label="stock">
          In stock for {stock.sku}: {stock.available === null ? 'unknown' : stock.available}
        </p>
      )}

      {error && <p role="alert" className="error">Error: {error}</p>}

      <ul aria-label="orders">
        {orders.map((o) => (
          <li key={o.id}>
            <strong>{o.status}</strong> {o.items.map((i) => `${i.quantity} x ${i.sku}`).join(', ')}
            {o.status === 'PENDING' && <button onClick={() => void run(cancelOrder(o.id))}>Cancel</button>}
          </li>
        ))}
      </ul>
    </main>
  );
}
