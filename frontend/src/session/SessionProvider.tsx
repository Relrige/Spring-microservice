// Session state (spec user-session, design D5): localStorage persistence, expiry
// timer, cross-tab sync, logout clearing the query cache, expired-session redirect.
import { useQueryClient } from '@tanstack/react-query';
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { useLocation, useNavigate } from 'react-router';
import { configureHttp } from '../api/http';
import { isProtectedPath, loginUrl } from './roles';
import { decodeToken, type Session } from './token';

export const SESSION_STORAGE_KEY = 'voltstore.session';

/** Longest delay setTimeout accepts; longer expiries are re-armed. */
const MAX_TIMER = 2_147_483_647;

export class InvalidTokenError extends Error {
  constructor() {
    super('The login answer could not be read.');
    this.name = 'InvalidTokenError';
  }
}

/**
 * How the current session started (change customer-cart, design D6): `login` in
 * this tab (login or registration), `stored` when the app loaded, `other-tab`
 * when another tab logged in. Only `login` and `stored` carry a guest cart into the account.
 */
export type SessionSource = 'login' | 'stored' | 'other-tab';

interface SessionContextValue {
  session: Session | null;
  source: SessionSource;
  /** True while logout or expiry navigates away from the current page. */
  ending: boolean;
  /** Starts a session; throws InvalidTokenError for an unreadable token or unknown role. */
  startSession: (token: string) => Session;
  logout: () => void;
}

const SessionContext = createContext<SessionContextValue | null>(null);

function readStoredSession(): Session | null {
  let token: string | null = null;
  try {
    token = window.localStorage.getItem(SESSION_STORAGE_KEY);
  } catch {
    return null;
  }
  if (!token) return null;
  const result = decodeToken(token);
  if (result.ok) return result.session;
  // Spec user-session, Expired token on load: discarded, the user is anonymous.
  try {
    window.localStorage.removeItem(SESSION_STORAGE_KEY);
  } catch {
    // storage unavailable: nothing to remove
  }
  return null;
}

function writeStoredToken(token: string | null): void {
  try {
    if (token) window.localStorage.setItem(SESSION_STORAGE_KEY, token);
    else window.localStorage.removeItem(SESSION_STORAGE_KEY);
  } catch {
    // storage unavailable (private mode): the session lives in memory only
  }
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(readStoredSession);
  const [source, setSource] = useState<SessionSource>('stored');
  const sessionRef = useRef(session);
  useLayoutEffect(() => {
    sessionRef.current = session;
  }, [session]);

  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const location = useLocation();
  const locationRef = useRef(location);
  const [endingAt, setEndingAt] = useState<string | null>(null);
  useLayoutEffect(() => {
    locationRef.current = location;
  }, [location]);

  const clear = useCallback(() => {
    sessionRef.current = null;
    setSession(null);
    queryClient.clear();
  }, [queryClient]);

  /**
   * Ends the session because the token expired or was rejected. `always`
   * (a 401) goes to the login page; `if-protected` (idle expiry) only leaves
   * pages that need a login.
   */
  const expire = useCallback(
    (redirect: 'always' | 'if-protected') => {
      if (!sessionRef.current) return;
      writeStoredToken(null);
      const { pathname, search, hash, key } = locationRef.current;
      const here = `${pathname}${search}${hash}`;
      if (redirect === 'always' || isProtectedPath(pathname)) {
        setEndingAt(key);
        clear();
        navigate(loginUrl(here, true), { replace: true });
      } else {
        clear();
      }
    },
    [clear, navigate],
  );

  // The API layer reads the token from here and reports 401s on token-carrying requests.
  // A layout effect, so it is in place before queries of child components (the cart) fetch.
  useLayoutEffect(() => {
    configureHttp({
      getToken: () => sessionRef.current?.token ?? null,
      onSessionExpired: () => expire('always'),
    });
  }, [expire]);

  // Expiry while idle (spec user-session, Session expiry).
  useEffect(() => {
    if (!session) return;
    let timer: ReturnType<typeof setTimeout>;
    const arm = () => {
      const remaining = session.expiresAt - Date.now();
      if (remaining <= 0) {
        expire('if-protected');
        return;
      }
      timer = setTimeout(arm, Math.min(remaining, MAX_TIMER));
    };
    arm();
    return () => clearTimeout(timer);
  }, [session, expire]);

  // Cross-tab sync: login or logout in another tab (spec user-session, Logout in another tab).
  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      if (event.key !== SESSION_STORAGE_KEY && event.key !== null) return;
      const next = readStoredSession();
      if (next?.token === sessionRef.current?.token) return;
      if (!next) clear();
      else {
        queryClient.clear();
        sessionRef.current = next;
        setSource('other-tab');
        setSession(next);
      }
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, [clear, queryClient]);

  const startSession = useCallback(
    (token: string) => {
      const result = decodeToken(token);
      if (!result.ok) throw new InvalidTokenError();
      writeStoredToken(token);
      queryClient.clear();
      sessionRef.current = result.session;
      setSource('login');
      setSession(result.session);
      return result.session;
    },
    [queryClient],
  );

  const logout = useCallback(() => {
    writeStoredToken(null);
    setEndingAt(locationRef.current.key);
    clear();
    navigate('/');
  }, [clear, navigate]);

  // While logout or expiry leaves a page, guards must not add their own login redirect.
  const ending = endingAt !== null && endingAt === location.key;

  const value = useMemo(
    () => ({ session, source, ending, startSession, logout }),
    [session, source, ending, startSession, logout],
  );
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
  const value = useContext(SessionContext);
  if (!value) throw new Error('useSession must be used inside SessionProvider');
  return value;
}
