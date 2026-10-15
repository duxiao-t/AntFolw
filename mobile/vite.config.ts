import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';
import { defineConfig, searchForWorkspaceRoot } from 'vite';

const outputPath = process.env.ANTFLOW_OUTPUT_PATH || 'dist';

export default defineConfig({
  base: '/mobile/',
  plugins: [react()],
  server: {
    fs: {
      allow: [searchForWorkspaceRoot(process.cwd()), fileURLToPath(new URL('../shared', import.meta.url))],
    },
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: outputPath,
    manifest: true,
    // Keep default minification; budget is enforced on gzip via scripts/check-bundle-budget.mjs
  },
});
