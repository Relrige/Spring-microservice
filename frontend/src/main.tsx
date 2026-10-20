import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './app/App';

async function start() {
  if (import.meta.env.VITE_API_MOCK === 'true') {
    try {
      const { startMockBackend } = await import('./mocks/browser');
      await startMockBackend();
    } catch (error) {
      // Happens when the page is left while the worker registers; the next load retries.
      console.warn('[VoltStore] Mock backend could not start:', error);
    }
  }
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}

void start();
