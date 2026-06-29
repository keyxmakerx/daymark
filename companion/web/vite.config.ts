import { defineConfig } from 'vite'
import { svelte } from '@sveltejs/vite-plugin-svelte'

// The Companion can be served under a sub-path behind a reverse proxy (DAYMARK_BASE_PATH).
// At build time we bake a relative base so the same bundle works at "/" or "/daymark/…".
// `./` keeps every asset reference relative to index.html — the server can mount it anywhere.
export default defineConfig({
  base: './',
  plugins: [svelte()],
  build: {
    target: 'es2022',
    outDir: 'dist',
    emptyOutDir: true,
    assetsInlineLimit: 0, // never inline as data: URIs — keeps the CSP free of data: in script/style
    sourcemap: false,
    rollupOptions: {
      output: {
        // Stable, hashed names so the bundle is cache-bustable and reproducible.
        entryFileNames: 'assets/[name]-[hash].js',
        chunkFileNames: 'assets/[name]-[hash].js',
        assetFileNames: 'assets/[name]-[hash][extname]',
      },
    },
  },
})
