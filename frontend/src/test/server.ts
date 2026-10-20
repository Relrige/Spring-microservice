import { setupServer } from 'msw/node';
import { handlers } from '../mocks/handlers';

/**
 * The MSW server of the Vitest run, with the mock backend's handlers (design D10).
 * Tests override single answers with `server.use(...)`.
 */
export const server = setupServer(...handlers);
