import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';
import type { Role } from '../api/auth';
import { AccessDeniedPage } from '../app/AccessDeniedPage';
import { loginUrl } from './roles';
import { useSession } from './SessionProvider';

/** Guards a role area (spec app-navigation, Protected pages need a login). */
export function RequireRole({ allow, children }: { allow: Role; children: ReactNode }) {
  const { session, ending } = useSession();
  const location = useLocation();
  if (!session) {
    if (ending) return null;
    const here = `${location.pathname}${location.search}${location.hash}`;
    return <Navigate to={loginUrl(here)} replace />;
  }
  if (session.role !== allow) return <AccessDeniedPage />;
  return <>{children}</>;
}
