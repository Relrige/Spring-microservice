import { setupServer } from 'msw/node';

/** The MSW server of the Vitest run; tests add handlers with `server.use(...)`. */
export const server = setupServer();
