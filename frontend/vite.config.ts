import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// Design D2: the browser calls /api/<gateway path>; the dev proxy strips /api
// and forwards to the API Gateway, so the app and the API share one origin.
// Mode `mock` (npm run dev:mock, design D10) answers /api with MSW instead.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const apiTarget = env.VITE_API_TARGET || 'http://localhost:8080';
  const mock = mode === 'mock' || env.VITE_API_MOCK === 'true';

  return {
    plugins: [react()],
    define: {
      'import.meta.env.VITE_API_MOCK': JSON.stringify(mock ? 'true' : 'false'),
    },
    server: {
      port: mock ? 5174 : 5173,
      strictPort: true,
      proxy: mock
        ? undefined
        : {
            '/api': {
              target: apiTarget,
              changeOrigin: true,
              rewrite: (path) => path.replace(/^\/api/, ''),
            },
          },
    },
    preview: {
      port: 4173,
      strictPort: true,
    },
  };
});
