import { defineConfig, devices } from '@playwright/test';

// Design D11: project `mock` runs against `npm run dev:mock` (no Docker);
// project `live` runs the same journeys against the Compose stack through
// `npm run dev` (gateway on :8080, filled by `npm run seed`).
const live = process.env.E2E_LIVE === '1' || process.argv.some((arg) => arg.includes('live'));

export default defineConfig({
  testDir: 'e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 30_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    ...devices['Desktop Chrome'],
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'mock', use: { baseURL: 'http://localhost:5174' } },
    { name: 'live', use: { baseURL: 'http://localhost:5173' } },
  ],
  webServer: live
    ? { command: 'npm run dev', port: 5173, reuseExistingServer: true, timeout: 60_000 }
    : { command: 'npm run dev:mock', port: 5174, reuseExistingServer: true, timeout: 60_000 },
});
