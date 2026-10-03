/*
 * Standalone vitest config for the Phones walk — see phones-walk.test.mts. Kept apart from
 * vite.config.ts for the same reason as vitest.paired.config.mts: the browser walk can never leak
 * into the product suite (`pnpm test`). Run it with `pnpm e2e:phones`.
 *
 * The walk plays a phone with libsodium, so it takes the alias vite.config.ts gives the product
 * for the same reason: the package's ESM build is broken and its CJS build is not.
 */
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  root: fileURLToPath(new URL('..', import.meta.url)),
  resolve: {
    alias: {
      'libsodium-wrappers-sumo': fileURLToPath(
        new URL('../node_modules/libsodium-wrappers-sumo/dist/modules-sumo/libsodium-wrappers.js', import.meta.url),
      ),
    },
  },
  test: {
    include: ['e2e/phones-walk.test.mts'],
    environment: 'node',
    testTimeout: 10 * 60_000,
    hookTimeout: 10 * 60_000,
  },
})
