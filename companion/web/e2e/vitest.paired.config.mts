/*
 * Standalone vitest config for the Paired-loop browser test — see paired-loop.test.mts. Kept apart
 * from vite.config.ts for the same reason as vitest.cpace-live.config.mts: the browser test can
 * never leak into the product suite (`pnpm test`), and the product suite's include can never
 * silence it. Run it with `pnpm e2e:paired`.
 *
 * vitest is the runner here because it already transpiles TypeScript and resolves this package's
 * modules the way the bundle does; the test imports the backup shape and the pairing copy from
 * src/ rather than restating them.
 */
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  root: fileURLToPath(new URL('..', import.meta.url)),
  test: {
    include: ['e2e/paired-loop.test.mts'],
    environment: 'node',
    // One test, two browsers, a real server and a clinician poll that runs every 45 seconds.
    testTimeout: 10 * 60_000,
    // Building the jar from cold and the bundle happen in beforeAll.
    hookTimeout: 10 * 60_000,
  },
})
