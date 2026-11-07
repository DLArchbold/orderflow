import { getAccessToken, login } from './auth';

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

export class ForbiddenError extends Error {
  constructor() {
    super('You are not allowed to see this order');
  }
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const token = getAccessToken();
  const res = await fetch(url, {
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    ...init,
  });
  if (res.status === 401) {
    void login();   // not signed in (or the token expired): go through Keycloak
    throw new Error('Please sign in');
  }
  if (res.status === 403) {
    throw new ForbiddenError();
  }
  if (!res.ok) {
    throw new Error(`${res.status} ${res.statusText}`);
  }
  return (await res.json()) as T;
}

export const listOrders = () => request<Order[]>('/api/orders');

// the owner is taken from the access token on the server, never sent from here
export const createOrder = (items: OrderItem[]) =>
  request<Order>('/api/orders', {
    method: 'POST',
    body: JSON.stringify({ items }),
  });

export const cancelOrder = (id: string) =>
  request<Order>(`/api/orders/${id}/cancel`, { method: 'POST' });

export interface StockInfo {
  sku: string;
  available: number | null;
  reserved: number | null;
}

export const getStock = (sku: string) => request<StockInfo>(`/api/stock/${encodeURIComponent(sku)}`);
