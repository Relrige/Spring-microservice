import { act, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { db } from '../../mocks/db';
import { problem } from '../../mocks/handlers';
import { recordCartRequests, renderCartProbe } from '../../test/cart';
import { loginAs } from '../../test/render';
import { server } from '../../test/server';
import { LINE_GONE } from './CartProvider';
import { readGuestCart, writeGuestCart } from './guest-cart';

const active = () => db.products.filter((p) => p.status === 'ACTIVE');
const customerCart = () => db.carts.get(db.users.find((u) => u.role === 'CUSTOMER')!.id)!;

describe('Cart owner by role', () => {
  it('Visitor adds a product: no request to Customer Service, the product is in the cart', async () => {
    const calls = recordCartRequests();
    const { probe } = renderCartProbe();
    const productId = active()[0]!.productId;
    await act(() => probe.cart.add(productId, 2));
    expect(probe.cart.owner).toBe('guest');
    expect(probe.cart.lines).toEqual([{ productId, quantity: 2 }]);
    expect(probe.cart.count).toBe(2);
    expect(calls).toEqual([]);
  });

  it('Customer adds a product: POST /customers/cart/items and the cart from the answer', async () => {
    loginAs('CUSTOMER');
    const calls = recordCartRequests();
    const { probe } = renderCartProbe();
    await waitFor(() => expect(probe.cart.status).toBe('ready'));
    const productId = active()[0]!.productId;
    let change;
    await act(async () => {
      change = await probe.cart.add(productId, 1);
    });
    expect(change).toEqual({ ok: true });
    expect(calls).toContain('POST /customers/cart/items');
    expect(probe.cart.lines).toEqual(customerCart());
    expect(probe.cart.lines).toHaveLength(4);
  });

  it('Staff login keeps the guest cart: no cart request while logged in, unchanged after logout', async () => {
    const guest = [{ productId: active()[0]!.productId, quantity: 3 }];
    writeGuestCart(guest);
    loginAs('INVENTORY_WORKER');
    const calls = recordCartRequests();
    const { probe } = renderCartProbe();
    expect(probe.cart.owner).toBe('none');
    expect(probe.cart.lines).toEqual([]);
    act(() => probe.session.logout());
    await waitFor(() => expect(probe.cart.owner).toBe('guest'));
    expect(probe.cart.lines).toEqual(guest);
    expect(readGuestCart()).toEqual(guest);
    expect(calls).toEqual([]);
  });
});

describe('Server cart follows the server', () => {
  async function customerProbe() {
    loginAs('CUSTOMER');
    const rendered = renderCartProbe();
    await waitFor(() => expect(rendered.probe.cart.status).toBe('ready'));
    return rendered.probe;
  }

  it('Server rejects a change: the limit message, and the cart is read again', async () => {
    const probe = await customerProbe();
    const calls = recordCartRequests();
    const line = customerCart()[0]!;
    let change;
    await act(async () => {
      change = await probe.cart.add(line.productId, 10);
    });
    expect(change).toEqual({
      ok: false,
      message: 'You can have up to 10 of each product in your cart.',
    });
    await waitFor(() =>
      expect(calls).toEqual(['POST /customers/cart/items', 'GET /customers/cart']),
    );
  });

  it('Line removed on another device: PATCH 404 gives its message and reloads the cart', async () => {
    const probe = await customerProbe();
    const calls = recordCartRequests();
    const line = customerCart()[0]!;
    db.carts.set(
      db.users.find((u) => u.role === 'CUSTOMER')!.id,
      customerCart().filter((l) => l.productId !== line.productId),
    );
    let change;
    await act(async () => {
      change = await probe.cart.setQuantity(line.productId, 2);
    });
    expect(change).toEqual({ ok: false, message: LINE_GONE });
    await waitFor(() => expect(probe.cart.lines).toHaveLength(2));
    expect(calls).toContain('GET /customers/cart');
  });

  it('Customer Service unavailable: the cart is kept, the message shown, nothing resent', async () => {
    const probe = await customerProbe();
    const before = probe.cart.lines;
    server.use(
      http.post('*/api/customers/cart/items', () => new HttpResponse(null, { status: 503 })),
    );
    const calls = recordCartRequests();
    let change;
    await act(async () => {
      change = await probe.cart.add(active()[0]!.productId, 1);
    });
    expect(change).toEqual({
      ok: false,
      message: 'This service is temporarily unavailable. Please try again in a moment.',
    });
    expect(probe.cart.lines).toBe(before);
    expect(calls).toEqual(['POST /customers/cart/items']);
  });

  it('marks a line busy while its change waits for the answer', async () => {
    const probe = await customerProbe();
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    server.use(
      http.delete('*/api/customers/cart/items/:id', async () => {
        await gate;
        return problem(404, 'never');
      }),
    );
    const line = probe.cart.lines[0]!;
    let done!: Promise<unknown>;
    act(() => {
      done = probe.cart.remove(line.productId);
    });
    await waitFor(() => expect(probe.cart.busy(line.productId)).toBe(true));
    expect(probe.cart.busy(probe.cart.lines[1]!.productId)).toBe(false);
    release();
    await act(() => done);
    expect(probe.cart.busy(line.productId)).toBe(false);
  });
});
