import '@testing-library/jest-dom/vitest';
import { notifications } from '@mantine/notifications';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { resetDb } from '../mocks/db';
import { server } from './server';

// jsdom lacks these browser APIs, which Mantine uses. Media queries are evaluated
// against window.innerWidth (default 1024 px: the desktop layout); tests set
// window.innerWidth to check the phone layout.
function evaluateMedia(query: string): boolean {
  const width = window.innerWidth;
  const toPx = (value: string, unit: string) =>
    Number(value) * (unit === 'em' || unit === 'rem' ? 16 : 1);
  const min = /min-width:\s*([\d.]+)(px|em|rem)/.exec(query);
  const max = /max-width:\s*([\d.]+)(px|em|rem)/.exec(query);
  if (!min && !max) return false;
  if (min && width < toPx(min[1]!, min[2]!)) return false;
  if (max && width > toPx(max[1]!, max[2]!)) return false;
  return true;
}

window.matchMedia = (query: string) =>
  ({
    matches: evaluateMedia(query),
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }) as MediaQueryList;

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver ??= ResizeObserverStub;
window.HTMLElement.prototype.scrollIntoView ??= () => {};
// Mantine's autosizing Textarea listens for font loading.
if (!document.fonts) {
  Object.defineProperty(document, 'fonts', {
    value: { addEventListener: () => {}, removeEventListener: () => {} },
  });
}

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));

afterEach(() => {
  cleanup();
  // Mantine keeps notifications in a global store; one test's notice must not reach the next.
  notifications.clean();
  server.resetHandlers();
  server.events.removeAllListeners();
  resetDb();
  window.localStorage.clear();
  window.innerWidth = 1024;
});

afterAll(() => server.close());
