import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// The dev server proxies the API and the WebSocket so the browser sees a single origin.
// That keeps the app's fetch/WS URLs relative, which is also how it runs in production when
// CloudFront fronts both the S3 bucket and the ALB. VITE_API_BASE_URL exists for the
// split-origin deployment instead (see src/api/client.ts).
const API_TARGET = process.env.VITE_DEV_API_TARGET ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: API_TARGET, changeOrigin: true },
      '/ws': { target: API_TARGET, changeOrigin: true, ws: true },
      '/actuator': { target: API_TARGET, changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});
