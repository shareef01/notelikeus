import path from 'node:path';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';
import { VitePWA } from 'vite-plugin-pwa';

const PLACEHOLDER_PATTERNS = [/placeholder/i, /^your-/i, /^1:your-/];

function readEnvFile(filePath: string): Record<string, string> {
  if (!existsSync(filePath)) return {};
  const entries: Record<string, string> = {};
  for (const line of readFileSync(filePath, 'utf8').split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const index = trimmed.indexOf('=');
    if (index <= 0) continue;
    entries[trimmed.slice(0, index).trim()] = trimmed.slice(index + 1).trim();
  }
  return entries;
}

/** Prefer web/.env over stale shell exports (common source of "placeholder" production builds). */
function resolveFileEnv(mode: string, root: string): Record<string, string> {
  const merged = {
    ...readEnvFile(path.join(root, '.env')),
    ...readEnvFile(path.join(root, '.env.local')),
    ...readEnvFile(path.join(root, `.env.${mode}`)),
    ...readEnvFile(path.join(root, `.env.${mode}.local`)),
  };

  for (const [key, value] of Object.entries(merged)) {
    if (key.startsWith('VITE_')) {
      process.env[key] = value;
    }
  }

  return merged;
}

function assertFirebaseEnv(mode: string, env: Record<string, string>): void {
  if (mode !== 'production') return;

  const required = [
    'VITE_FIREBASE_API_KEY',
    'VITE_FIREBASE_AUTH_DOMAIN',
    'VITE_FIREBASE_PROJECT_ID',
    'VITE_FIREBASE_STORAGE_BUCKET',
    'VITE_FIREBASE_MESSAGING_SENDER_ID',
    'VITE_FIREBASE_APP_ID',
    'VITE_FIREBASE_GOOGLE_CLIENT_ID',
  ] as const;

  for (const key of required) {
    const value = env[key]?.trim();
    if (!value) {
      throw new Error(`[Notelikeus] Missing ${key}. Copy web/.env.example to web/.env and set Firebase values.`);
    }
    if (PLACEHOLDER_PATTERNS.some((pattern) => pattern.test(value))) {
      throw new Error(`[Notelikeus] ${key} still uses a placeholder value. Set real Firebase credentials before deploying.`);
    }
  }
}

export default defineConfig(({ mode }) => {
  const root = process.cwd();
  const fileEnv = resolveFileEnv(mode, root);
  assertFirebaseEnv(mode, fileEnv);
  loadEnv(mode, root, 'VITE_');

  const buildId = fileEnv.VITE_APP_BUILD_ID?.trim() || new Date().toISOString().slice(0, 10);
  process.env.VITE_APP_BUILD_ID = buildId;

  return {
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
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
  plugins: [
    react(),
    {
      name: 'notelikeus-build-info',
      closeBundle() {
        const distDir = path.resolve(__dirname, 'dist');
        const indexHtml = path.join(distDir, 'index.html');
        if (!existsSync(indexHtml)) return;

        const html = readFileSync(indexHtml, 'utf8');
        const match = html.match(/assets\/index-[^"']+\.js/);
        const buildInfo = {
          id: process.env.VITE_APP_BUILD_ID ?? new Date().toISOString(),
          main: match?.[0] ?? null,
        };
        writeFileSync(path.join(distDir, 'build-info.json'), JSON.stringify(buildInfo));
      },
    },
    VitePWA({
      strategies: 'injectManifest',
      srcDir: 'src',
      filename: 'sw.ts',
      registerType: 'autoUpdate',
      includeAssets: ['favicon.svg', 'icons/icon-192.png', 'icons/icon-512.png'],
      injectManifest: {
        globPatterns: ['**/*.{js,css,html,ico,png,svg,woff2}'],
        globIgnores: ['**/build-info.json'],
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
            url: '/note/new',
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
  };
});
