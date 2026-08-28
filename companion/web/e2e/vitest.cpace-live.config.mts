/*
 * Standalone vitest config for the CPace live-interop driver — see cpace-live.mts. Kept apart
 * from vite.config.ts so the driver can never leak into the product suite, and the product
 * suite's include can never silence the driver.
 */
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  root: fileURLToPath(new URL('..', import.meta.url)),
  resolve: {
    alias: {
      // Same alias vite.config.ts carries, for the same reason: the sumo package's ESM dist
      // references libsodium-sumo.mjs at a path that only exists in the sibling package, so
      // every resolver has to be pointed at the CJS build instead.
      'libsodium-wrappers-sumo': fileURLToPath(
        new URL('../node_modules/libsodium-wrappers-sumo/dist/modules-sumo/libsodium-wrappers.js', import.meta.url),
      ),
    },
  },
  test: {
    include: ['e2e/cpace-live.driver.test.mts'],
    environment: 'node',
  },
})
