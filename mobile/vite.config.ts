import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  base: '/mobile/',
  plugins: [react()],
  server: {
    proxy: {
      '/api': { target: 'http://localhost:8081', changeOrigin: true },
    },
  },
  build: {
    manifest: true,
    // Keep default minification; budget is enforced on gzip via scripts/check-bundle-budget.mjs
  },
});
