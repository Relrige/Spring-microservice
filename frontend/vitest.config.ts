import { defineConfig, mergeConfig } from 'vitest/config';
import viteConfig from './vite.config.ts';

export default defineConfig((env) =>
  mergeConfig(viteConfig(env), {
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      include: ['src/**/*.test.{ts,tsx}', 'scripts/**/*.test.ts'],
      css: false,
      restoreMocks: true,
      // Form tests type through whole forms; under a parallel run 5 s was too tight.
      testTimeout: 15_000,
    },
  }),
);
