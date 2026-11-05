import { fetchEventSource } from '@microsoft/fetch-event-source';
import { useEffect, useRef, useState } from 'react';
import { getAccessToken } from '../auth';

interface Options {
  enabled: boolean;
  /** Called for every {"orderId","status"} event. */
  onStatus: (orderId: string, status: string) => void;
  /** Called when the stream reconnects after a drop: events are not replayed, so refetch the list. */
  onReconnect: () => void;
}

/**
 * Live order status over server-sent events. fetch-event-source (not the browser's EventSource) is used because it
 * can send the Authorization header, and it reconnects on its own.
 */
export function useOrderStream({ enabled, onStatus, onReconnect }: Options) {
  const [connected, setConnected] = useState(false);
  // the latest callbacks without restarting the connection on every render
  const handlers = useRef({ onStatus, onReconnect });
  handlers.current = { onStatus, onReconnect };

  useEffect(() => {
    if (!enabled) return;
    const controller = new AbortController();
    let opened = false;

    void fetchEventSource('/api/orders/stream', {
      signal: controller.signal,
      openWhenHidden: true,
      headers: { Authorization: `Bearer ${getAccessToken() ?? ''}` },
      async onopen(res) {
        if (!res.ok) throw new Error(`stream ${res.status}`);
        setConnected(true);
        if (opened) handlers.current.onReconnect();   // second and later opens are reconnects
        opened = true;
      },
      onmessage(ev) {
        try {
          const { orderId, status } = JSON.parse(ev.data) as { orderId: string; status: string };
          handlers.current.onStatus(orderId, status);
        } catch {
          /* ignore a malformed event or a comment */
        }
      },
      onclose() {
        setConnected(false);   // the library reconnects
      },
      onerror() {
        setConnected(false);
        return 2000;   // retry in 2 s
      },
    });

    return () => controller.abort();
  }, [enabled]);

  return { connected };
}
