/*
 * Transport wrapper for cpace-live.mts — vitest is used here as a TS runner with a working
 * module resolver, not as a test framework (see that file's header for why plain tsx cannot
 * load libsodium-wrappers-sumo). The single "test" performs one protocol step named by
 * CPACE_MODE and writes the JSON result to CPACE_OUT. It is reachable only through
 * vitest.cpace-live.config.mts; the product suite's include (src/**) never picks it up.
 */
import { writeFileSync } from 'node:fs'
import { it } from 'vitest'
import { runLiveCpace } from './cpace-live.mts'

it('runs one live CPace step for the interop harness', async () => {
  const mode = process.env.CPACE_MODE
  const out = process.env.CPACE_OUT
  if (!mode || !out) throw new Error('CPACE_MODE and CPACE_OUT are required — see cpace-live.mts')
  const args = (process.env.CPACE_ARGS ?? '').split(',').filter((s) => s.length > 0)
  writeFileSync(out, JSON.stringify(await runLiveCpace(mode, args)))
})
