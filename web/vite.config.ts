import path from 'node:path';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';
import { VitePWA } from 'vite-plugin-pwa';

export default defineConfig({
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  test: {
    environment: 'happy-dom',
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    // happy-dom ships no IndexedDB, which is where the locked-note key lives.
    setupFiles: ['./src/test/setup.ts'],
  },
  plugins: [
    react(),
    VitePWA({
      strategies: 'injectManifest',
      srcDir: 'src',
      filename: 'sw.ts',
      registerType: 'autoUpdate',
      // main.tsx registers via `virtual:pwa-register`. Pinned to null so the plugin can never
      // fall back to injecting an inline registration script — the CSP in firebase.json has no
      // 'unsafe-inline' in script-src, and an injected inline script would be blocked.
      injectRegister: null,
      includeAssets: ['favicon.svg', 'icons/icon-192.png', 'icons/icon-512.png'],
      injectManifest: {
        globPatterns: ['**/*.{js,css,html,ico,png,svg,woff2}'],
      },
      manifest: {
        name: 'Notelikeus',
        short_name: 'Notelikeus',
        description: 'Minimal, premium notes — synced across devices.',
        id: '/',
        theme_color: '#000000',
        background_color: '#000000',
        display: 'standalone',
        orientation: 'any',
        scope: '/',
        start_url: '/',
        categories: ['productivity', 'utilities'],
        shortcuts: [
          {
            name: 'New note',
            short_name: 'New',
            url: '/?new=1',
            icons: [{ src: '/icons/icon-192.png', sizes: '192x192', type: 'image/png' }],
          },
        ],
        icons: [
          {
            src: '/icons/icon-192.png',
            sizes: '192x192',
            type: 'image/png',
            purpose: 'any',
          },
          {
            src: '/icons/icon-512.png',
            sizes: '512x512',
            type: 'image/png',
            purpose: 'any',
          },
          {
            src: '/icons/icon-512.png',
            sizes: '512x512',
            type: 'image/png',
            purpose: 'maskable',
          },
        ],
      },
      devOptions: {
        enabled: false,
      },
    }),
  ],
  server: {
    port: 5173,
    strictPort: true,
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return;
          if (id.includes('firebase')) return 'firebase';
          if (id.includes('react-dom') || id.includes('react/') || id.includes('scheduler')) {
            return 'react-vendor';
          }
          if (id.includes('zustand')) return 'zustand';
        },
      },
    },
  },
});
