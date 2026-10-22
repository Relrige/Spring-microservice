import { createMemoryRouter, RouterProvider } from 'react-router';
import { CartProvider, useCart } from '../features/cart/CartProvider';
import { SessionProvider, useSession } from '../session/SessionProvider';
import { renderWithProviders } from './render';
import { server } from './server';

/**
 * Renders only the session and the cart, and returns their current values
 * (`probe.cart`, `probe.session`) for tests of the cart state.
 */
export function renderCartProbe() {
  const probe = {} as { cart: ReturnType<typeof useCart>; session: ReturnType<typeof useSession> };
  function Probe() {
    probe.cart = useCart();
    probe.session = useSession();
    return null;
  }
  const router = createMemoryRouter([
    {
      path: '*',
      element: (
        <SessionProvider>
          <CartProvider>
            <Probe />
          </CartProvider>
        </SessionProvider>
      ),
    },
  ]);
  return { probe, ...renderWithProviders(<RouterProvider router={router} />) };
}

/** Records `METHOD /path` of every request to Customer Service's cart until the test ends. */
export function recordCartRequests(): string[] {
  const calls: string[] = [];
  server.events.on('request:start', ({ request }) => {
    const path = new URL(request.url).pathname.replace(/^\/api/, '');
    if (path.startsWith('/customers/cart')) calls.push(`${request.method} ${path}`);
  });
  return calls;
}
