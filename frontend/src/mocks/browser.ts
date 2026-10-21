import { setupWorker } from 'msw/browser';
import { handlers } from './handlers';

/** The mock backend in the browser (`npm run dev:mock`, design D10). */
export async function startMockBackend(): Promise<void> {
  const worker = setupWorker(...handlers);
  await worker.start({
    // Only /api requests are mocked; Vite's own files go through.
    onUnhandledFrame: 'bypass',
    quiet: true,
  });
  console.info('[VoltStore] Mock backend active: /api requests are answered by MSW.');
}
