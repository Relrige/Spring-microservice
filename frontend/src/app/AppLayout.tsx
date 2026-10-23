import {
  Anchor,
  AppShell,
  Badge,
  Box,
  Burger,
  Button,
  Divider,
  Group,
  Stack,
  Text,
} from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { useEffect } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router';
import type { Role } from '../api/auth';
import { ColorSchemeToggle } from '../components/ColorSchemeToggle';
import { Wordmark } from '../components/Wordmark';
import { CartButton } from '../features/cart/CartButton';
import { SearchForm } from '../features/catalog/SearchForm';
import { useQueue } from '../features/warehouse/queries';
import { ROLE_LABELS } from '../session/roles';
import { useSession } from '../session/SessionProvider';
import { useIsDesktop } from './layout';

interface NavItem {
  to: string;
  label: string;
  /** A neutral count badge (no volt in staff screens). */
  count?: number;
}

/** Links per role: only pages of the user's own area that exist (spec app-navigation). */
const AREA_LINKS: Record<Role, NavItem[]> = {
  CUSTOMER: [
    { to: '/account/orders', label: 'Orders' },
    { to: '/account/profile', label: 'Profile' },
  ],
  CATALOG_MANAGER: [
    { to: '/manage/products', label: 'Products' },
    { to: '/manage/categories', label: 'Categories' },
  ],
  INVENTORY_WORKER: [
    { to: '/warehouse', label: 'Stock' },
    { to: '/warehouse/queue', label: 'Fulfillment queue' },
  ],
  ADMIN: [{ to: '/admin', label: 'Admin' }],
};

function isStorefront(pathname: string): boolean {
  return pathname === '/' || pathname.startsWith('/products/');
}

/** NavLink's rule: the link's path or a page below it; `/` only by itself. */
function isActiveLink(to: string, pathname: string): boolean {
  return to === '/' ? pathname === '/' : pathname === to || pathname.startsWith(`${to}/`);
}

function NavLinks({ items, onNavigate }: { items: NavItem[]; onNavigate?: () => void }) {
  const { pathname } = useLocation();
  return (
    <>
      {items.map((item) => {
        // Mantine reads a function `style` as a theme callback, so the active state is
        // computed here rather than taken from NavLink's style argument.
        // /warehouse (Stock) is exact so it is not active on /warehouse/queue.
        const exact = item.to === '/warehouse';
        const active = exact ? pathname === item.to : isActiveLink(item.to, pathname);
        return (
          <Anchor
            key={item.to}
            component={NavLink}
            to={item.to}
            end={item.to === '/' || exact}
            onClick={onNavigate}
            underline="never"
            fw={600}
            px="sm"
            py={9}
            style={{
              borderRadius: 6,
              display: 'inline-flex',
              alignItems: 'center',
              whiteSpace: 'nowrap',
              color: active ? 'var(--vs-primary)' : 'var(--vs-ink)',
              background: active ? 'var(--vs-primary-soft)' : undefined,
            }}
          >
            {item.label}
            {item.count ? (
              <Badge size="sm" variant="light" color="gray" ml={6} circle>
                {item.count}
              </Badge>
            ) : null}
          </Anchor>
        );
      })}
    </>
  );
}

export function AppLayout() {
  const { session, logout } = useSession();
  const [opened, { toggle, close }] = useDisclosure(false);
  const isDesktop = useIsDesktop();
  const { pathname } = useLocation();

  useEffect(() => {
    if (isDesktop) close();
  }, [isDesktop, close]);

  const isWorker = session?.role === 'INVENTORY_WORKER';
  const queue = useQueue({ enabled: isWorker, refetchInterval: 30_000 });
  const waiting = isWorker ? queue.data?.orders.length : undefined;
  const items: NavItem[] = [
    { to: '/', label: 'Catalog' },
    ...(session ? AREA_LINKS[session.role] : []).map((item) =>
      item.to === '/warehouse/queue' ? { ...item, count: waiting } : item,
    ),
  ];
  const who = session
    ? [session.email, ROLE_LABELS[session.role]].filter(Boolean).join(' · ')
    : null;

  return (
    <AppShell
      header={{ height: { base: 56, sm: 64 } }}
      navbar={{ width: 280, breakpoint: 'sm', collapsed: { desktop: true, mobile: !opened } }}
      padding={0}
    >
      <AppShell.Header
        style={{ background: 'var(--vs-surface-raised)', borderColor: 'var(--vs-line)' }}
      >
        <Group
          h="100%"
          maw="var(--vs-content-max)"
          mx="auto"
          px={{ base: 'md', sm: 'lg' }}
          gap="lg"
          wrap="nowrap"
        >
          {!isDesktop && (
            <Burger
              opened={opened}
              onClick={toggle}
              size="sm"
              aria-label={opened ? 'Close menu' : 'Open menu'}
            />
          )}
          <Wordmark size={isDesktop ? 20 : 18} />
          {isDesktop && (
            <>
              <Group gap={4} component="nav" aria-label="Main" wrap="nowrap">
                <NavLinks items={items} />
              </Group>
              {isStorefront(pathname) ? <SearchForm /> : <Box style={{ flex: 1 }} />}
              <Group gap="sm" wrap="nowrap">
                <CartButton />
                {session ? (
                  <>
                    <Text size="sm" c="dimmed" data-testid="current-user">
                      {who}
                    </Text>
                    <Button variant="default" onClick={logout}>
                      Log out
                    </Button>
                  </>
                ) : (
                  <>
                    <Anchor component={Link} to="/login" fw={600} c="var(--vs-ink)">
                      Log in
                    </Anchor>
                    <Button component={Link} to="/register" variant="outline">
                      Register
                    </Button>
                  </>
                )}
                <ColorSchemeToggle />
              </Group>
            </>
          )}
          {!isDesktop && (
            <Group ml="auto" gap="xs" wrap="nowrap">
              <CartButton />
              <ColorSchemeToggle />
            </Group>
          )}
        </Group>
      </AppShell.Header>

      {!isDesktop && opened && (
        <AppShell.Navbar
          p="md"
          style={{ background: 'var(--vs-surface-raised)', borderColor: 'var(--vs-line)' }}
        >
          <Stack gap="xs" component="nav" aria-label="Main">
            <NavLinks items={items} onNavigate={close} />
            <Divider my="sm" color="var(--vs-line)" />
            {session ? (
              <>
                <Text size="sm" c="dimmed" data-testid="current-user">
                  {who}
                </Text>
                <Button
                  variant="default"
                  onClick={() => {
                    close();
                    logout();
                  }}
                >
                  Log out
                </Button>
              </>
            ) : (
              <>
                <Button component={Link} to="/login" onClick={close}>
                  Log in
                </Button>
                <Button component={Link} to="/register" variant="outline" onClick={close}>
                  Register
                </Button>
              </>
            )}
          </Stack>
        </AppShell.Navbar>
      )}

      <AppShell.Main>
        <Outlet />
      </AppShell.Main>
    </AppShell>
  );
}
