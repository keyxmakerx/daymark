/// <reference types="vitest/config" />
import { defineConfig } from 'vitest/config'
import { svelte } from '@sveltejs/vite-plugin-svelte'
import { fileURLToPath, URL as NodeURL } from 'node:url'

// libsodium-wrappers ships a broken ESM build (its .mjs imports a sibling that isn't
// published); the CJS build is fine. Alias to it so both the browser bundle and the
// Node tests load a working module. Everything stays vendored — no CDN.
const sodiumCjs = fileURLToPath(new URL('node_modules/libsodium-wrappers-sumo/dist/modules-sumo/libsodium-wrappers.js', import.meta.url))

export default defineConfig({
  base: './',
  plugins: [svelte()],
  resolve: {
    alias: {
      'libsodium-wrappers-sumo': sodiumCjs,
    },
  },
  build: {
    target: 'es2022',
    outDir: 'dist',
    emptyOutDir: true,
    assetsInlineLimit: 0,
    sourcemap: false,
    rollupOptions: {
      // Multi-page build: the owner report viewer (index.html) and the SEPARATE therapist portal
      // (therapist.html) are distinct entries/surfaces served at distinct routes.
      input: {
        index: fileURLToPath(new NodeURL('index.html', import.meta.url)),
        therapist: fileURLToPath(new NodeURL('therapist.html', import.meta.url)),
      },
      output: {
        entryFileNames: 'assets/[name]-[hash].js',
        chunkFileNames: 'assets/[name]-[hash].js',
        assetFileNames: 'assets/[name]-[hash][extname]',
      },
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
    testTimeout: 30000,
    hookTimeout: 30000,
  },
})
