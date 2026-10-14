import { writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => ({
  plugins: [
    react(),
    {
      // web.go embeds dist/, which must exist even before the first build
      // (for `go vet` and the Go tests). A committed dist/.gitkeep guarantees
      // that; put it back after Vite empties the folder.
      name: 'keep-dist-placeholder',
      apply: (_, env) => env.mode !== 'android', // that build goes to dist-android/
      closeBundle() {
        writeFileSync('dist/.gitkeep', '');
      },
    },
  ],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    // index.html is the app, which the server serves at /app/; landing.html
    // is the introduction at /. The Android app has no use for the latter.
    rollupOptions: {
      input: {
        app: resolve(import.meta.dirname, 'index.html'),
        ...(mode === 'android' ? {} : { landing: resolve(import.meta.dirname, 'landing.html') }),
      },
    },
  },
  // `npm run dev` serves the UI with hot reload and forwards /api to the Go
  // server (e.g. the local Docker stack on :8080).
  server: { proxy: { '/api': 'http://localhost:8080' } },
}));
