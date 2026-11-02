export type OrderStatus = 'PENDING' | 'CONFIRMED' | 'REJECTED' | 'CANCELLED';

export interface OrderItem {
  sku: string;
  quantity: number;
}

export interface Order {
  id: string;
  customerId: string;
  status: OrderStatus;
  items: OrderItem[];
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  });
  if (!res.ok) {
    throw new Error(`${res.status} ${res.statusText}`);
  }
  return (await res.json()) as T;
}

export const listOrders = () => request<Order[]>('/api/orders');

export const createOrder = (customerId: string, items: OrderItem[]) =>
  request<Order>('/api/orders', {
    method: 'POST',
    body: JSON.stringify({ customerId, items }),
  });

export const confirmOrder = (id: string) =>
  request<Order>(`/api/orders/${id}/confirm`, { method: 'POST' });

export const cancelOrder = (id: string) =>
  request<Order>(`/api/orders/${id}/cancel`, { method: 'POST' });
