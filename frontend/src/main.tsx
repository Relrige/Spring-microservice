import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './app/App';

async function start() {
  if (import.meta.env.VITE_API_MOCK === 'true') {
    const { startMockBackend } = await import('./mocks/browser');
    await startMockBackend();
  }
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}

void start();
