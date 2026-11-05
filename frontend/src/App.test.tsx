import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import * as api from './api';

// the tests look at what the user sees and does (labels, roles, text), not at component internals
vi.mock('./api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./api')>();
  return {
    ...actual,
    listOrders: vi.fn(),
    createOrder: vi.fn(),
    cancelOrder: vi.fn(),
    getStock: vi.fn(),
  };
});
vi.mock('./auth', () => ({
  initAuth: vi.fn().mockResolvedValue(true),
  getUserName: () => 'alice',
  getAccessToken: () => 'token',
  login: vi.fn(),
  logout: vi.fn(),
}));

// captures the callbacks so a test can play the part of the server pushing an event
let stream: { onStatus: (orderId: string, status: string) => void; onReconnect: () => void };
vi.mock('./hooks/useOrderStream', () => ({
  useOrderStream: (options: typeof stream) => {
    stream = options;
    return { connected: true };
  },
}));

const order = (id: string, status: api.Order['status'], sku = 'SKU-1', quantity = 2): api.Order => ({
  id,
  customerId: 'alice',
  status,
  items: [{ sku, quantity }],
});

beforeEach(() => {
  vi.clearAllMocks();   // call counts start at zero in every test
  vi.mocked(api.listOrders).mockResolvedValue([]);
  vi.mocked(api.getStock).mockResolvedValue({ sku: 'SKU-1', available: 10, reserved: 0 });
});

describe('App', () => {
  it('submits the typed SKU and quantity to createOrder', async () => {
    vi.mocked(api.createOrder).mockResolvedValue(order('1', 'PENDING', 'SKU-9', 3));
    const user = userEvent.setup();
    render(<App />);

    const sku = await screen.findByLabelText('SKU');
    await user.clear(sku);
    await user.type(sku, 'SKU-9');
    const quantity = screen.getByLabelText('Quantity');
    await user.clear(quantity);
    await user.type(quantity, '3');
    await user.click(screen.getByRole('button', { name: 'Create order' }));

    expect(api.createOrder).toHaveBeenCalledWith([{ sku: 'SKU-9', quantity: 3 }]);
  });

  it('lists the orders with their status', async () => {
    vi.mocked(api.listOrders).mockResolvedValue([order('1', 'CONFIRMED', 'SKU-1', 2), order('2', 'PENDING', 'SKU-2', 5)]);
    render(<App />);

    const list = await screen.findByRole('list', { name: 'orders' });
    expect(await screen.findByText('CONFIRMED')).toBeInTheDocument();
    expect(list).toHaveTextContent('2 x SKU-1');
    expect(list).toHaveTextContent('PENDING');
    expect(list).toHaveTextContent('5 x SKU-2');
  });

  it('shows a rejected createOrder in the alert paragraph', async () => {
    vi.mocked(api.createOrder).mockRejectedValue(new Error('409 Conflict'));
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole('button', { name: 'Create order' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('409 Conflict');
  });

  it('shows the permission message on a 403', async () => {
    vi.mocked(api.createOrder).mockRejectedValue(new api.ForbiddenError());
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole('button', { name: 'Create order' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('You are not allowed to see this order');
  });

  it('changes a row when a status event arrives', async () => {
    vi.mocked(api.listOrders).mockResolvedValue([order('abc', 'PENDING')]);
    render(<App />);
    expect(await screen.findByText('PENDING')).toBeInTheDocument();

    act(() => stream.onStatus('abc', 'CONFIRMED'));

    await waitFor(() => expect(screen.getByText('CONFIRMED')).toBeInTheDocument());
    expect(screen.queryByText('PENDING')).not.toBeInTheDocument();
  });

  it('refetches the list when the stream reconnects', async () => {
    render(<App />);
    await screen.findByRole('list', { name: 'orders' });
    const calls = vi.mocked(api.listOrders).mock.calls.length;

    act(() => stream.onReconnect());

    await waitFor(() => expect(vi.mocked(api.listOrders).mock.calls.length).toBeGreaterThan(calls));
  });

  it('keeps a pushed status when an older list arrives afterwards', async () => {
    vi.mocked(api.listOrders).mockResolvedValue([order('abc', 'PENDING')]);
    render(<App />);
    expect(await screen.findByText('PENDING')).toBeInTheDocument();

    act(() => stream.onStatus('abc', 'CONFIRMED'));
    // the list request that was already in flight still answers PENDING
    act(() => stream.onReconnect());

    await waitFor(() => expect(api.listOrders).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('CONFIRMED')).toBeInTheDocument();
    expect(screen.queryByText('PENDING')).not.toBeInTheDocument();
  });
});
