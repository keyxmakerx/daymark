/*
 * Standalone vitest config for the practice sign-in browser test — see practice-signin.test.mts.
 * Kept apart from vite.config.ts for the same reason as vitest.paired.config.mts: the browser test
 * can never leak into the product suite (`pnpm test`). Run it with `pnpm e2e:practice`.
 */
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  root: fileURLToPath(new URL('..', import.meta.url)),
  test: {
    include: ['e2e/practice-signin.test.mts'],
    environment: 'node',
    testTimeout: 5 * 60_000,
    hookTimeout: 10 * 60_000,
  },
})
