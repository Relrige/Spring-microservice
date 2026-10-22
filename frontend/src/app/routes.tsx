// Route tree (design D6): storefront, four role areas behind RequireRole, not-found.
import type { RouteObject } from 'react-router';
import { LoginPage } from '../features/auth/LoginPage';
import { CartPage } from '../features/cart/CartPage';
import { CartProvider } from '../features/cart/CartProvider';
import { RegisterPage } from '../features/auth/RegisterPage';
import { ProductDetailsPage } from '../features/catalog/ProductDetailsPage';
import { ProductListPage } from '../features/catalog/ProductListPage';
import { RequireRole } from '../session/RequireRole';
import { SessionProvider } from '../session/SessionProvider';
import { AppLayout } from './AppLayout';
import { AreaPlaceholderPage } from './AreaPlaceholderPage';
import { NotFoundPage } from './NotFoundPage';

export const routes: RouteObject[] = [
  {
    element: (
      <SessionProvider>
        <CartProvider>
          <AppLayout />
        </CartProvider>
      </SessionProvider>
    ),
    children: [
      { index: true, element: <ProductListPage /> },
      { path: 'products/:productId', element: <ProductDetailsPage /> },
      { path: 'login', element: <LoginPage /> },
      { path: 'register', element: <RegisterPage /> },
      // Open to visitors and customers; CartPage denies staff (change customer-cart, design D7).
      { path: 'cart', element: <CartPage /> },
      {
        path: 'account/*',
        element: (
          <RequireRole allow="CUSTOMER">
            <AreaPlaceholderPage
              title="Your account"
              arrives="Your orders and your profile arrive in iteration 3 (customer-checkout-orders)."
            />
          </RequireRole>
        ),
      },
      {
        path: 'manage/*',
        element: (
          <RequireRole allow="CATALOG_MANAGER">
            <AreaPlaceholderPage
              title="Catalog console"
              arrives="Product and category management arrives in iteration 4 (catalog-manager-console)."
            />
          </RequireRole>
        ),
      },
      {
        path: 'warehouse/*',
        element: (
          <RequireRole allow="INVENTORY_WORKER">
            <AreaPlaceholderPage
              title="Warehouse"
              arrives="Stock, packaging and the fulfillment queue arrive in iteration 5 (inventory-worker-console)."
            />
          </RequireRole>
        ),
      },
      {
        path: 'admin/*',
        element: (
          <RequireRole allow="ADMIN">
            <AreaPlaceholderPage
              title="Administration"
              arrives="Creating staff and admin accounts arrives in iteration 6 (admin-staff-accounts)."
            />
          </RequireRole>
        ),
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];
