// Route tree (design D6): storefront, four role areas behind RequireRole, not-found.
import { Navigate, Outlet, type RouteObject } from 'react-router';
import { LoginPage } from '../features/auth/LoginPage';
import { CartPage } from '../features/cart/CartPage';
import { CartProvider } from '../features/cart/CartProvider';
import { CheckoutPage } from '../features/checkout/CheckoutPage';
import { PaymentPage } from '../features/checkout/PaymentPage';
import { OrderHistoryPage } from '../features/orders/OrderHistoryPage';
import { OrderPage } from '../features/orders/OrderPage';
import { StaffAccountsPage } from '../features/admin/StaffAccountsPage';
import { ProfilePage } from '../features/profile/ProfilePage';
import { QueuePage } from '../features/warehouse/QueuePage';
import { StockPage } from '../features/warehouse/StockPage';
import { GuestCartResolver } from '../features/cart/GuestCartResolver';
import { RegisterPage } from '../features/auth/RegisterPage';
import { ProductDetailsPage } from '../features/catalog/ProductDetailsPage';
import { ProductListPage } from '../features/catalog/ProductListPage';
import { CategoriesPage } from '../features/manage/CategoriesPage';
import { ProductCreatePage } from '../features/manage/ProductCreatePage';
import { ProductEditPage } from '../features/manage/ProductEditPage';
import { ProductListPage as ManagedProductListPage } from '../features/manage/ProductListPage';
import { RequireRole } from '../session/RequireRole';
import { SessionProvider } from '../session/SessionProvider';
import { AppLayout } from './AppLayout';
import { NotFoundPage } from './NotFoundPage';

export const routes: RouteObject[] = [
  {
    element: (
      <SessionProvider>
        <CartProvider>
          <AppLayout />
          <GuestCartResolver />
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
      // Customer pages (change customer-checkout-orders, design D3).
      {
        path: 'account',
        element: (
          <RequireRole allow="CUSTOMER">
            <Outlet />
          </RequireRole>
        ),
        children: [
          { index: true, element: <Navigate to="orders" replace /> },
          { path: 'orders', element: <OrderHistoryPage /> },
          { path: 'orders/:orderId', element: <OrderPage /> },
          { path: 'profile', element: <ProfilePage /> },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
      {
        path: 'checkout',
        element: (
          <RequireRole allow="CUSTOMER">
            <Outlet />
          </RequireRole>
        ),
        children: [
          { index: true, element: <CheckoutPage /> },
          { path: ':orderId', element: <PaymentPage /> },
        ],
      },
      // Catalog manager console (change catalog-manager-console, design D4).
      {
        path: 'manage',
        element: (
          <RequireRole allow="CATALOG_MANAGER">
            <Outlet />
          </RequireRole>
        ),
        children: [
          { index: true, element: <Navigate to="products" replace /> },
          { path: 'products', element: <ManagedProductListPage /> },
          { path: 'products/new', element: <ProductCreatePage /> },
          { path: 'products/:productId', element: <ProductEditPage /> },
          { path: 'categories', element: <CategoriesPage /> },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
      // Worker pages (change inventory-worker-console, design D3).
      {
        path: 'warehouse',
        element: (
          <RequireRole allow="INVENTORY_WORKER">
            <Outlet />
          </RequireRole>
        ),
        children: [
          { index: true, element: <StockPage /> },
          { path: 'queue', element: <QueuePage /> },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
      {
        // Admin page (change admin-staff-accounts, design D1).
        path: 'admin',
        element: (
          <RequireRole allow="ADMIN">
            <Outlet />
          </RequireRole>
        ),
        children: [
          { index: true, element: <StaffAccountsPage /> },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];
