import { useEffect, useState } from 'react';

export type OrderStatuses = Record<string, string>;

/** Subscribes to server-sent events of the form {"orderId": "...", "status": "..."}. */
export function useOrderStream(url: string) {
  const [statuses, setStatuses] = useState<OrderStatuses>({});
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    if (typeof EventSource === 'undefined') return;
    const es = new EventSource(url);
    es.onopen = () => setConnected(true);
    es.onerror = () => setConnected(false); // EventSource retries on its own
    es.onmessage = (e) => {
      try {
        const { orderId, status } = JSON.parse(e.data) as { orderId: string; status: string };
        setStatuses((prev) => ({ ...prev, [orderId]: status }));
      } catch {
        /* ignore malformed event */
      }
    };
    return () => es.close();
  }, [url]);

  return { statuses, connected };
}
