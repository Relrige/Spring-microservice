import { render, type RenderOptions } from '@testing-library/react';
import type { ReactElement } from 'react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import type { Role } from '../api/auth';
import { Providers } from '../app/Providers';
import { createQueryClient } from '../app/query-client';
import { routes } from '../app/routes';
import { db } from '../mocks/db';
import { createMockToken } from '../mocks/tokens';
import { SESSION_STORAGE_KEY } from '../session/SessionProvider';

/** Renders inside the app providers (Mantine in test mode, a fresh query client). */
export function renderWithProviders(ui: ReactElement, options?: RenderOptions) {
  const queryClient = createQueryClient({ retryDelay: 0 });
  return {
    queryClient,
    ...render(
      <Providers env="test" queryClient={queryClient}>
        {ui}
      </Providers>,
      options,
    ),
  };
}

/** Renders the whole app at a URL with an in-memory router. */
export function renderApp(url: string) {
  const router = createMemoryRouter(routes, { initialEntries: [url] });
  const result = renderWithProviders(<RouterProvider router={router} />);
  return { ...result, router };
}

/** Stores a mock session for a demo account of the role, as if it had logged in before. */
export function loginAs(role: Role, options: { lifetimeSeconds?: number } = {}): string {
  const user = db.users.find((u) => u.role === role)!;
  const token = createMockToken(user, options);
  window.localStorage.setItem(SESSION_STORAGE_KEY, token);
  return token;
}

/** The current in-app location of a rendered router. */
export function currentUrl(router: { state: { location: { pathname: string; search: string } } }) {
  const { pathname, search } = router.state.location;
  return `${pathname}${search}`;
}
