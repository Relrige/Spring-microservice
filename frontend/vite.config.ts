import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// Design D2: the browser calls /api/<gateway path>; the dev proxy strips /api
// and forwards to the API Gateway, so the app and the API share one origin.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const apiTarget = env.VITE_API_TARGET || 'http://localhost:8080';
  const mock = env.VITE_API_MOCK === 'true';

  return {
    plugins: [react()],
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
  };
});
